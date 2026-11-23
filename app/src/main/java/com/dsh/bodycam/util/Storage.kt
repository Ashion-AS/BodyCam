package com.dsh.bodycam.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输出落盘：直接写公共存储，再让 MediaScanner 把文件索引进相册/音乐库。
 *
 * 目录约定（与系统 DCIM 同级，统一放在 BodyCam 下，按类型再按天分文件夹）：
 *   /sdcard/BodyCam/Video/yyyyMMdd/  视频
 *   /sdcard/BodyCam/Image/yyyyMMdd/  照片
 *   /sdcard/BodyCam/Audio/yyyyMMdd/  录音
 *
 * 为什么不用 MediaStore.insert：Android 11+ 的 MediaProvider 只允许媒体插入到
 * 固定白名单目录（Images→DCIM/Pictures，Audio→Music/Ringtones，Files→Download/
 * Documents），自定义顶层目录（如 BodyCam）会被拒绝——这正是之前音频报
 * 「Primary directory DCIM not allowed」的原因。所以这里改为**直接文件写入**
 * + [MediaScannerConnection] 扫描：文件系统层面不受白名单限制，扫描器会把任意
 * 位置下的媒体文件正常索引进媒体库。
 *
 * 权限：
 *   API 30+  需要「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE），由
 *            [StoragePermission] 引导用户在系统设置里开启。
 *   API 26-29 需要 WRITE_EXTERNAL_STORAGE（Android 10 靠清单里的
 *            requestLegacyExternalStorage 恢复旧行为）。
 *
 * 分两阶段：先建条目（此时只是空占位/尚未扫描），写完 [Entry.commit] 才扫描
 * 入库；没录到帧或写失败就 [Entry.abort] 删掉。这样相册里不会留下半成品或
 * 0 字节空壳。
 */
object Storage {

    private const val TAG = "Storage"
    private const val VIDEO_MIME = "video/mp4"
    private const val PHOTO_MIME = "image/jpeg"
    private const val AUDIO_MIME = "audio/mp4"

    /** 与 DCIM 同级的应用根目录 */
    private const val ROOT = "BodyCam"

    /** 一个正在写或已写完的输出条目 */
    class Entry(
        val displayName: String,
        private val context: Context,
        private val file: File
    ) {
        /** 落盘文件句柄；收尾加密要用它，所以不暴露可写流，只给 File */
        fun fileOrNull(): File? = if (file.exists()) file else null

        /** 给 muxer 用的读写 FD；拿不到返回 null */
        fun openReadWrite(): ParcelFileDescriptor? = try {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "openReadWrite $displayName", e)
            null
        }

        fun openWrite(): OutputStream? = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "openWrite $displayName", e)
            null
        }

        /** 写完：让 MediaScanner 把文件索引进相册/音乐库 */
        fun commit() {
            try {
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf(mimeOf(displayName)), null
                )
            } catch (e: Exception) {
                Log.w(TAG, "commit $displayName", e)
            }
        }

        /** 放弃：删文件 */
        fun abort() {
            try {
                if (file.exists() && !file.delete()) Log.w(TAG, "abort: delete failed ${file.path}")
            } catch (e: Exception) {
                Log.w(TAG, "abort $displayName", e)
            }
        }

        /**
         * 回填 mvhd / tkhd / mdhd 的 duration 字段。
         *
         * media3 的 FragmentedMp4Muxer 写 moov 时这三处一律填 0（mvex 里也没有 mehd），
         * 而 moov 是在首个样本之前就落盘的，那时还不可能知道总时长。结果就是文件里
         * 没有任何声明总时长的字段：media3 的 FragmentedMp4Extractor 读 moov 时拿到
         * durationUs = TIME_UNSET，SeekMap 退化成 Unseekable(TIME_UNSET)，ExoPlayer
         * 认为这条流时长 0，READY 之后立刻 ENDED，一帧都不播。
         *
         * 单轨音频必然踩到；视频因为还有另一条轨（且播放器对 video 有额外处理）才
         * 侥幸没暴露。电脑上的播放器（ffmpeg/VLC）会自行扫描分片算时长，所以能放。
         *
         * 只改 4 字节、位置和长度都不变，不破坏 tfhd 里存的绝对偏移。
         */
        fun patchDurations() {
            try {
                MuxerFix.patchDurations(file)
            } catch (e: Exception) {
                Log.w(TAG, "patchDurations $displayName", e)
            }
        }
    }

    /** 建一个待写视频条目，落在 BodyCam/Video/yyyyMMdd */
    fun createVideo(context: Context, displayName: String): Entry? =
        create(context, displayName, "Video")

    /** 建一个待写音频条目，落在 BodyCam/Audio/yyyyMMdd */
    fun createAudio(context: Context, displayName: String): Entry? =
        create(context, displayName, "Audio")

    /** 直接存一张照片，落在 BodyCam/Image/yyyyMMdd；成功返回显示名 */
    fun savePhoto(context: Context, bytes: ByteArray, displayName: String): String? {
        val entry = create(context, displayName, "Image") ?: return null
        val ok = try {
            entry.openWrite()?.use { it.write(bytes) } != null
        } catch (e: Exception) {
            Log.e(TAG, "write photo $displayName", e)
            false
        }
        if (!ok) {
            entry.abort()
            return null
        }
        entry.commit()
        return displayName
    }

    fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** 媒体类型，对应 [Storage] 的三条落盘子目录 */
    enum class Kind(val dirName: String, val extensions: List<String>) {
        IMAGE("Image", listOf("jpg", "jpeg", "png")),
        VIDEO("Video", listOf("mp4", "m4v")),
        AUDIO("Audio", listOf("m4a", "mp4", "aac"))
    }

    /** 文件页用的只读条目 */
    class MediaFile(val file: File) {
        val name: String get() = file.name
        val length: Long get() = file.length()
        val modified: Long get() = file.lastModified()
    }

    /**
     * 列出某类型下的所有文件，最新的在最上面。
     *
     * 先比文件修改时间，时间相同（粗粒度文件系统）再按文件名倒序；
     * 文件名的 stamp 精度到毫秒，同秒连拍也不会乱序。
     *
     * @return 没有权限时返回空列表，由调用方提示
     */
    fun list(context: Context, kind: Kind): List<MediaFile> {
        if (!StoragePermission.isGranted(context)) return emptyList()
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStorageDirectory(), "$ROOT/${kind.dirName}")
        val days = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<MediaFile>()
        for (day in days) {
            val files = day.listFiles { f ->
                f.isFile && kind.extensions.any { f.name.endsWith(".$it", true) }
            } ?: continue
            files.forEach { out += MediaFile(it) }
        }
        // 最新在最上：先按修改时间倒序，时间相同（粗粒度文件系统）再按名字倒序
        out.sortWith(
            compareByDescending<MediaFile> { it.modified }.thenByDescending { it.name }
        )
        return out
    }

    /** 当天日期文件夹名，yyyyMMdd */
    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun mimeOf(displayName: String): String = when {
        displayName.endsWith(".mp4", true) -> VIDEO_MIME
        displayName.endsWith(".m4a", true) -> AUDIO_MIME
        else -> PHOTO_MIME
    }

    /**
     * @param typeDir 类型子目录名：`Video` / `Image` / `Audio`；会在其后拼上 `/yyyyMMdd`。
     */
    @SuppressLint("MissingPermission") // 权限由调用方通过 StoragePermission 保证
    private fun create(context: Context, displayName: String, typeDir: String): Entry? {
        if (!StoragePermission.isGranted(context)) {
            Log.e(TAG, "no storage access, skip $displayName")
            return null
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), "$ROOT/$typeDir/${today()}")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "mkdirs failed: $dir")
            return null
        }
        return Entry(displayName, context.applicationContext, File(dir, displayName))
    }
}