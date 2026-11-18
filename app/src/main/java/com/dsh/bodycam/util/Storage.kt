package com.dsh.bodycam.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输出落盘：写进系统相册，拍完录完就能在相册里看到。
 *
 * 目录约定（均在公共 DCIM 下，按类型再按天分文件夹）：
 *   DCIM/Video/yyyyMMdd/  视频
 *   DCIM/Image/yyyyMMdd/  照片
 *   DCIM/Audio/yyyyMMdd/  录音
 *
 * 两条路：
 *   API 29+   MediaStore + RELATIVE_PATH，不需要任何存储权限。
 *   API 26-28 没有 RELATIVE_PATH，只能写公共目录 DCIM 下的同名子目录，
 *             需要 WRITE_EXTERNAL_STORAGE，写完再用 MediaScanner 通知相册。
 *
 * 音频特意写 DCIM/Audio，但 `MediaStore.Audio` 只接受 Music/、Ringtones/ 等
 * 白名单目录（放进 DCIM 会抛 IllegalArgumentException），所以音频在 API 29+
 * 走 [MediaStore.Files] 集合插入，它允许任意公共目录；记录照样会被相册/音乐
 * 应用按 MIME 扫描识别。
 *
 * 分两阶段：先插条目拿到 URI/FD，此时内容对相册不可见（IS_PENDING=1，
 * 旧系统靠尚未扫描），写完 [Entry.commit] 才放出来；没录到帧或写失败就
 * [Entry.abort]。这样相册里不会留下半成品或 0 字节空壳。
 */
object Storage {

    private const val TAG = "Storage"
    private const val VIDEO_MIME = "video/mp4"
    private const val PHOTO_MIME = "image/jpeg"
    private const val AUDIO_MIME = "audio/mp4"

    /** 一个正在写或已写完的输出条目 */
    class Entry(
        val displayName: String,
        private val context: Context,
        private val uri: Uri?,
        private val file: File?
    ) {
        private val resolver: ContentResolver get() = context.contentResolver

        /** 给 MediaMuxer 用的读写 FD；拿不到返回 null */
        fun openReadWrite(): ParcelFileDescriptor? = try {
            if (uri != null) {
                resolver.openFileDescriptor(uri, "rw")
            } else if (file != null) {
                @Suppress("DEPRECATION")
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "openReadWrite $displayName", e)
            null
        }

        fun openWrite(): OutputStream? = try {
            if (uri != null) {
                resolver.openOutputStream(uri)
            } else if (file != null) {
                FileOutputStream(file)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "openWrite $displayName", e)
            null
        }

        /** 写完：对相册放行 */
        // IS_PENDING 只在 API 29+ 的条目上写；那时 uri 才非空，旧系统走 file 分支。
        // 这个常量编译期内联成字符串，运行时不需要系统提供该字段，故不设 SDK_INT 分支。
        @SuppressLint("InlinedApi")
        fun commit() {
            try {
                if (uri != null) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, cv, null, null)
                } else if (file != null) {
                    // 旧系统写的是公共目录，相册要扫一遍才知道
                    MediaScannerConnection.scanFile(
                        context, arrayOf(file.absolutePath), arrayOf(mimeOf(displayName)), null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "commit $displayName", e)
            }
        }

        /** 放弃：删条目和文件 */
        fun abort() {
            try {
                if (uri != null) resolver.delete(uri, null, null) else file?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "abort $displayName", e)
            }
        }
    }

    /**
     * 建一个待写视频条目，落在 DCIM/Video/yyyyMMdd。
     *
     * 用 DCIM 而不是 Movies：Google 相册这类主图库只索引 DCIM/ 与 Pictures/，
     * 落在 Movies/ 的视频虽然 MediaStore 里有记录、系统相册也能扫到，
     * 但在 Google 相册主视图里看不到，必须进「设备文件夹」翻。
     */
    fun createVideo(context: Context, displayName: String): Entry? =
        create(context, displayName, VIDEO_MIME, "DCIM/Video")

    /**
     * 建一个待写音频条目，落在 DCIM/Audio/yyyyMMdd。
     * 同样是先写、写完 [Entry.commit] 才对其它应用放行。
     */
    fun createAudio(context: Context, displayName: String): Entry? =
        create(context, displayName, AUDIO_MIME, "DCIM/Audio")

    /** 直接存一张照片，落在 DCIM/Image/yyyyMMdd；成功返回显示名 */
    fun savePhoto(context: Context, bytes: ByteArray, displayName: String): String? {
        val entry = create(context, displayName, PHOTO_MIME, "DCIM/Image")
            ?: return null
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

    /** 当天日期文件夹名，yyyyMMdd */
    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun mimeOf(displayName: String): String = when {
        displayName.endsWith(".mp4", true) -> VIDEO_MIME
        displayName.endsWith(".m4a", true) -> AUDIO_MIME
        else -> PHOTO_MIME
    }

    /**
     * @param baseDir 相对公共存储的类型目录，如 `DCIM/Video`；会在其后拼上 `/yyyyMMdd`。
     */
    private fun create(
        context: Context,
        displayName: String,
        mime: String,
        baseDir: String
    ): Entry? {
        val relativePath = "$baseDir/${today()}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 音频不能进 MediaStore.Audio（只认 Music/ 等白名单），改走 Files 集合。
            val collection = when (mime) {
                VIDEO_MIME -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                AUDIO_MIME -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                context.contentResolver.insert(collection, values)
            } catch (e: Exception) {
                Log.e(TAG, "insert $displayName", e)
                null
            }
            return uri?.let { Entry(displayName, context.applicationContext, it, null) }
        }

        // API 26-28：没有 RELATIVE_PATH，只能写公共目录；子目录不存在要手动建
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), relativePath.removePrefix("DCIM/"))
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "mkdirs failed: $dir")
            return null
        }
        return Entry(displayName, context.applicationContext, null, File(dir, displayName))
    }
}
