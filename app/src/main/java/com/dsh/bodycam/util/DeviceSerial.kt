package com.dsh.bodycam.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 取设备 SN 号，用于水印左下角第一行。
 *
 * Android 从 8.0 起逐步收紧了 SN 的读取：
 *  - `Build.SERIAL` 在 API 26+ 恒返回 "UNKNOWN"；
 *  - `Build.getSerial()` 需要 READ_PHONE_STATE，且 API 29+ 只对系统应用返回真值。
 *
 * 所以按「能拿到真 SN 的优先级」逐级回退：
 *  1. 系统属性 `ro.serialno` / `ro.boot.serialno` / `persist.sys.serialno`
 *     —— 执法记录仪多为定制 ROM，这几个属性通常可读，拿到的就是出厂 SN；
 *  2. `Build.getSerial()` —— API 26~28 且已授权时有效；
 *  3. `ANDROID_ID` —— 始终可用，但它不是出厂 SN，只能当设备标识用。
 *
 * 结果缓存一次：SN 在设备生命周期内不变，而水印每 500ms 就要取一次。
 */
object DeviceSerial {

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val value = readProperty("ro.serialno")
            ?: readProperty("ro.boot.serialno")
            ?: readProperty("persist.sys.serialno")
            ?: hardwareSerial()
            ?: androidId(context)
        return value.also { cached = it }
    }

    /**
     * 反射读 `android.os.SystemProperties`。
     * targetSdk 33 下它属于非 SDK 接口，部分 ROM 会拒绝，所以每步都吞异常继续回退。
     */
    private fun readProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun hardwareSerial(): String? {
        val serial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        } catch (_: Throwable) {
            // 未授权或不许读：API 29+ 对普通应用必然走到这里
            return null
        }
        return serial?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
    }

    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "UNKNOWN"
}