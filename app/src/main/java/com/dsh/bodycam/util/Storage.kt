package com.dsh.bodycam.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件落在应用私有外部目录：
 *   Android/data/com.dsh.bodycam/files/BodyCam/videos|photos
 * 好处是不需要存储权限，adb pull 也方便。
 * 需要进系统相册时，把目录换成 MediaStore 即可（见 README）。
 */
object Storage {

    private fun dir(context: Context, sub: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val target = File(base, "BodyCam/$sub")
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun newVideoFile(context: Context): File =
        File(dir(context, "videos"), "VID_${stamp()}.mp4")

    fun videoDir(context: Context): File = dir(context, "videos")

    fun savePhoto(context: Context, bytes: ByteArray): File {
        val file = File(dir(context, "photos"), "IMG_${stamp()}.jpg")
        file.writeBytes(bytes)
        return file
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
