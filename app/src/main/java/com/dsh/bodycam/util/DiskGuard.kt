package com.dsh.bodycam.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import java.io.File

/**
 * 磁盘空间的兜底管理。
 *
 * 长时间连续执法时存储会写满，写满之后继续写入会失败、分段报废。这里做两件事：
 *  1. 按 `StatFs` 报剩余空间，分成「告警线」与「强制停录线」两级；
 *  2. 循环录像开着时，按修改时间从旧到新删 `BodyCam/Video` 下已结束的分段，
 *     直到剩余空间回到告警线以上。
 *
 * **只动自己的目录**：扫描范围限定在 `BodyCam/Video` 下的日期子目录，不碰系统或其它应用的文件。
 *
 * **当前分段永不删除**：调用方把正在写的文件名传进来（[cleanup] 的 `keep`），
 * 命中的一律跳过。清理只针对 `partNN` 已完成收尾的文件。
 */
object DiskGuard {

    private const val TAG = "DiskGuard"

    /** 一次空间检查的结论 */
    enum class Level {
        /** 空间充足 */
        OK,

        /** 低于告警线：循环录像开时已清理，关时只提示 */
        WARN,

        /** 低于强制停录线：无论循环录像是否开启都要停 */
        STOP
    }

    data class Status(
        val level: Level,
        val freeBytes: Long,
        val warnBytes: Long,
        val stopBytes: Long,
        /** 本次清理删掉的字节数；0 表示没删或没得删 */
        val freedBytes: Long = 0L,
        /** 本次清理删掉的文件数 */
        val removedCount: Int = 0
    )

    /** 告警阈值（字节） */
    fun warnBytes(context: Context): Long =
        SettingsStore.diskWarnMb(context).coerceAtLeast(1) * MB

    /**
     * 强制停录阈值（字节）：告警线的 1/[AppConfig.DISK_STOP_DIVISOR]，
     * 且不低于 [AppConfig.DISK_STOP_MIN_MB]。默认 500MB → 100MB。
     */
    fun stopBytes(context: Context): Long {
        val byDiv = warnBytes(context) / AppConfig.DISK_STOP_DIVISOR
        return maxOf(byDiv, AppConfig.DISK_STOP_MIN_MB * MB)
    }

    /** 录像根目录：`/sdcard/BodyCam/Video` */
    @Suppress("DEPRECATION")
    fun videoDir(): File = File(Environment.getExternalStorageDirectory(), "BodyCam/Video")

    /** 当前可用字节数；拿不到时返回 [Long.MAX_VALUE]，宁可不停录 */
    fun freeBytes(): Long = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        stat.availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "statFs failed", e)
        Long.MAX_VALUE
    }

    /** 只读检查，不删任何东西 */
    fun inspect(context: Context): Status {
        val free = freeBytes()
        val warn = warnBytes(context)
        val stop = stopBytes(context)
        return Status(levelOf(free, warn, stop), free, warn, stop)
    }

    /**
     * 检查空间，必要时清理。
     *
     * @param loopRecord 循环录像是否开启。关闭时**不删任何文件**，只在低于告警线时
     *   返回 [Level.WARN]（由调用方提示）。
     * @param keepProvider 每次删除前查询「当前正在写入的分段文件名」。
     *   用回调而不是一次性字符串：清理过程中录像可能刚好切到下一个分段，
     *   固定值会漏掉新分段，把它当成可删的旧文件。
     */
    fun check(
        context: Context,
        loopRecord: Boolean,
        keepProvider: () -> String? = { null }
    ): Status {
        val free = freeBytes()
        val warn = warnBytes(context)
        val stop = stopBytes(context)
        val level = levelOf(free, warn, stop)
        if (level == Level.OK || !loopRecord) {
            return Status(level, free, warn, stop)
        }
        // 低于告警线且开了循环录像：删到回到告警线以上
        val result = cleanup(context, keepProvider, warn)
        val after = freeBytes()
        return Status(
            levelOf(after, warn, stop),
            after,
            warn,
            stop,
            result.freedBytes,
            result.removedCount
        )
    }

    private fun levelOf(free: Long, warn: Long, stop: Long): Level = when {
        free < stop -> Level.STOP
        free < warn -> Level.WARN
        else -> Level.OK
    }

    private data class CleanupResult(val freedBytes: Long, val removedCount: Int)

    /**
     * 从旧到新删文件直到可用空间 ≥ [targetBytes]。
     *
     * 排序按修改时间升序（同时间按名字升序），所以最早的分段先走。
     * 只扫两层（`Video/yyyyMMdd/VID_*.mp4`），不递归其它子目录。
     */
    private fun cleanup(
        context: Context,
        keepProvider: () -> String?,
        targetBytes: Long
    ): CleanupResult {
        var freed = 0L
        var removed = 0
        val candidates = try {
            collectVideoFiles()
        } catch (e: Exception) {
            Log.w(TAG, "collect failed", e)
            return CleanupResult(0, 0)
        }
        val deleted = ArrayList<File>()
        for (f in candidates) {
            if (freeBytes() >= targetBytes) break
            // 每删一个都重查：清理期间可能已经切到新分段
            if (f.name == keepProvider()) continue
            val size = f.length()
            if (!f.delete()) {
                Log.w(TAG, "delete failed: ${f.path}")
                continue
            }
            freed += size
            removed++
            deleted += f
            Log.i(TAG, "removed ${f.name} (${size / MB}MB)")
        }
        // 删掉的已进过媒体库，通知扫描器移除索引，否则图库里留着失效条目
        if (deleted.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    deleted.map { it.absolutePath }.toTypedArray(),
                    null,
                    null
                )
            }
        }
        return CleanupResult(freed, removed)
    }

    /** 已结束的分段，按修改时间从旧到新 */
    private fun collectVideoFiles(): List<File> {
        val root = videoDir()
        val days = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<File>()
        for (day in days) {
            // 只认本应用录出来的分段（VID_ 前缀）：目录里可能有用户手动放进来的
            // 其它 mp4，那些不是我们的东西，不该被循环录像删掉
            day.listFiles { f ->
                f.isFile && f.name.startsWith(VIDEO_PREFIX) && f.name.endsWith(".mp4", true)
            }?.let { out.addAll(it) }
        }
        out.sortWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        return out
    }

    /**
     * 本应用录像分段的文件名前缀，与 [SegmentRecorder] 的命名保持一致
     * （`VID_yyyyMMdd_HHmmss_partNN.mp4`）。
     */
    private const val VIDEO_PREFIX = "VID_"

    private const val MB = 1024L * 1024L
}