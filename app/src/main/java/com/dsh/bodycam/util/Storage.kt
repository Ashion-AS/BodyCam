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

    /**
     * 建条目的失败原因。
     *
     * 必须区分「致命」与「可重试」：前者在部分 ROM 上会稳定复现且无法自愈
     * （例如 MTK 的 FUSE 不给 app 写 `/storage/emulated/0` 的权限，即使
     * MANAGE_EXTERNAL_STORAGE 显示已授予），录制器必须立刻中止而不是
     * 空转几十秒后交出一个 0 字节的结果。
     */
    enum class FailReason {
        /** 没拿到存储权限（用户没开「所有文件访问」，或运行时权限被拒） */
        NO_PERMISSION,

        /** 目录建不出来，或文件打不开。权限被 ROM 拒绝、只读挂载都归这里 */
        NOT_WRITABLE,

        /** 磁盘已满（ENOSPC），腾出空间后可以重试 */
        NO_SPACE,

        /** 其它 IO 问题，通常可重试 */
        IO_ERROR
    }

    /** 建条目的结果：成功带 [Entry]，失败带 [FailReason] */
    class CreateResult(val entry: Entry?, val failReason: FailReason?) {
        /** 失败原因；成功时为 null */
        val error: FailReason? get() = failReason

        companion object {
            fun ok(entry: Entry) = CreateResult(entry, null)
            fun fail(reason: FailReason) = CreateResult(null, reason)
        }
    }

    /** 一个正在写或已写完的输出条目 */
    class Entry(
        val displayName: String,
        private val context: Context,
        private val file: File
    ) {
        /** 落盘文件句柄；收尾加密要用它，所以不暴露可写流，只给 File */
        fun fileOrNull(): File? = if (file.exists()) file else null

        /** 本条目打开失败的原因；仅 [openReadWrite] / [openWrite] 失败后有意义 */
        var openFailReason: FailReason? = null
            private set

        /** 给 muxer 用的读写 FD；拿不到返回 null，原因见 [openFailReason] */
        fun openReadWrite(): ParcelFileDescriptor? {
            return try {
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            } catch (e: Exception) {
                Log.e(TAG, "openReadWrite $displayName", e)
                openFailReason = when {
                    runCatching { file.parentFile?.usableSpace }.getOrNull() == 0L ->
                        FailReason.NO_SPACE
                    e is java.io.FileNotFoundException -> FailReason.NOT_WRITABLE
                    else -> FailReason.IO_ERROR
                }
                null
            }
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
    fun createVideo(context: Context, displayName: String): CreateResult =
        create(context, displayName, "Video")

    /** 建一个待写音频条目，落在 BodyCam/Audio/yyyyMMdd */
    fun createAudio(context: Context, displayName: String): CreateResult =
        create(context, displayName, "Audio")

    /**
     * 直接存一张照片，落在 BodyCam/Image/yyyyMMdd。
     *
     * 失败原因随结果一起返回，不用全局变量：拍照是并发执行的（连拍每张一个
     * 任务跑在 photoExecutor 上），全局变量会被后一张覆盖，弹出来的提示就串了。
     */
    fun savePhoto(context: Context, bytes: ByteArray, displayName: String): PhotoResult {
        val result = create(context, displayName, "Image")
        val entry = result.entry ?: return PhotoResult.fail(result.failReason)
        val ok = try {
            entry.openWrite()?.use { it.write(bytes) } != null
        } catch (e: Exception) {
            Log.e(TAG, "write photo $displayName", e)
            false
        }
        if (!ok) {
            entry.abort()
            return PhotoResult.fail(FailReason.IO_ERROR)
        }
        entry.commit()
        return PhotoResult.ok(displayName)
    }

    /** 拍照落盘的结果：成功带显示名，失败带原因 */
    class PhotoResult(val name: String?, val failReason: FailReason?) {
        companion object {
            fun ok(name: String) = PhotoResult(name, null)
            fun fail(reason: FailReason?) = PhotoResult(null, reason)
        }
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

        /**
         * 录制时间，从文件名里的 `yyyyMMdd_HHmmss` 解析。
         *
         * 不能用 [File.lastModified]：播放视频时会跑 MuxerFix 回填 duration 并重写
         * 整个文件，修改时间被刷成「刚才」，列表里那条就跳到第一位了。
         * 文件名是录制时定下的、之后不再变，才是稳定的时间来源。
         */
        val modified: Long = parseRecordTime(file)
    }

    /** 文件名里的时间戳片段（`20261120_143022`），三段式前缀之后不一定紧跟，靠正则找 */
    private val RECORD_STAMP = Regex("""(\d{8}_\d{6})""")

    /** 解析文件名中的录制时间；解析不出来（异常命名）才退回文件修改时间 */
    private fun parseRecordTime(file: File): Long {
        val match = RECORD_STAMP.find(file.name) ?: return file.lastModified()
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(match.value)?.time
                ?: file.lastModified()
        } catch (_: Exception) {
            file.lastModified()
        }
    }

    /**
     * 列出某类型下的所有文件，最新的在最上面。
     *
     * 按文件名里的录制时间倒序（见 [MediaFile.modified]），不按文件修改时间：
     * 播放视频会触发 MuxerFix 重写文件，修改时间不再代表录制时间。
     * 时间相同（同一秒开录的多段）再按文件名倒序，文件名精度到毫秒，不会乱序。
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
        // 最新在最上：按 filename 里的录制时间倒序，时间相同再按名字倒序
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
    private fun create(context: Context, displayName: String, typeDir: String): CreateResult {
        if (!StoragePermission.isGranted(context)) {
            Log.e(TAG, "no storage access, skip $displayName")
            return CreateResult.fail(FailReason.NO_PERMISSION)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), "$ROOT/$typeDir/${today()}")
        if (!dir.exists() && !dir.mkdirs()) {
            // mkdirs 失败有两种：目录其实已存在（并发/已建好），或真的建不出来。
            // 前者不是错误，继续往下；后者按失败上报。
            if (!dir.exists()) {
                return CreateResult.fail(classifyCreateFailure(dir, "mkdirs failed: $dir"))
            }
            if (!dir.canWrite()) {
                val reason = classifyCreateFailure(dir, "dir not writable: $dir")
                return CreateResult.fail(reason)
            }
        }
        return CreateResult.ok(Entry(displayName, context.applicationContext, File(dir, displayName)))
    }

    /**
     * 目录建不出来时判定原因。
     *
     * 关键坑：[dir] 在这个时刻**不存在**，而 `File.usableSpace` 对不存在的路径
     * 会做 statvfs 失败并返回 0——直接拿它判断会把「权限被拒」误报成「磁盘已满」。
     * 所以必须往上找到第一个存在的祖先目录，那里的剩余空间才是真的。
     */
    private fun classifyCreateFailure(dir: File, message: String): FailReason {
        val anchor = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }
        val free = anchor?.usableSpace ?: -1L
        // 空间充足却建不出目录 = 权限/只读挂载（MTK 的 FUSE 拒绝就落这里）
        val reason = if (free == 0L) FailReason.NO_SPACE else FailReason.NOT_WRITABLE
        Log.e(TAG, "$message (reason=$reason free=$free)")
        return reason
    }
}