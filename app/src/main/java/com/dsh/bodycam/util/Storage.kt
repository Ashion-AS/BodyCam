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