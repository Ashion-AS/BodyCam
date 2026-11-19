package com.dsh.bodycam.core

import android.content.Context
import android.view.KeyEvent
import com.dsh.bodycam.core.AppConfig

/**
 * 设置持久化。
 *
 * 预录：
 *   - 开关：开启后 App 一直在后台缓存最近 N 秒画面，按下录像键时把这
 *     段缓存一起落盘，成片开头就带上了「按下按键之前」的画面；
 *   - 时长：5/10/20/30 秒。
 *
 * 外接触发（拍照 / 录像）：keycode 与广播 action 两种方式，见 [TriggerRouter]。
 * 默认值来自 [AppConfig]，这里保存的是运行期覆盖；未设置过时读回默认值，
 * 因此「默认全空 = 不占用任何键、不监听任何广播」。
 *
 * 值变化时通过 [onChanged] 通知（设置页改完，主页面立刻生效，无需重启）。
 */
object SettingsStore {

    private const val PREFS = "bodycam_settings"
    private const val KEY_PRE_RECORD_ENABLED = "pre_record_enabled"
    private const val KEY_PRE_RECORD_SECONDS = "pre_record_seconds"
    private const val KEY_TRIGGER_KEYCODE_SHUTTER = "trigger_keycode_shutter"
    private const val KEY_TRIGGER_KEYCODE_RECORD = "trigger_keycode_record"
    private const val KEY_TRIGGER_KEYCODE_AUDIO = "trigger_keycode_audio"
    private const val KEY_TRIGGER_ACTION_SHUTTER = "trigger_action_shutter"
    private const val KEY_TRIGGER_ACTION_RECORD = "trigger_action_record"
    private const val KEY_TRIGGER_ACTION_AUDIO = "trigger_action_audio"

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

    // ------------------------------------------------------------ 外接触发

    /** 拍照键 keycode；[KeyEvent.KEYCODE_UNKNOWN] 表示未绑定 */
    fun triggerKeycodeShutter(context: Context): Int =
        prefs(context).getInt(KEY_TRIGGER_KEYCODE_SHUTTER, AppConfig.TRIGGER_KEYCODE_SHUTTER)

    fun setTriggerKeycodeShutter(context: Context, keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            prefs(context).edit().remove(KEY_TRIGGER_KEYCODE_SHUTTER).apply()
        } else {
            prefs(context).edit().putInt(KEY_TRIGGER_KEYCODE_SHUTTER, keyCode).apply()
        }
        listener?.onSettingsChanged()
    }

    /** 录像键 keycode；[KeyEvent.KEYCODE_UNKNOWN] 表示未绑定 */
    fun triggerKeycodeRecord(context: Context): Int =
        prefs(context).getInt(KEY_TRIGGER_KEYCODE_RECORD, AppConfig.TRIGGER_KEYCODE_RECORD)

    fun setTriggerKeycodeRecord(context: Context, keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            prefs(context).edit().remove(KEY_TRIGGER_KEYCODE_RECORD).apply()
        } else {
            prefs(context).edit().putInt(KEY_TRIGGER_KEYCODE_RECORD, keyCode).apply()
        }
        listener?.onSettingsChanged()
    }

    /** 录音键 keycode；[KeyEvent.KEYCODE_UNKNOWN] 表示未绑定 */
    fun triggerKeycodeAudio(context: Context): Int =
        prefs(context).getInt(KEY_TRIGGER_KEYCODE_AUDIO, AppConfig.TRIGGER_KEYCODE_AUDIO)

    fun setTriggerKeycodeAudio(context: Context, keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            prefs(context).edit().remove(KEY_TRIGGER_KEYCODE_AUDIO).apply()
        } else {
            prefs(context).edit().putInt(KEY_TRIGGER_KEYCODE_AUDIO, keyCode).apply()
        }
        listener?.onSettingsChanged()
    }

    /** 拍照广播 action；空串表示不监听 */
    fun triggerActionShutter(context: Context): String =
        prefs(context).getString(KEY_TRIGGER_ACTION_SHUTTER, AppConfig.TRIGGER_ACTION_SHUTTER)
            ?: AppConfig.TRIGGER_ACTION_SHUTTER

    fun setTriggerActionShutter(context: Context, action: String) {
        if (action.isEmpty()) {
            prefs(context).edit().remove(KEY_TRIGGER_ACTION_SHUTTER).apply()
        } else {
            prefs(context).edit().putString(KEY_TRIGGER_ACTION_SHUTTER, action).apply()
        }
        listener?.onSettingsChanged()
    }

    /** 录像广播 action；空串表示不监听 */
    fun triggerActionRecord(context: Context): String =
        prefs(context).getString(KEY_TRIGGER_ACTION_RECORD, AppConfig.TRIGGER_ACTION_RECORD)
            ?: AppConfig.TRIGGER_ACTION_RECORD

    fun setTriggerActionRecord(context: Context, action: String) {
        if (action.isEmpty()) {
            prefs(context).edit().remove(KEY_TRIGGER_ACTION_RECORD).apply()
        } else {
            prefs(context).edit().putString(KEY_TRIGGER_ACTION_RECORD, action).apply()
        }
        listener?.onSettingsChanged()
    }

    /** 录音广播 action；空串表示不监听 */
    fun triggerActionAudio(context: Context): String =
        prefs(context).getString(KEY_TRIGGER_ACTION_AUDIO, AppConfig.TRIGGER_ACTION_AUDIO)
            ?: AppConfig.TRIGGER_ACTION_AUDIO

    fun setTriggerActionAudio(context: Context, action: String) {
        if (action.isEmpty()) {
            prefs(context).edit().remove(KEY_TRIGGER_ACTION_AUDIO).apply()
        } else {
            prefs(context).edit().putString(KEY_TRIGGER_ACTION_AUDIO, action).apply()
        }
        listener?.onSettingsChanged()
    }
}
