package com.dsh.bodycam.core

import android.os.SystemClock

/**
 * 音量键路由。
 *
 * 两条来源：
 * 1. Activity.dispatchKeyEvent —— 前台时直接拿到；
 * 2. GlobalKeyService（无障碍服务）—— 息屏/后台时也能拿到。
 *
 * [armed] 用来避免「装了这个 App 就把系统音量键永久吃掉」：
 * 只有本应用可见或正在录像时才拦截。
 */
object KeyRouter {

    interface Listener {
        fun onShutterKey()
        fun onRecordKey()
    }

    @Volatile
    var listener: Listener? = null

    /** 是否处于「按键归本应用」的状态 */
    @Volatile
    var armed: Boolean = false

    @Volatile
    var recording: Boolean = false

    private const val DEBOUNCE_MS = 300L
    private var lastShutter = 0L
    private var lastRecord = 0L

    fun handleVolumeUp(): Boolean {
        if (!armed) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastShutter < DEBOUNCE_MS) return true
        lastShutter = now
        listener?.onShutterKey()
        return true
    }

    fun handleVolumeDown(): Boolean {
        if (!armed) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastRecord < DEBOUNCE_MS) return true
        lastRecord = now
        listener?.onRecordKey()
        return true
    }
}
