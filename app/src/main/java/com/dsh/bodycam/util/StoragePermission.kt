package com.dsh.bodycam.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 存储权限判断与引导。
 *
 * 本工程要往公共存储 `/sdcard/BodyCam/...` 直接写文件（见 [Storage]），
 * 这需要「所有文件访问」权限才能在 Android 11+ 生效：
 *
 *   API 30+   MANAGE_EXTERNAL_STORAGE（系统设置里单独开关，不能弹窗授予）
 *   API 26-29 WRITE_EXTERNAL_STORAGE（运行时权限；Android 10 靠清单里的
 *             requestLegacyExternalStorage 恢复旧行为）
 *
 * 统一从这里判断，避免各页面各写一套。
 */
object StoragePermission {

    /** 当前是否已具备写公共存储的权限 */
    fun isGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 跳到系统里开启存储权限的界面：
     *   API 30+  本应用的「所有文件访问权限」页
     *   API 26-29 本应用详情页（用户在权限里打开存储）
     * 调用后需在 onResume 再查一次 [isGranted]，因为这是跨页返回。
     */
    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 个别 ROM 没有这个跳转页，退回所有应用列表
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    /** 需要运行时申请 WRITE_EXTERNAL_STORAGE 的档位（API 26-29） */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
}