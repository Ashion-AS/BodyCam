package com.dsh.bodycam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.record.AudioRecorder
import com.dsh.bodycam.ui.WaveformView
import com.dsh.bodycam.util.Storage
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 独立录音页：只采声音，不开相机。
 *
 * 上半部是实时波形（[WaveformView]），下半部是一个开始/停止二合一的主控件。
 * 录音链路是 [AudioRecorder]（AudioRecord → AAC → .m4a），与录像里的音轨同参数。
 *
 * 快捷入口（预留）：外部用 [launch] 拉起本页并直接开录。
 * 目前还没有绑定具体物理按键；接入时只需在拿到按键回调的地方调用
 * `AudioRecordActivity.launch(context, startImmediately = true)`，
 * 页面进入后等录音权限就绪即自动开始。前台时也可用音量键切换录音/停止。
 */
class AudioRecordActivity : Activity() {

    companion object {
        private const val REQ_AUDIO_PERMISSION = 2001
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
    private lateinit var timerView: TextView
    private lateinit var statusView: TextView
    private lateinit var btnToggle: TextView

    private var recorder: AudioRecorder? = null
    private var recording = false
    private var recordStartElapsed = 0L
    private var saving = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val stopExecutor = Executors.newSingleThreadExecutor()

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) updateTimer()
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_record)

        waveform = findViewById(R.id.waveform)
        timerView = findViewById(R.id.timerView)
        statusView = findViewById(R.id.statusView)
        btnToggle = findViewById(R.id.btnToggle)

        recorder = AudioRecorder(this).apply {
            levelListener = { level -> onLevel(level) }
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        btnToggle.setOnClickListener { toggleRecording() }

        if (AppConfig.KEEP_SCREEN_ON) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        updateUi()
        requestAudioPermissionIfNeeded()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        recorder?.release() // 未保存的录音直接收尾成文件，不会丢
        recorder = null
        stopExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 权限

    private fun requestAudioPermissionIfNeeded() {
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            maybeAutoStart()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO_PERMISSION) return
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            maybeAutoStart()
        } else {
            toast(getString(R.string.audio_need_permission))
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
     * 前台时音量下键在录音中可停止（只在录音中拦截，其余放行给系统调音量）。
     * 「按键→跳转到本页并直接开录」的快捷入口见 [launch] 与 [AppConfig.ACTION_START_AUDIO_RECORD]。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 只在录音中吃音量下键（用来停止），未录音时放行，别抢系统调音量
        if (recording && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
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

    private fun updateTimer() {
        if (!recording) {
            timerView.text = getString(R.string.timer_zero)
            return
        }
        val total = (SystemClock.elapsedRealtime() - recordStartElapsed) / 1000
        timerView.text = String.format(
            Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60
        )
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
        statusView.text = getString(
            if (recording) R.string.audio_status_recording else R.string.audio_status_ready
        )
        timerView.visibility = if (recording || saving) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}