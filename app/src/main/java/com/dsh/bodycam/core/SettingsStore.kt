package com.dsh.bodycam.core

import android.content.Context

/**
 * 设置持久化。目前只有「预录」：
 *   - 开关：开启后 App 一直在后台缓存最近 N 秒画面，按下录像键时把这
 *     段缓存一起落盘，成片开头就带上了「按下按键之前」的画面；
 *   - 时长：5/10/20/30 秒。
 *
 * 值变化时通过 [onChanged] 通知（设置页改完，主页面立刻生效，无需重启）。
 */
object SettingsStore {

    private const val PREFS = "bodycam_settings"
    private const val KEY_PRE_RECORD_ENABLED = "pre_record_enabled"
    private const val KEY_PRE_RECORD_SECONDS = "pre_record_seconds"

    /** 预录时长可选项 */
    val PRE_RECORD_OPTIONS = intArrayOf(5, 10, 20, 30)

    const val DEFAULT_PRE_RECORD_SECONDS = 10

    interface Listener {
        fun onSettingsChanged()
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 页面销毁时解除监听，避免旧 Activity 被持有、也避免重复回调 */
    fun clearListener(l: Listener) {
        if (listener === l) listener = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPreRecordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRE_RECORD_ENABLED, false)

    fun setPreRecordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRE_RECORD_ENABLED, enabled).apply()
        listener?.onSettingsChanged()
    }

    fun preRecordSeconds(context: Context): Int =
        prefs(context).getInt(KEY_PRE_RECORD_SECONDS, DEFAULT_PRE_RECORD_SECONDS)

    fun setPreRecordSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_PRE_RECORD_SECONDS, seconds).apply()
        listener?.onSettingsChanged()
    }
}
