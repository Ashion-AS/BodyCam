package com.dsh.bodycam.core

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 拍照 / 录像 / 录音的外接触发路由。
 *
 * 触发源有两种，配置都放在 [SettingsStore]（默认值见 [AppConfig]），**默认全空**：
 *   1. 物理按键 keycode —— 前台由 `Activity.dispatchKeyEvent` 送进来，
 *      息屏 / 后台由 [com.dsh.bodycam.key.GlobalKeyService] 送进来；
 *   2. 广播 action —— 由 [com.dsh.bodycam.key.TriggerReceiver] 转进来。
 *
 * 两套配置可同时生效；同一 keycode / action 配给多个动作时按
 * 拍照 → 录像 → 录音 的顺序取第一个。
 *
 * ## 监听器分两类
 *
 * - **主监听**（[claimMainListener]，主界面）：接收全部三种触发。
 *   录像中即使退到后台也保留，否则息屏后无法用触发键停止录像。
 * - **录音页监听**（[claimAudioListener]，独立录音页）：只接收 [Target.AUDIO]。
 *
 * 分开持有是为了避免「录像中打开录音页 → 主界面监听被覆盖 → 停不了录像」。
 * 录音触发优先给录音页（它在前台时由它切换录音开始/停止），没有录音页才给主界面
 * （跳过去开录）。
 *
 * [armed] 用来避免「装了这个 App 就永久吃掉某个键」：只有本应用可见或正在
 * 录像时才消费按键 / 广播，否则一律放行（音量键照常调音量）。
 */
object TriggerRouter {

    /** 一个触发动作。拍照 / 录像 / 录音共用同一套配置与分发通道 */
    enum class Target { SHUTTER, RECORD, AUDIO }

    interface Listener {
        fun onShutterTrigger()
        fun onRecordTrigger()
        fun onAudioTrigger()
    }

    /** 主界面：接收全部触发，录像中退到后台也保留 */
    @Volatile
    private var mainListener: Listener? = null

    /** 独立录音页：只关心录音触发 */
    @Volatile
    private var audioListener: Listener? = null

    /** 录像是否进行中（决定后台是否仍消费触发） */
    @Volatile
    var recording: Boolean = false

    /**
     * 当前正在写入的分段文件名（如 `VID_20261120_143022_part03.mp4`）；未录制时为空。
     *
     * 由 [com.dsh.bodycam.record.SegmentRecorder] 在每段切换时更新。
     * 播放链路必须拒绝对这个文件做任何后处理：MuxerFix 会重写/截断整个文件，
     * 而 muxer 全程持有旧 FD，一改就把这一段永久写坏（不可恢复）。
     */
    @Volatile
    var recordingSegmentName: String = ""

    /** 该文件名是否为录制器当前正在写入的分段 */
    fun isRecordingSegment(fileName: String): Boolean =
        fileName.isNotEmpty() && fileName == recordingSegmentName

    /**
     * 是否处于「按键归本应用」的状态。
     *
     * 用计算属性而不是手动赋值，避免多个页面来回切换时状态不同步
     * ——例如录音页销毁时清掉状态，会把刚恢复的主界面也误关掉。
     */
    val armed: Boolean
        get() = mainListener != null || audioListener != null || recording

    private const val DEBOUNCE_MS = 300L
    private var lastShutter = 0L
    private var lastRecord = 0L
    private var lastAudio = 0L

    // ------------------------------------------------------------ 监听器认领

    fun claimMainListener(l: Listener) {
        mainListener = l
    }

    /** 只清自己认领的槽位，避免把后来者的认领清掉 */
    fun releaseMainListener(l: Listener) {
        if (mainListener === l) mainListener = null
    }

    fun claimAudioListener(l: Listener) {
        audioListener = l
    }

    fun releaseAudioListener(l: Listener) {
        if (audioListener === l) audioListener = null
    }

    // ------------------------------------------------------------ 事件入口

    /**
     * 处理一次 KeyEvent。
     * @return true = 该键属于配置的触发键并已被消费（不再交给系统）
     */
    fun handleKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!armed) return false
        val target = matchKey(context, event.keyCode) ?: return false
        // 只在下按那一刻触发；抬起与长按重复也要吃掉，避免漏给系统
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            fire(target)
        }
        return true
    }

    /**
     * 处理一条外部广播。
     * @return true = 命中配置的 action 并已触发
     */
    fun handleBroadcast(context: Context, action: String?): Boolean {
        if (action == null || !armed) return false
        val shutter = SettingsStore.triggerActionShutter(context)
        val record = SettingsStore.triggerActionRecord(context)
        val audio = SettingsStore.triggerActionAudio(context)
        val target = when {
            shutter.isNotEmpty() && action == shutter -> Target.SHUTTER
            record.isNotEmpty() && action == record -> Target.RECORD
            audio.isNotEmpty() && action == audio -> Target.AUDIO
            else -> return false
        }
        fire(target)
        return true
    }

    /** 页面上配置的所有触发广播 action（供动态注册 IntentFilter 用） */
    fun configuredActions(context: Context): List<String> = listOf(
        SettingsStore.triggerActionShutter(context),
        SettingsStore.triggerActionRecord(context),
        SettingsStore.triggerActionAudio(context)
    ).filter { it.isNotEmpty() }

    /**
     * 判断 keycode 命中了哪个已配置的触发键，不触发、不看 [armed]。
     * 给需要「自己吞键但按不同动作走不同逻辑」的页面用（如录音页）。
     */
    fun matchKey(context: Context, keyCode: Int): Target? {
        val shutter = SettingsStore.triggerKeycodeShutter(context)
        if (shutter != KeyEvent.KEYCODE_UNKNOWN && keyCode == shutter) return Target.SHUTTER
        val record = SettingsStore.triggerKeycodeRecord(context)
        if (record != KeyEvent.KEYCODE_UNKNOWN && keyCode == record) return Target.RECORD
        val audio = SettingsStore.triggerKeycodeAudio(context)
        if (audio != KeyEvent.KEYCODE_UNKNOWN && keyCode == audio) return Target.AUDIO
        return null
    }

    // ------------------------------------------------------------ 分发

    private fun fire(target: Target) {
        val now = SystemClock.uptimeMillis()
        when (target) {
            Target.SHUTTER -> {
                if (now - lastShutter < DEBOUNCE_MS) return
                lastShutter = now
                mainListener?.onShutterTrigger()
            }
            Target.RECORD -> {
                if (now - lastRecord < DEBOUNCE_MS) return
                lastRecord = now
                mainListener?.onRecordTrigger()
            }
            Target.AUDIO -> {
                if (now - lastAudio < DEBOUNCE_MS) return
                lastAudio = now
                // 录音页在前台时由它切换录音；否则交给主界面跳转开录
                (audioListener ?: mainListener)?.onAudioTrigger()
            }
        }
    }
}