package com.dsh.bodycam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
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
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.dsh.bodycam.camera.CameraController
import com.dsh.bodycam.camera.CameraSpec
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.core.TriggerRouter
import com.dsh.bodycam.gl.GlPipeline
import com.dsh.bodycam.key.TriggerReceiver
import com.dsh.bodycam.light.LightController
import com.dsh.bodycam.record.PreRecordBuffer
import com.dsh.bodycam.record.SegmentRecorder
import com.dsh.bodycam.service.KeepAliveService
import com.dsh.bodycam.util.DiskGuard
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

        /** 空间告警 toast 的节流间隔：巡检 2 秒一次，但提示不要刷屏 */
        private const val DISK_WARN_TOAST_MS = 30_000L

        private const val MB = 1024L * 1024L

        /**
         * 未手动校准过的默认偏移：让净旋转归零（开箱即正向）。
         *
         * 前后摄的传感器方向不同（后摄多为 90，前摄多为 270），所以默认偏移
         * 不能共用：用同一个 270 去校准前摄会得到净旋转 180，画面上下颠倒。
         * 按当前摄像头的传感器角度现算，后摄 90 → 270、前摄 270 → 90，两者都归零。
         */
        private fun defaultRotationOffset(sensorOrientation: Int): Int =
            (360 - sensorOrientation % 360) % 360

        /**
         * 方向校准按摄像头分开存。不同摄像头的传感器方向与变换矩阵互不相干，
         * 后摄校准好的偏移套到前摄上一定是错的，所以键名带上 cameraId。
         */
        private fun rotationKey(cameraId: String) = "rotation_offset_$cameraId"
    }

    private lateinit var previewView: SurfaceView
    private lateinit var flashView: View
    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var btnShutter: View
    private lateinit var btnRecord: View
    private lateinit var iconRecord: android.widget.ImageView

    /** 顶栏第二行的录像图标：空闲时显示，录像时让位给红色计时 */
    private lateinit var iconRecordStatus: android.widget.ImageView
    private lateinit var labelRecord: TextView
    private lateinit var debugView: TextView
    private lateinit var preRecordDot: View
    private lateinit var btnSettings: View
    private lateinit var btnAudioRecord: View
    private lateinit var btnFiles: View
    private lateinit var btnTorch: android.widget.ImageView
    private lateinit var btnSwitchCamera: android.widget.ImageView
    private lateinit var zoomSlider: com.dsh.bodycam.ui.ZoomSliderView

    /** 当前使用的摄像头能力；切换摄像头时整体替换 */
    private var currentSpec: CameraSpec? = null

    /** 设备上所有可用摄像头（后摄在前），多于一个才显示切换入口 */
    private var cameras: List<CameraSpec> = emptyList()

    /** 补光灯的相机通路；LightController 建好后注入，销毁时注销 */
    private val torchPath = TorchPath()

    /**
     * 连拍状态。连拍全部在主线程的 [uiHandler] 上排程，所以两个字段都只在
     * 主线程读写；[burstRemaining] 归零即表示本次连拍结束。
     */
    private var burstRemaining = 0
    private var burstSaved = 0

    /** 已发出、尚未回调的拍照数；与 [burstSaved] 一起判断连拍是否全部落盘 */
    private var burstIssued = 0

    /** 连拍期间抑制单张 toast，改在结束时汇总一次 */
    private var burstActive = false

    /** 连拍定时任务；取消连拍或退出页面时移除 */
    private var burstTick: Runnable? = null

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
     * 未校准过时按当前摄像头的传感器角度取默认值（见 [defaultRotationOffset]），
     * 前后摄各自归零，开箱即正向。
     */
    private var rotationOffset = 0

    private var recording = false
    private var recordStartElapsed = 0L
    private var resumed = false

    /** 上次磁盘巡检时刻；告警提示做了节流，避免每 2 秒弹一次 */
    private var lastDiskCheckElapsed = 0L
    private var lastDiskWarnElapsed = 0L

    /** 磁盘巡检在后台跑，同一时刻只允许一次 */
    @Volatile
    private var diskCheckRunning = false

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
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            // 一直走，不只在录像时走：否则空闲时预览上的时间会停在进入应用的时刻
            if (resumed) {
                updateWatermark()
                updateTimer()
                checkDiskDuringRecording()
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

        // 灯光统一的控制入口：相机闪光灯通路 + 可选的 sysfs 固定节点通路
        LightController.init()
        LightController.attachCameraTorch(torchPath)

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
        btnTorch.setOnClickListener { toggleTorch() }
        btnSwitchCamera.setOnClickListener { switchCamera() }
        // 拖动/点击缩放条 → 直接改相机数字变焦，不重建会话，录像中也能调
        zoomSlider.onZoomChanged = { ratio -> camera?.setZoom(ratio) }

        // 设置变化（预录开关/时长、触发键/广播）→ 刷新缓存与黄点、重挂广播
        settingsListener = object : SettingsStore.Listener {
            override fun onSettingsChanged() {
                runOnUiThread {
                    applyPreRecordSetting()
                    refreshTriggerReceiver()
                    updateDebugInfo()
                    applyPhotoSettings()
                }
            }
        }
        SettingsStore.setListener(settingsListener)
        refreshTriggerReceiver()

        // 方向校准：点调试行每次 +90°，可选 0/90/180/270，按摄像头分别写入
        // 初始偏移在 setup() 里按选中的摄像头读取，这里只挂点击
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        debugView.setOnClickListener {
            rotationOffset = (rotationOffset + 90) % 360
            val id = currentSpec?.cameraId
            if (id != null) {
                prefs.edit().putInt(rotationKey(id), rotationOffset).apply()
            }
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
        // 连拍排程挂在 uiHandler 上，已被上面一并清掉，这里只复位状态
        burstActive = false
        burstRemaining = 0
        burstTick = null
        // 前摄模式下亮的是别的摄像头的 LED，退出页面必须手工关掉
        releaseLights()
        LightController.detachCameraTorch()
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
        diskExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.preview)
        flashView = findViewById(R.id.flashView)
        statusView = findViewById(R.id.statusView)
        timerView = findViewById(R.id.timerView)
        btnShutter = findViewById(R.id.btnShutter)
        btnRecord = findViewById(R.id.btnRecord)
        iconRecord = findViewById(R.id.iconRecord)
        iconRecordStatus = findViewById(R.id.iconRecordStatus)
        labelRecord = findViewById(R.id.labelRecord)
        debugView = findViewById(R.id.debugView)
        preRecordDot = findViewById(R.id.preRecordDot)
        btnSettings = findViewById(R.id.btnSettings)
        btnAudioRecord = findViewById(R.id.btnAudioRecord)
        btnFiles = findViewById(R.id.btnFiles)
        btnTorch = findViewById(R.id.btnTorch)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        zoomSlider = findViewById(R.id.zoomSlider)
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
        cameras = CameraController.listCameras(this)
        val savedId = SettingsStore.cameraId(this)
        // 从已枚举的列表里挑，避免再扫一遍 CameraCharacteristics
        val spec = cameras.firstOrNull { it.cameraId == savedId }
            ?: cameras.firstOrNull { !it.isFront }
            ?: cameras.firstOrNull()
        if (spec == null) {
            toast(getString(R.string.no_camera))
            return
        }
        applyCameraSpec(spec)
        // 校准偏移按摄像头存，onCreate 时还不知道用哪颗，这里补读
        loadRotationOffset()
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

        val controller = buildCameraController(spec, surface)
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
     * 记录当前相机能力并同步流尺寸/传感器角度。
     * 相机流永远是传感器方向的尺寸；输出尺寸再按设备姿态交换（见 applyOrientation）。
     */
    private fun applyCameraSpec(spec: CameraSpec) {
        currentSpec = spec
        streamW = spec.videoSize.width
        streamH = spec.videoSize.height
        sensorOrientation = spec.sensorOrientation
        // 每颗摄像头的变焦能力不同：切过去先按新上限重置到 1x，
        // 不支持缩放的摄像头（zoomMax <= 1）由控件自己隐藏
        zoomSlider.setZoomRange(spec.zoomMax)
        updateCameraButtons()
    }

    /**
     * 读取当前摄像头的校准偏移。
     *
     * 优先级：该摄像头自己存过的值 → 后摄沿用旧版全局键（老版本只校准过后摄）
     * → 按传感器角度现算的默认值（前后摄各自归零，开箱即正向）。
     */
    private fun loadRotationOffset() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = currentSpec?.cameraId
        val fallback = defaultRotationOffset(sensorOrientation)
        rotationOffset = when {
            id == null -> fallback
            prefs.contains(rotationKey(id)) -> prefs.getInt(rotationKey(id), fallback)
            // 后摄沿用旧版全局键（老版本只校准过后摄）
            currentSpec?.isFront == false ->
                prefs.getInt(KEY_ROTATION_OFFSET, fallback)
            else -> fallback
        }
    }

    /**
     * 顶栏入口显隐：多摄才给切换；补光灯是否可用交给 [LightController] 判断
     * （相机 LED 或 sysfs 节点任一条接通就算可用）。
     * 前摄本身通常没有 LED，但硬件 LED 挂在后摄上，切到前摄时补光按钮
     * 仍然可用——改去点亮那颗带闪光灯的摄像头（见 [toggleTorch]）。
     */
    private fun updateCameraButtons() {
        btnSwitchCamera.visibility = if (cameras.size > 1) View.VISIBLE else View.GONE
        btnTorch.visibility =
            if (LightController.available(LightController.Kind.TORCH)) View.VISIBLE else View.GONE
        updateTorchButton()
    }

    /** 补光灯按钮高亮：开着琥珀，关着 muted（与其它顶栏图标一致） */
    private fun updateTorchButton() {
        if (!this::btnTorch.isInitialized) return
        val on = LightController.isOn(LightController.Kind.TORCH)
        btnTorch.setColorFilter(getColor(if (on) R.color.accent else R.color.muted))
    }

    // ------------------------------------------------------------ 摄像头控制

    /**
     * 相机闪光灯通路：当前摄像头自带 LED 时走预览重复请求的 TORCH（[CameraController.setTorch]），
     * 录像中切换也不会打断编码；当前摄像头没有 LED（常见于前摄）时，用
     * [CameraManager.setTorchMode] 点亮设备上另一颗带闪光灯的摄像头——那颗没被本应用打开，
     * 不会 CAMERA_IN_USE。
     */
    private inner class TorchPath : LightController.CameraTorch {
        /** 固定键：状态跨摄像头切换保留，切回来时按钮高亮不会错乱 */
        override val key: String = "camera:torch"

        override val available: Boolean
            get() = currentSpec?.flashAvailable == true || fallbackTorchId() != null

        override val isOn: Boolean
            get() = camera?.isTorchOn == true || externalTorchOn

        /** 用 setTorchMode 点亮的那颗外部摄像头 id；关灯后清空 */
        private var externalTorchOn = false
        private var externalTorchCameraId: String? = null

        /** 当前摄像头有 LED 就用它自己，否则退到设备上任意一颗带闪光灯的（通常是后摄） */
        private fun fallbackTorchId(): String? = cameras.firstOrNull { it.flashAvailable }?.cameraId

        override fun set(on: Boolean): Boolean {
            if (currentSpec?.flashAvailable == true) {
                val cam = camera ?: return false
                if (!cam.setTorch(on)) return false
                // 相机自带的灯归控制器所有；外部灯若还亮着顺手关掉，避免两处同亮
                if (on) releaseExternal()
                return true
            }
            val id = fallbackTorchId() ?: return false
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ok = runCatching { manager.setTorchMode(id, on) }.isSuccess
            if (!ok) return false
            externalTorchOn = on
            externalTorchCameraId = if (on) id else null
            return true
        }

        /** 关掉外部点亮的补光灯（切摄像头 / 离开页面时调用） */
        fun releaseExternal() {
            val id = externalTorchCameraId ?: return
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            runCatching { manager.setTorchMode(id, false) }
            externalTorchOn = false
            externalTorchCameraId = null
        }
    }

    /** 一键补光：具体走哪条通路由 [LightController] 决定（相机 LED 优先，其次 sysfs 节点） */
    private fun toggleTorch() {
        if (!LightController.toggle(LightController.Kind.TORCH)) {
            toast(getString(R.string.torch_unavailable))
        }
        updateTorchButton()
    }

    /** 切摄像头 / 退出页面前释放所有灯：外部 LED 不释放会一直亮到系统回收 */
    private fun releaseLights() {
        torchPath.releaseExternal()
        LightController.releaseAll()
    }

    /**
     * 切换摄像头（后摄→前摄→其它后摄…循环）。
     *
     * 录像中不允许切：摄像机会话整体重建，编码器拿不到连续帧。
     * 切换顺序：先关旧设备 → 再开新设备（Camera2 同一时间只允许一个
     * 活跃设备，先关后开才不会报 CAMERA_IN_USE）。
     * SurfaceTexture 与 GlPipeline 复用，只按新 spec 更新流尺寸与旋转。
     */
    private fun switchCamera() {
        if (recording || savingSegment) {
            return toast(getString(R.string.camera_switch_blocked))
        }
        // 连拍跨摄像头没有意义：旧控制器的 capture 会在 close 后失效
        cancelBurst()
        if (cameras.size <= 1) return
        val currentId = currentSpec?.cameraId
        val index = cameras.indexOfFirst { it.cameraId == currentId }
        val next = cameras[(index + 1).mod(cameras.size)]
        if (next.cameraId == currentId) return

        val p = pipeline ?: return toast(getString(R.string.camera_not_ready))
        val surface = cameraSurface ?: return

        // 预录编码器按旧摄像头的画幅开好，切换前先停掉，切完按新画幅重建
        preRecordBuffer?.takeIf { it.isRunning }?.let {
            p.setPreRecordSurface(null)
            it.release()
        }

        // 旧设备必须先释放（含 torch），否则新设备打开会 CAMERA_IN_USE
        camera?.close()
        camera = null
        // 前摄模式下点亮的是另一颗摄像头的 LED，这台设备没被我们持有，要单独关掉
        releaseLights()

        applyCameraSpec(next)
        // 每个摄像头有自己的校准偏移，切过去必须换一套，否则画面方向是错的
        loadRotationOffset()
        // 流尺寸可能不同（前后摄支持的分辨率集合不一样），SurfaceTexture 要跟着改，
        // 否则相机会按旧尺寸出帧、画面被拉伸
        surfaceTexture?.setDefaultBufferSize(streamW, streamH)
        // applyOrientation 内部会在画幅变化时同步 setVideoSize + setRotation
        applyOrientation()

        val controller = buildCameraController(next, surface)
        camera = controller
        controller.open()
        updateCameraButtons()
        SettingsStore.setCameraId(this, next.cameraId)
        toast(getString(R.string.camera_switched, next.label))
        applyPreRecordSetting()
    }

    private fun buildCameraController(spec: CameraSpec, surface: Surface): CameraController {
        val controller = CameraController(
            context = this,
            spec = spec,
            targetSurface = surface,
            rotationProvider = { currentRotation() },
            onPhoto = { bytes -> savePhoto(bytes) },
            onError = { message -> runOnUiThread { toast(message) } }
        )
        // 拍照相关设置（补光 / 质量 / 尺寸）随控制器实例下发
        controller.setPhotoFlashMode(SettingsStore.photoFlashMode(this))
        controller.setPhotoQuality(SettingsStore.photoQuality(this))
        val (targetW, targetH) = AppConfig.photoSizeOptions()
            .getOrElse(SettingsStore.photoSizeIndex(this)) { AppConfig.photoSizeOptions()[0] }
        controller.setPhotoSize(spec.resolvePhotoSize(targetW, targetH))
        return controller
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

    // ------------------------------------------------------------ 拍照

    /**
     * 把拍照相关设置下发给当前控制器。
     *
     * 质量与补光只改字段，下一次拍照即生效；尺寸要换 ImageReader 并重建会话，
     * 所以**录像中跳过**（会话重建会让编码器拿不到连续帧），下次录像结束
     * 或切摄像头时会重新下发。
     */
    private fun applyPhotoSettings() {
        val cam = camera ?: return
        cam.setPhotoFlashMode(SettingsStore.photoFlashMode(this))
        cam.setPhotoQuality(SettingsStore.photoQuality(this))
        if (!recording) {
            val (w, h) = AppConfig.photoSizeOptions()
                .getOrElse(SettingsStore.photoSizeIndex(this)) { AppConfig.photoSizeOptions()[0] }
            cam.setPhotoSize(currentSpec?.resolvePhotoSize(w, h) ?: android.util.Size(w, h))
        }
    }

    private fun capturePhoto() {
        val cam = camera ?: return toast(getString(R.string.camera_not_ready))
        if (!StoragePermission.isGranted(this)) {
            toast(getString(R.string.need_storage_access))
            StoragePermission.openSettings(this)
            return
        }
        if (SettingsStore.photoMode(this) == AppConfig.PHOTO_MODE_BURST) {
            startBurst(cam)
        } else {
            cam.takePhoto()
            flashScreen()
        }
    }

    // ------------------------------------------------------------ 连拍

    /**
     * 开始连拍：立刻拍第一张，之后每隔 N 秒再拍一张，共拍 N 张。
     *
     * 排程放在主线程：每张都走一次 [CameraController.takePhoto] 的**单次 capture**，
     * 预览流与会话完全不动，所以既不阻塞预览，也不重建会话。
     *
     * 录像中不允许进入连拍：连拍每张都要往 JPEG 输出送一帧，与编码器抢带宽，
     * 连续多张会让编码器偶发掉帧，这里按需求给的折中方案直接拦下。
     */
    private fun startBurst(cam: CameraController) {
        if (recording) return toast(getString(R.string.photo_burst_blocked_recording))
        if (burstActive) return  // 上一轮还没拍完，忽略重复触发

        val total = SettingsStore.burstCount(this).coerceAtLeast(1)
        val intervalMs = SettingsStore.burstIntervalSeconds(this).coerceAtLeast(1) * 1000L

        burstActive = true

        burstRemaining = total
        burstSaved = 0
        burstIssued = 0

        // 第一张立即拍，后续按固定间隔排队（不叠加上一张的写盘耗时）
        shootBurstFrame(cam, intervalMs)
    }

    /** 拍一张，然后按间隔预约下一张；拍满 total 张后收尾 */
    private fun shootBurstFrame(cam: CameraController, intervalMs: Long) {
        if (!burstActive) return
        if (!cam.isReady) {
            // 相机在连拍途中被系统掐断（息屏/被抢占）：中止，别让状态卡住
            abortBurst()
            return
        }
        burstRemaining--
        burstIssued++
        cam.takePhoto()
        flashScreen()

        if (burstRemaining <= 0) {
            finishBurst()
            return
        }
        val tick = Runnable { shootBurstFrame(cam, intervalMs) }
        burstTick = tick
        uiHandler.postDelayed(tick, intervalMs)
    }

    /**
     * 拍够张数：停止排程，但要把**在途照片等全部写盘完成**再汇总。
     * 每张的 savePhoto 异步跑在 photoExecutor 上，提前汇总会少算最后几张。
     */
    private fun finishBurst() {
        cancelBurstTick()
        burstRemaining = 0
        settleBurst(cancelled = false)
    }

    /**
     * 收尾：等所有已发出的照片回调完毕（成功或失败各算一次），再弹一次汇总。
     * [burstActive] 全程保持 true，就是为了让 savePhoto 在等待期间不逐张弹提示。
     */
    private fun settleBurst(cancelled: Boolean) {
        if (burstSaved < burstIssued) {
            uiHandler.postDelayed({ settleBurst(cancelled) }, 100)
            return
        }
        burstActive = false
        if (cancelled) {
            if (burstSaved > 0) toast(getString(R.string.photo_burst_cancelled))
        } else {
            toast(getString(R.string.photo_burst_done_fmt, burstSaved))
        }
        burstIssued = 0
        burstSaved = 0

    }

    /** 取消进行中的连拍（退出页面、切换摄像头、开录时调用），已拍的照常保存 */
    private fun cancelBurst() {
        if (!burstActive) return
        cancelBurstTick()
        burstRemaining = 0
        settleBurst(cancelled = true)
    }

    /** 相机不可用导致的中止：静默复位并提示重试 */
    private fun abortBurst() {
        cancelBurstTick()
        burstActive = false
        burstRemaining = 0
        burstIssued = 0
        burstSaved = 0

        toast(getString(R.string.camera_not_ready))
    }

    private fun cancelBurstTick() {
        burstTick?.let { uiHandler.removeCallbacks(it) }
        burstTick = null
    }

    private fun savePhoto(bytes: ByteArray) {
        // 拍照时间要固定成「按下快门那一刻」，不能等后台线程排到才开始算
        val data = watermarkData(System.currentTimeMillis())
        // 用「自动角度」而不是校准后的角度：JPEG_ORIENTATION 写的就是这个值，
        // 照片该转多少度由它决定，跟预览/录像的校准偏移无关。
        val rotation = autoRotationDeg
        // 连拍时每张单独命名；毫秒时间戳保证不撞名（间隔最短 1 秒，远大于 1ms）
        val name = "IMG_${Storage.stamp()}.jpg"
        photoExecutor.execute {
            // 照片链路单独用一个 painter 实例：WatermarkPainter 内部复用 Bitmap，不是线程安全的
            val stamped = runCatching {
                PhotoWatermarker.watermark(bytes, WatermarkPainter(), data, rotation)
            }.getOrDefault(bytes)
            val saved = runCatching { Storage.savePhoto(this, stamped, name) }.getOrNull()
            runOnUiThread {
                if (saved != null) burstSaved++
                // 连拍（含收尾等待期）不逐张弹提示，否则每秒一条把画面盖住；
                // 汇总由 settleBurst 在全部落盘后统一弹一次
                if (!burstActive) {
                    toast(
                        if (saved != null) getString(R.string.photo_saved, saved)
                        else getString(R.string.photo_failed)
                    )
                }
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
        // 循环录像关闭时，空间不够就不开录——否则录到一半必然被迫中断
        if (!SettingsStore.isLoopRecordEnabled(this)) {
            val st = DiskGuard.inspect(this)
            if (st.level != DiskGuard.Level.OK) {
                toast(getString(R.string.disk_low_refuse_record, st.freeBytes / MB))
                return
            }
        }
        val p = pipeline ?: return toast(getString(R.string.camera_not_ready))
        val rec = recorder ?: return toast(getString(R.string.camera_not_ready))
        val cam = camera ?: return toast(getString(R.string.camera_not_ready))

        // 连拍与录像互斥：连拍会连续往 JPEG 输出送帧，与编码器抢带宽
        cancelBurst()

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
        // 录像期间被跳过的拍照尺寸切换，现在补上（会话重建放在停录之后才安全）
        applyPhotoSettings()
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

    // ------------------------------------------------------------ 磁盘空间

    /**
     * 录像期间的磁盘巡检，节流到 [AppConfig.DISK_CHECK_MS] 一次。
     *
     * 行为：
     *  - 低于**告警线**：循环录像开 → 由 [DiskGuard] 删最早的分段腾空间（正在写的
     *    分段靠 `recorder.currentSegmentName` 跳过）；关 → 只提示一次。
     *  - 低于**强制停录线**：无论循环录像是否开启，都安全停录。
     *
     * 停录走 [stopRecording]，它会把编码器尾帧排空、写出最后一个 moof+mdat，
     * 所以文件仍是可播放的 fMP4，不会留下坏文件。
     */
    private fun checkDiskDuringRecording() {
        if (!recording || savingSegment || diskCheckRunning) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiskCheckElapsed < AppConfig.DISK_CHECK_MS) return
        lastDiskCheckElapsed = now

        // 删除文件与多次 StatFs 都是 I/O，放后台线程，别卡住预览
        diskCheckRunning = true
        val loop = SettingsStore.isLoopRecordEnabled(this)
        val rec = recorder
        diskExecutor.execute {
            val status = runCatching {
                DiskGuard.check(this, loop) { rec?.currentSegmentName }
            }.getOrNull()
            diskCheckRunning = false
            if (status == null || isDestroyed) return@execute
            uiHandler.post { onDiskStatus(status, loop) }
        }
    }

    private fun onDiskStatus(status: DiskGuard.Status, loop: Boolean) {
        if (status.removedCount > 0) {
            Log.i("BodyCamDisk", "循环录像清理 ${status.removedCount} 个分段，释放 ${status.freedBytes / MB}MB")
        }
        val now = SystemClock.elapsedRealtime()
        when (status.level) {
            DiskGuard.Level.OK -> Unit
            DiskGuard.Level.WARN -> {
                // 循环录像开着却没删出空间（已无可删），或关着 → 提示，节流到 30 秒
                if (now - lastDiskWarnElapsed < DISK_WARN_TOAST_MS) return
                lastDiskWarnElapsed = now
                toast(
                    getString(
                        if (loop) R.string.disk_low_no_removable
                        else R.string.disk_low_loop_off,
                        status.freeBytes / MB
                    )
                )
                notifyLowDisk(status.freeBytes / MB)
            }
            DiskGuard.Level.STOP -> {
                toast(getString(R.string.disk_full_stop, status.freeBytes / MB))
                notifyLowDisk(status.freeBytes / MB)
                stopRecording()
            }
        }
    }

    /** 空间不足时把告警文案推给前台服务，更新常驻通知（息屏也能看到） */
    private fun notifyLowDisk(freeMb: Long) {
        val intent = Intent(this, KeepAliveService::class.java)
            .putExtra(KeepAliveService.EXTRA_LOW_DISK, getString(R.string.notification_low_disk, freeMb))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
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
        // 顶栏第二行：空闲显示录像图标，录像时换成红色计时（二者互斥占位，高度恒定）
        iconRecordStatus.visibility = if (recording) View.GONE else View.VISIBLE
        timerView.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        updatePreRecordDot()
        // 补光灯状态可能因会话重建而变化，按钮高亮每次都按真实状态重画
        updateTorchButton()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
