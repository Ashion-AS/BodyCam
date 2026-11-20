package com.dsh.bodycam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.dsh.bodycam.camera.CameraController
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.core.TriggerRouter
import com.dsh.bodycam.gl.GlPipeline
import com.dsh.bodycam.key.TriggerReceiver
import com.dsh.bodycam.record.PreRecordBuffer
import com.dsh.bodycam.record.SegmentRecorder
import com.dsh.bodycam.service.KeepAliveService
import com.dsh.bodycam.util.Storage
import com.dsh.bodycam.util.StoragePermission
import com.dsh.bodycam.util.rotationToDegrees
import com.dsh.bodycam.watermark.PhotoWatermarker
import com.dsh.bodycam.watermark.Telemetry
import com.dsh.bodycam.watermark.WatermarkData
import com.dsh.bodycam.watermark.WatermarkPainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主界面：
 *   拍照按钮 → 拍一张 JPG（录像中也能拍）
 *   录像按钮 → 开始 / 停止录像
 *   外接触发 → 可配置的物理按键 keycode 或广播 action（见 [TriggerRouter]），
 *              默认一个都不绑定
 *
 * 相机、OpenGL、MediaRecorder 都挂在 Activity 上；息屏时靠
 * KeepAliveService（camera|microphone 前台服务 + WakeLock）保活。
 */
class MainActivity : Activity(), TriggerRouter.Listener {

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val TICK_MS = 500L
        private const val PREFS = "bodycam"
        private const val KEY_ROTATION_OFFSET = "rotation_offset"

        /** 未手动校准过的默认偏移：本机相机 SurfaceTexture 需要额外转 270° 才正向 */
        private const val DEFAULT_ROTATION_OFFSET = 270
    }

    private lateinit var previewView: SurfaceView
    private lateinit var flashView: View
    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var recDot: View
    private lateinit var btnShutter: View
    private lateinit var btnRecord: View
    private lateinit var iconRecord: android.widget.ImageView
    private lateinit var labelRecord: TextView
    private lateinit var hintView: TextView
    private lateinit var debugView: TextView
    private lateinit var preRecordDot: View
    private lateinit var btnSettings: View
    private lateinit var btnAudioRecord: View
    private lateinit var btnFiles: View

    private var pipeline: GlPipeline? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var camera: CameraController? = null
    private var recorder: SegmentRecorder? = null
    private var preRecordBuffer: PreRecordBuffer? = null
    private var painter: WatermarkPainter? = null
    private var telemetry: Telemetry? = null

    // 相机流尺寸（传感器方向，一般 1920x1080 横）
    private var streamW = 1920
    private var streamH = 1080
    private var sensorOrientation = 90

    // 输出尺寸（把画面转正后的画幅：竖屏 1080x1920，横屏 1920x1080）
    private var videoW = 1080
    private var videoH = 1920
    private var rotationDeg = 90

    /** 自动算出的「传感器→屏幕」角度，不含校准偏移；JPEG_ORIENTATION 用的也是它 */
    private var autoRotationDeg = 90

    /**
     * 方向校准偏移：在自动算出的角度上再加这个值。
     * 不同机型相机 SurfaceTexture 的变换矩阵不一样（有的还带镜像），
     * 自动值可能差 90°，点一下调试行就能循环调整并持久化。
     * 默认取 [DEFAULT_ROTATION_OFFSET]，本机开箱即正向，无需手动点。
     */
    private var rotationOffset = DEFAULT_ROTATION_OFFSET

    private var recording = false
    private var recordStartElapsed = 0L
    private var resumed = false

    /** 监听设置变化，销毁时解除，避免旧 Activity 被持有 */
    private var settingsListener: SettingsStore.Listener? = null

    /** 外接按键广播接收器；action 是运行期配置，所以动态注册 */
    private val triggerReceiver = TriggerReceiver()
    private var receiverRegistered = false

    /** 停止录像要等编码器把尾帧写完，放到后台线程做，避免卡 UI */
    @Volatile
    private var savingSegment = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private val stopExecutor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            // 一直走，不只在录像时走：否则空闲时预览上的时间会停在进入应用的时刻
            if (resumed) {
                updateWatermark()
                updateTimer()
            }
            uiHandler.postDelayed(this, TICK_MS)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            pipeline?.setPreviewWindow(holder.surface, previewView.width, previewView.height)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            pipeline?.updatePreviewSize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            pipeline?.setPreviewWindow(null, 0, 0)
        }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        TriggerRouter.claimMainListener(this)

        telemetry = Telemetry(this).also { it.start() }

        previewView.holder.addCallback(surfaceCallback)
        btnShutter.setOnClickListener { capturePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnAudioRecord.setOnClickListener {
            AudioRecordActivity.launch(this)
        }
        btnFiles.setOnClickListener {
            FilesActivity.launch(this)
        }

        // 设置变化（预录开关/时长、触发键/广播）→ 刷新缓存与黄点、重挂广播
        settingsListener = object : SettingsStore.Listener {
            override fun onSettingsChanged() {
                runOnUiThread {
                    applyPreRecordSetting()
                    refreshTriggerReceiver()
                    updateDebugInfo()
                }
            }
        }
        SettingsStore.setListener(settingsListener)
        refreshTriggerReceiver()

        // 方向校准：点调试行每次 +90°，可选 0/90/180/270，写入 SharedPreferences
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        rotationOffset = prefs.getInt(KEY_ROTATION_OFFSET, DEFAULT_ROTATION_OFFSET)
        debugView.setOnClickListener {
            rotationOffset = (rotationOffset + 90) % 360
            prefs.edit().putInt(KEY_ROTATION_OFFSET, rotationOffset).apply()
            applyOrientation()
            updateWatermark()
            toast(getString(R.string.rotation_offset, rotationOffset, rotationDeg, videoW, videoH))
        }

        if (AppConfig.KEEP_SCREEN_ON) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        handleIntent(intent)
        ensurePermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 录制过程中锁定了方向；没录的时候跟着设备转，画面与画幅一起更新
        if (!recording) applyOrientation()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 从设置页 / 录音页返回时重新认领主监听
        TriggerRouter.claimMainListener(this)

        val p = pipeline
        if (p != null) {
            p.setPreviewVisible(true)
            // 切后台再回来时，SurfaceView 的 Surface 很可能被系统销毁重建过，
            // 而 surfaceCreated 不一定再回调到我们这里，所以这里无条件重挂一次
            //（幂等：内部会先销毁旧的 EGL 表面再建新的）。
            val holder = previewView.holder
            if (holder.surface.isValid) {
                p.setPreviewWindow(holder.surface, previewView.width, previewView.height)
            }
            updateWatermark()
        }

        // 录像中不能动会话；没录像时回前台直接重建预览会话，
        // 因为后台期间相机可能被系统掐断，对旧会话重发请求不会报错但也不出帧。
        if (recording) {
            camera?.setStreaming(true)
        } else {
            camera?.restartPreview()
        }
        // 后台期间相机可能被系统断开，回前台时重新打开
        camera?.ensureOpen()
        if (pipeline == null && hasCorePermission()) setup()
        // 从设置页回来：按最新设置拉起/停止预录缓存并刷新黄点
        if (!recording) applyPreRecordSetting()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // 录像中继续保持监听（armed 由「有监听 或 正在录像」计算得出），
        // 息屏后仍可用触发键停止；未录像且退到后台时交回，避免占用触发
        if (!recording) TriggerRouter.releaseMainListener(this)
        pipeline?.setPreviewVisible(false)
        if (!recording) camera?.setStreaming(false)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        settingsListener?.let { SettingsStore.clearListener(it) }
        settingsListener = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(triggerReceiver) }
            receiverRegistered = false
        }
        if (recording) stopRecording(wait = true)
        TriggerRouter.releaseMainListener(this)
        TriggerRouter.recording = false

        camera?.close()
        camera = null
        stopExecutor.shutdown()
        if (!savingSegment) recorder?.release()
        recorder = null
        preRecordBuffer?.release()
        preRecordBuffer = null
        pipeline?.release()
        pipeline = null
        surfaceTexture?.release()
        surfaceTexture = null
        cameraSurface?.release()
        cameraSurface = null
        telemetry?.stop()
        telemetry = null
        photoExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.preview)
        flashView = findViewById(R.id.flashView)
        statusView = findViewById(R.id.statusView)
        timerView = findViewById(R.id.timerView)
        recDot = findViewById(R.id.recDot)
        btnShutter = findViewById(R.id.btnShutter)
        btnRecord = findViewById(R.id.btnRecord)
        iconRecord = findViewById(R.id.iconRecord)
        labelRecord = findViewById(R.id.labelRecord)
        hintView = findViewById(R.id.hintView)
        debugView = findViewById(R.id.debugView)
        preRecordDot = findViewById(R.id.preRecordDot)
        btnSettings = findViewById(R.id.btnSettings)
        btnAudioRecord = findViewById(R.id.btnAudioRecord)
        btnFiles = findViewById(R.id.btnFiles)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == AppConfig.ACTION_STOP_RECORD && recording) {
            stopRecording()
        }
    }

    // ------------------------------------------------------------ 初始化

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // API 26-29 直接写公共存储要这个运行时权限；30+ 改用「所有文件访问」，
        // 清单里已用 maxSdkVersion=29 卡住，那条走 StoragePermission 引导
        if (StoragePermission.needsRuntimePermission() &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isEmpty()) {
            setup()
            ensureStorageAccess()
        } else {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    /**
     * API 30+ 写公共存储需要「所有文件访问」权限，不能弹窗授予，只能引导去系统设置。
     * 未开启时提示一次（不强行拦在用相机之前，用户可先看画面）；拍照/录像时会再兜底检查。
     */
    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !StoragePermission.isGranted(this)
        ) {
            toast(getString(R.string.need_storage_access))
            StoragePermission.openSettings(this)
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasCorePermission(): Boolean =
        granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasCorePermission()) {
            setup()
            ensureStorageAccess()
        } else {
            toast(getString(R.string.need_permission))
        }
    }

    private fun setup() {
        if (pipeline != null) return
        val spec = CameraController.probe(this)
        if (spec == null) {
            toast(getString(R.string.no_camera))
            return
        }
        // 相机流永远是传感器方向的尺寸；输出尺寸再按设备姿态交换
        streamW = spec.videoSize.width
        streamH = spec.videoSize.height
        sensorOrientation = spec.sensorOrientation
        applyOrientation()

        val p = GlPipeline(videoW, videoH)
        if (!p.available) {
            toast(getString(R.string.gl_failed))
            return
        }
        // 注意：applyOrientation() 在上面就跑了，那时 pipeline 还是 null，
        // 旋转没投递出去；这里必须补一次，否则空闲预览是「未旋转」的压扁画面
        p.setRotation(rotationDeg.toFloat())
        pipeline = p

        val st = SurfaceTexture(p.cameraTextureId)
        // 这里必须是相机流尺寸，不能是输出尺寸
        st.setDefaultBufferSize(streamW, streamH)
        st.setOnFrameAvailableListener({ p.onFrameAvailable() }, uiHandler)
        surfaceTexture = st
        p.attachSurfaceTexture(st)

        val surface = Surface(st)
        cameraSurface = surface

        painter = WatermarkPainter()
        recorder = SegmentRecorder(this)
        preRecordBuffer = PreRecordBuffer(this)

        val controller = CameraController(
            context = this,
            spec = spec,
            targetSurface = surface,
            rotationProvider = { currentRotation() },
            onPhoto = { bytes -> savePhoto(bytes) },
            onError = { message -> runOnUiThread { toast(message) } }
        )
        camera = controller
        controller.open()

        if (previewView.holder.surface.isValid) {
            p.setPreviewWindow(previewView.holder.surface, previewView.width, previewView.height)
        }

        statusView.text = getString(R.string.readout_fmt, videoW, videoH, AppConfig.VIDEO_FPS)
        updateDebugInfo()
        updateWatermark()
        applyPreRecordSetting()
        // 水印时钟常驻：空闲时也要每秒刷新
        startTicker()
    }

    /**
     * 根据设置启动/停止预录缓存，并更新黄点。
     * - 开启：预录音视频编码器立即启动，GL 持续往它出帧；
     * - 关闭/录像中：不启动（录像中 GL 的帧由正式录像编码器消费）。
     * 画幅变化（转屏）时缓存尺寸会失配，这里重启一次以保持一致。
     */
    private fun applyPreRecordSetting() {
        val p = pipeline
        val buf = preRecordBuffer
        if (p == null || buf == null) {
            preRecordDot.visibility = View.GONE
            return
        }
        val enabled = SettingsStore.isPreRecordEnabled(this)
        val sizeMismatch = buf.isRunning &&
                (buf.startedWidth != videoW || buf.startedHeight != videoH)
        if (enabled && !recording && (!buf.isRunning || sizeMismatch)) {
            if (buf.isRunning) {
                buf.release()
                p.setPreRecordSurface(null)
            }
            val surface = buf.start(
                videoW, videoH, AppConfig.VIDEO_FPS, AppConfig.VIDEO_BITRATE,
                SettingsStore.preRecordSeconds(this)
            )
            if (surface != null) p.setPreRecordSurface(surface)
        } else if ((!enabled || recording) && buf.isRunning) {
            buf.release()
            p.setPreRecordSurface(null)
        }
        updatePreRecordDot()
    }

    /** 黄点：只反映「设置里预录是否开启」，与当前是否正在录像无关 */
    private fun updatePreRecordDot() {
        preRecordDot.visibility =
            if (SettingsStore.isPreRecordEnabled(this)) View.VISIBLE else View.GONE
    }

    /**
     * 计算画幅与旋转角。
     *
     * 画幅跟随**屏幕方向**，不跟旋转角走：
     * 竖屏就是竖屏（1080×1920），横屏就是横屏（1920×1080）。
     * 之前画幅由旋转角决定，一旦校准到 0/180 就退回横屏画幅，
     * 竖屏下预览会变成中间一条横的画面，还会被拉伸。
     */
    private fun applyOrientation() {
        autoRotationDeg = computeRotation()
        rotationDeg = (autoRotationDeg + rotationOffset + 360) % 360

        val portraitScreen = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val shortSide = minOf(streamW, streamH)
        val longSide = maxOf(streamW, streamH)
        val newW = if (portraitScreen) shortSide else longSide
        val newH = if (portraitScreen) longSide else shortSide

        if (newW != videoW || newH != videoH) {
            videoW = newW
            videoH = newH
            pipeline?.setVideoSize(videoW, videoH)
        }
        pipeline?.setRotation(rotationDeg.toFloat())
        if (this::statusView.isInitialized) {
            statusView.text = getString(R.string.readout_fmt, videoW, videoH, AppConfig.VIDEO_FPS)
        }
        updateDebugInfo()
    }

    /** 把相机画面转正所需的角度：传感器方向 - 屏幕方向 */
    private fun computeRotation(): Int {
        if (AppConfig.ROTATION_OVERRIDE >= 0) {
            return AppConfig.ROTATION_OVERRIDE % 360
        }
        return (sensorOrientation - displayDegrees() + 360) % 360
    }

    private fun displayDegrees(): Int = rotationToDegrees(currentRotation())

    private fun updateDebugInfo() {
        if (!this::debugView.isInitialized) return
        if (!SettingsStore.isDebugOverlayVisible(this)) {
            debugView.visibility = View.GONE
            return
        }
        debugView.visibility = View.VISIBLE
        debugView.text = getString(
            R.string.debug_readout_fmt,
            sensorOrientation, displayDegrees(), rotationDeg, rotationOffset,
            streamW, streamH, videoW, videoH
        )
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay.rotation

    // ------------------------------------------------------------ 按键 / 外接触发

    /**
     * 只拦截「配置好的触发键」，未配置时一律放行（音量键照常调音量）。
     * 具体 keycode 与拦截条件由 [TriggerRouter] 判断。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TriggerRouter.handleKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onShutterTrigger() = capturePhoto()

    override fun onRecordTrigger() = toggleRecording()

    /** 外接「录音」触发：跳到独立录音页并直接开录 */
    override fun onAudioTrigger() = AudioRecordActivity.launch(this, startImmediately = true)

    /** 按 [SettingsStore] 里配置的 action 重新注册广播接收器；默认无 action 时不注册 */
    private fun refreshTriggerReceiver() {
        if (receiverRegistered) {
            unregisterReceiver(triggerReceiver)
            receiverRegistered = false
        }
        val actions = TriggerRouter.configuredActions(this)
        if (actions.isEmpty()) return
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        // 触发广播来自外部设备（DVR / 车机），必须允许外部发送
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(triggerReceiver, filter)
        }
        receiverRegistered = true
    }

    // ------------------------------------------------------------ 拍照

    private fun capturePhoto() {
        val cam = camera ?: return toast(getString(R.string.camera_not_ready))
        if (!StoragePermission.isGranted(this)) {
            toast(getString(R.string.need_storage_access))
            StoragePermission.openSettings(this)
            return
        }
        cam.takePhoto()
        flashScreen()
    }

    private fun savePhoto(bytes: ByteArray) {
        // 拍照时间要固定成「按下快门那一刻」，不能等后台线程排到才开始算
        val data = watermarkData(System.currentTimeMillis())
        // 用「自动角度」而不是校准后的角度：JPEG_ORIENTATION 写的就是这个值，
        // 照片该转多少度由它决定，跟预览/录像的校准偏移无关。
        val rotation = autoRotationDeg
        photoExecutor.execute {
            // 照片链路单独用一个 painter 实例：WatermarkPainter 内部复用 Bitmap，不是线程安全的
            val stamped = runCatching {
                PhotoWatermarker.watermark(bytes, WatermarkPainter(), data, rotation)
            }.getOrDefault(bytes)
            val name = "IMG_${Storage.stamp()}.jpg"
            val saved = runCatching { Storage.savePhoto(this, stamped, name) }.getOrNull()
            runOnUiThread {
                toast(
                    if (saved != null) getString(R.string.photo_saved, saved)
                    else getString(R.string.photo_failed)
                )
            }
        }
    }

    private fun flashScreen() {
        flashView.alpha = 0.6f
        flashView.animate().alpha(0f).setDuration(200).start()
    }

    // ------------------------------------------------------------ 录像

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recording) return
        if (savingSegment) return toast(getString(R.string.record_saving))
        if (!StoragePermission.isGranted(this)) {
            toast(getString(R.string.need_storage_access))
            StoragePermission.openSettings(this)
            return
        }
        val p = pipeline ?: return toast(getString(R.string.camera_not_ready))
        val rec = recorder ?: return toast(getString(R.string.camera_not_ready))
        val cam = camera ?: return toast(getString(R.string.camera_not_ready))

        // 若此前因息屏停掉了预览流，开录前必须先恢复，否则编码器拿不到帧
        cam.setStreaming(true)
        // 按当前姿态确定画幅（竖屏 1080x1920），并在录制期间锁死，避免中途改尺寸
        applyOrientation()
        // 全应用固定竖屏：录制期间无需再额外锁方向，这里只是确保保持竖屏
        applyOrientation()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 预录交接：预录开着且缓存正在跑，就把「编码器 + 最近 N 秒帧」一并
        // 交给录制器（成片 = 预录画面 + 实时画面，单个文件）；GL 不换 Surface。
        // 顺序关键：先同步摘掉 GL 的预录 Surface，渲染线程不再出帧，编码器
        // 才能安全排空并交接；否则编码器缓冲满会让 GL 渲染线程卡住。
        val handover = preRecordBuffer?.takeIf { it.isRunning }?.let { buf ->
            if (buf.startedWidth != videoW || buf.startedHeight != videoH) {
                buf.release()
                null
            } else {
                // 先同步摘掉 GL 的预录 Surface，渲染线程停手后编码器才能安全
                // 排空并交接（否则编码器缓冲满会把 GL 渲染线程卡住）。
                // 交接块本身已按 GOP 对齐（见 PreRecordBuffer.trimLocked），
                // 可独立干净解码，不需要在这里额外逼 IDR。
                p.setPreRecordSurface(null)
                buf.beginHandover(SettingsStore.preRecordSeconds(this))
            }
        }

        val surface = rec.start(
            videoW, videoH, AppConfig.VIDEO_FPS, AppConfig.VIDEO_BITRATE,
            0,  // 画面已经烘成正向，不需要再写 rotation 元数据
            AppConfig.SEGMENT_SECONDS,
            handover
        )
        if (surface == null) {
            // 交接出去的编码器归录制器管，失败时得由录制器释放；这里只需提示
            toast(getString(R.string.record_init_failed))
            return
        }

        p.setEncoderWindow(surface)
        // 编码器已在 rec.start() 里启动，这里开始出帧即可
        p.setRecording(true)
        recording = true
        TriggerRouter.recording = true
        recordStartElapsed = SystemClock.elapsedRealtime()

        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        startTicker()
        updateUi()
    }

    /**
     * @param wait true 时同步等待编码器收尾（Activity 销毁时用，保证文件写完）
     */
    private fun stopRecording(wait: Boolean = false) {
        if (!recording) return
        recording = false
        TriggerRouter.recording = false

        // 全应用固定竖屏，停止录制后保持竖屏即可
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 顺序很重要：先停 GL 出帧，再销毁编码器 EGL 表面，最后让编码器排空并写尾帧
        pipeline?.setRecording(false)
        pipeline?.clearEncoderWindow()

        stopService(Intent(this, KeepAliveService::class.java))
        // 不停 ticker：空闲时水印上的时间也要继续走
        updateUi()
        updateTimer()
        updateWatermark()

        val rec = recorder ?: return
        val preEnabled = SettingsStore.isPreRecordEnabled(this)
        if (wait) {
            runCatching { rec.stop() }
            if (!preEnabled) preRecordBuffer?.release()
            runOnUiThread { applyPreRecordSetting() }
            return
        }

        savingSegment = true
        stopExecutor.execute {
            val files = runCatching { rec.stop() }.getOrDefault(emptyList())
            val micPeak = rec.lastMicPeak
            savingSegment = false
            uiHandler.post { onSegmentSaved(files, micPeak) }
        }
    }

    /** 录制文件已收尾：提示结果并重新拉起预录缓存（在 UI 线程） */
    private fun onSegmentSaved(files: List<Storage.Entry>, micPeak: Int) {
        if (isDestroyed) return
        toast(
            if (files.isEmpty()) getString(R.string.video_failed)
            else getString(R.string.video_saved, files.size, files.last().displayName)
        )
        // 麦克风峰值极低基本等于没录到声音（静音/被占用/权限问题），提示一下
        if (files.isNotEmpty() && micPeak < AppConfig.AUDIO_SILENT_THRESHOLD) {
            toast(getString(R.string.audio_silent, micPeak))
        }
        applyPreRecordSetting()
    }

    private fun startTicker() {
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    // ------------------------------------------------------------ 水印 / UI

    private fun updateWatermark() {
        val p = pipeline ?: return
        val painter = painter ?: return
        p.setWatermark(
            painter.render(watermarkData(System.currentTimeMillis()), videoW),
            AppConfig.WATERMARK_LEFT,
            AppConfig.WATERMARK_BOTTOM,
            AppConfig.WATERMARK_WIDTH
        )
    }

    /** 视频与照片共用同一份水印内容；照片在后台线程调用，注意不要碰 UI */
    private fun watermarkData(now: Long): WatermarkData {
        val elapsed = if (recording) SystemClock.elapsedRealtime() - recordStartElapsed else 0L
        return WatermarkData(
            dateText = dateFormat.format(Date(now)),
            timeText = timeFormat.format(Date(now)),
            gpsText = telemetry?.gpsText ?: "",
            statusText = if (recording) "REC ${formatDuration(elapsed)}" else "STANDBY",
            deviceId = AppConfig.DEVICE_ID
        )
    }

    private fun updateTimer() {
        if (!recording) {
            timerView.text = getString(R.string.timer_zero)
            return
        }
        timerView.text = formatDuration(SystemClock.elapsedRealtime() - recordStartElapsed)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return String.format(
            Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60
        )
    }

    private fun updateUi() {
        labelRecord.text = getString(if (recording) R.string.btn_stop else R.string.btn_record)
        iconRecord.setBackgroundResource(
            if (recording) R.drawable.bg_dock_record_on else R.drawable.bg_dock_record
        )
        // 未录像：红实心 + 白字；录像中：反过来，深底 + 红字
        labelRecord.setTextColor(getColor(if (recording) R.color.rec_signal else R.color.ink))
        iconRecord.setColorFilter(getColor(if (recording) R.color.rec_signal else R.color.ink))
        recDot.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        timerView.visibility = if (recording) View.VISIBLE else View.GONE
        hintView.visibility = if (recording) View.GONE else View.VISIBLE
        updatePreRecordDot()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
