package com.dsh.bodycam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.core.TriggerRouter
import com.dsh.bodycam.record.AudioRecorder
import com.dsh.bodycam.ui.ToastBar
import com.dsh.bodycam.ui.WaveformView
import com.dsh.bodycam.util.Storage
import com.dsh.bodycam.util.StoragePermission
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 独立录音页：只采声音，不开相机。
 *
 * 上半部是实时波形（[WaveformView]），下半部是一个开始/停止二合一的主控件。
 * 录音链路是 [AudioRecorder]（AudioRecord → AAC → .m4a），与录像里的音轨同参数。
 *
 * 快捷入口：外部用 [launch] 拉起本页并直接开录，或配置「录音触发键 / 录音广播」
 * 由 [TriggerRouter] 自动转入本页开录。页面在前台时认领「录音页监听」
 * （[TriggerRouter.claimAudioListener]），此时按录音键即切换录音开始/停止；
 * 离开页面时归还。主界面用另一个独立槽位，两者互不覆盖（录像中也能开录音页）。
 */
class AudioRecordActivity : Activity(), TriggerRouter.Listener {

    companion object {
        private const val REQ_AUDIO_PERMISSION = 2001
        private const val REQ_STORAGE_PERMISSION = 2002
        private const val TICK_MS = 250L

        /**
         * 拉起独立录音页。
         * @param startImmediately true = 进入后自动开始录音（给「按键快捷开录」用）
         */
        fun launch(context: Context, startImmediately: Boolean = false) {
            val intent = Intent(context, AudioRecordActivity::class.java)
            if (startImmediately) intent.action = AppConfig.ACTION_START_AUDIO_RECORD
            context.startActivity(intent)
        }
    }

    private lateinit var waveform: WaveformView

    /** 读数行：未录音显示就绪提示，录音中显示计时，两者共用一个控件 */
    private lateinit var readoutView: TextView
    private lateinit var btnToggle: TextView

    private var recorder: AudioRecorder? = null
    private var recording = false
    private var recordStartElapsed = 0L
    private var saving = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val stopExecutor = Executors.newSingleThreadExecutor()

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) showReadout()
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_record)

        waveform = findViewById(R.id.waveform)
        readoutView = findViewById(R.id.readoutView)
        btnToggle = findViewById(R.id.btnToggle)

        recorder = AudioRecorder(this).apply {
            levelListener = { level -> onLevel(level) }
            micFailureListener = { onMicFailure() }
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnToggle.setOnClickListener { toggleRecording() }

        // 录音页用独立的监听槽位：不会覆盖主界面的监听，
        // 录像中打开录音页也不会让主界面丢掉「停止录像」的触发
        TriggerRouter.claimAudioListener(this)

        if (AppConfig.KEEP_SCREEN_ON) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        updateUi()
        requestAudioPermissionIfNeeded()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        ToastBar.release(this)
        recorder?.release() // 未保存的录音直接收尾成文件，不会丢
        recorder = null
        stopExecutor.shutdown()
        TriggerRouter.releaseAudioListener(this)
        super.onDestroy()
    }

    /** 外接「录音」触发：切换录音（在录音就停止，否则开始） */
    override fun onAudioTrigger() {
        // 走按钮同一条路径，避免绕过权限/存储检查
        toggleRecording()
    }

    // 录音页没有拍照与录像功能，这两个触发忽略
    override fun onShutterTrigger() = Unit

    override fun onRecordTrigger() = Unit

    // ------------------------------------------------------------ 权限

    private fun requestAudioPermissionIfNeeded() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION)
            return
        }
        // 录音要写公共存储：API 26-29 补申请运行时权限，30+ 引导「所有文件访问」
        if (StoragePermission.needsRuntimePermission() &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE_PERMISSION
            )
            return
        }
        ensureStorageAccess()
        maybeAutoStart()
    }

    /** API 30+ 未开启「所有文件访问」时提示并跳系统设置 */
    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !StoragePermission.isGranted(this)
        ) {
            toast(getString(R.string.need_storage_access))
            StoragePermission.openSettings(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_AUDIO_PERMISSION -> {
                if (granted(Manifest.permission.RECORD_AUDIO)) requestAudioPermissionIfNeeded()
                else toast(getString(R.string.audio_need_permission))
            }
            REQ_STORAGE_PERMISSION -> {
                if (granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    ensureStorageAccess()
                    maybeAutoStart()
                } else {
                    toast(getString(R.string.need_storage_access))
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** 快捷入口：带 action 进来就立刻开录 */
    private fun maybeAutoStart() {
        if (intent?.action == AppConfig.ACTION_START_AUDIO_RECORD && !recording) {
            startRecording()
        }
    }

    // ------------------------------------------------------------ 按键

    /**
     * 录音页的按键：
     *   - 录音中命中「录音触发键」或「录像触发键」→ 停止录音；
     *   - 其余一律放行（未配置时一个键都不拦）。
     *
     * 触发键来自 `SettingsStore`（默认未绑定），具体匹配交给
     * [TriggerRouter.matchKey]，保证主界面与录音页用的是同一套配置。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = TriggerRouter.matchKey(this, event.keyCode)
        if (recording && (target == TriggerRouter.Target.AUDIO || target == TriggerRouter.Target.RECORD)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) stopRecording()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ 录音

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recording || saving) return
        val rec = recorder ?: return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestAudioPermissionIfNeeded()
            return
        }
        if (!StoragePermission.isGranted(this)) {
            requestAudioPermissionIfNeeded()
            return
        }
        if (!rec.start()) {
            toast(getString(R.string.audio_start_failed))
            return
        }
        recording = true
        waveform.reset()
        recordStartElapsed = SystemClock.elapsedRealtime()
        updateUi()
        startTicker()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        saving = true
        updateUi()
        uiHandler.removeCallbacks(ticker)

        val rec = recorder ?: return
        stopExecutor.execute {
            val entries = runCatching { rec.stop() }.getOrDefault(emptyList())
            val micPeak = rec.lastMicPeak
            uiHandler.post { onSaved(entries, micPeak) }
        }
    }

    /**
     * 音频线程判定麦克风已失效（连续读不到数据）后的回调。
     * 走与手动停止相同的收尾路径：已录到的分段照常提交，不会丢文件。
     */
    private fun onMicFailure() {
        uiHandler.post {
            if (!recording) return@post // 用户已自行停止，交给那条路径收尾
            toast(getString(R.string.audio_mic_lost))
            stopRecording()
        }
    }

    private fun onSaved(entries: List<Storage.Entry>, micPeak: Int) {
        saving = false
        if (isDestroyed) return
        waveform.reset()
        updateUi()
        toast(
            if (entries.isEmpty()) getString(R.string.audio_failed)
            else if (entries.size == 1) getString(R.string.audio_saved, entries.last().displayName)
            else getString(R.string.audio_saved_segments, entries.size, entries.last().displayName)
        )
        if (entries.isNotEmpty() && micPeak < AppConfig.AUDIO_SILENT_THRESHOLD) {
            toast(getString(R.string.audio_silent_simple))
        }
    }

    /** 音频线程回调电平：切回主线程再推给波形 */
    private fun onLevel(level: Float) {
        uiHandler.post { if (recording) waveform.pushLevel(level) }
    }

    private fun startTicker() {
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    private fun elapsedSeconds(): Long =
        (SystemClock.elapsedRealtime() - recordStartElapsed) / 1000

    /**
     * 刷新读数行。
     *
     * 一个控件承担两种角色：录音中显示红色等宽计时，其余时候显示就绪提示。
     * 两种文字样式不同（字号 18sp / 14sp），用 setTextAppearance 切，不硬编码颜色字号。
     */
    private fun showReadout() {
        val running = recording || saving
        readoutView.setTextAppearance(
            if (running) R.style.BodyCamInstrumentLarge else R.style.BodyCamLabel
        )
        if (running) {
            val total = elapsedSeconds()
            readoutView.text = String.format(
                Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60
            )
        } else {
            readoutView.text = getString(R.string.audio_status_ready)
        }
    }

    private fun updateUi() {
        btnToggle.text = getString(
            if (recording) R.string.btn_audio_stop else R.string.btn_audio_start
        )
        btnToggle.setBackgroundResource(
            if (recording) R.drawable.bg_btn_record_on else R.drawable.bg_btn_record
        )
        // 未录音：红实心 + 白字；录音中：深底 + 红字（与主界面的录像键同一套双态）
        btnToggle.setTextColor(getColor(if (recording) R.color.rec_signal else R.color.ink))
        showReadout()
    }

    private fun toast(message: String) {
        ToastBar.show(this, message)
    }
}