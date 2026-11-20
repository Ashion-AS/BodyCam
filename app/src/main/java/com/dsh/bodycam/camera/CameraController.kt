package com.dsh.bodycam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.util.rotationToDegrees
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 探测到的相机能力：分辨率、传感器方向、朝向、是否带闪光灯、数字变焦上限。
 * [label] 是给用户看的短名（后摄 / 前摄 / 后摄 2…），用于切换时的提示与日志。
 */
data class CameraSpec(
    val cameraId: String,
    val videoSize: Size,
    val photoSize: Size,
    val sensorOrientation: Int,
    val lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    val flashAvailable: Boolean = false,
    val label: String = "",
    /** 传感器有效像素区域，缩放裁剪以它为中心 */
    val activeArray: Rect = Rect(),
    /** 最大数字变焦倍率；1 表示该摄像头不支持缩放（缩放条应隐藏） */
    val zoomMax: Float = 1f,
    /** 该摄像头支持的全部 JPEG 输出尺寸，用于按设置档位挑选/回退 */
    val jpegSizes: List<Size> = emptyList()
) {
    val isFront: Boolean get() = lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    /**
     * 按设置档位的目标像素挑一个实际可用的 JPEG 尺寸。
     *
     * 相机只支持固定的几档，设置页给的档位不一定都有。规则：
     *  1. 精确命中就用它；
     *  2. 否则在同一宽高比（16:9）里取面积最接近的；
     *  3. 再否则在全量列表里取面积最接近的。
     * 都没有（例如探测失败）退回探测时选好的 [photoSize]。
     */
    fun resolvePhotoSize(targetW: Int, targetH: Int): Size {
        if (jpegSizes.isEmpty()) return photoSize
        val targetArea = targetW.toLong() * targetH
        jpegSizes.firstOrNull { it.width == targetW && it.height == targetH }?.let { return it }
        val sameRatio = jpegSizes.filter { it.width * targetH == it.height * targetW }
        val pool = sameRatio.ifEmpty { jpegSizes }
        return pool.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - targetArea) }
            ?: photoSize
    }

    /**
     * 按倍率算中心裁剪区域：裁得越小，画面放大越多。
     * 用 [CaptureRequest.SCALER_CROP_REGION] 做数字变焦（兼容 minSdk 26，
     * API 30 才有 CONTROL_ZOOM_RATIO）。倍率 1 或没有有效区域时返回整个区域。
     */
    fun cropRegionFor(ratio: Float): Rect {
        if (activeArray.isEmpty) return activeArray
        val safe = if (zoomMax > 1f) ratio.coerceIn(1f, zoomMax) else 1f
        if (safe <= 1.001f) return activeArray
        val w = (activeArray.width() / safe).toInt().coerceAtLeast(2)
        val h = (activeArray.height() / safe).toInt().coerceAtLeast(2)
        val left = activeArray.centerX() - w / 2
        val top = activeArray.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }
}

/**
 * Camera2 封装。
 *
 * 关键点：只开一路预览流（SurfaceTexture → OpenGL），JPEG 作为第二路输出挂在同一个
 * CaptureSession 上，但**不加入**重复预览请求——只有这样，录像过程中拍照才不会打断录像。
 */
class CameraController(
    private val context: Context,
    private val spec: CameraSpec,
    private val targetSurface: Surface,
    private val rotationProvider: () -> Int,
    private val onPhoto: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {

    private val thread = HandlerThread("bodycam-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /** 会话代次：每建一个 CaptureSession +1，用于丢弃过期的 onConfigured 回调 */
    private var sessionGen = 0

    @Volatile
    private var streaming = false

    /** 补光灯 torch 是否开启（重复请求里的 FLASH_MODE 与状态保持一致） */
    @Volatile
    private var torchOn = false

    /** 当前数字变焦倍率；写 SCALER_CROP_REGION 实现，不重建会话 */
    @Volatile
    private var zoomRatio = 1f

    /** 拍照补光模式：AppConfig.PHOTO_FLASH_OFF / AUTO / ALWAYS */
    @Volatile
    private var photoFlashMode = AppConfig.PHOTO_FLASH_OFF

    /** JPEG 压缩质量，来自设置页的质量档位 */
    @Volatile
    private var jpegQuality = AppConfig.jpegQuality(AppConfig.PHOTO_QUALITY_HIGH)

    /** 当前实际使用的拍照尺寸；默认用探测时选好的，切档位时替换 */
    @Volatile
    private var photoSize: Size = spec.photoSize

    /** 防止 open() 与 ensureOpen() 在相机真正打开前重复调用 openCamera */
    @Volatile
    private var opening = false

    private val released = AtomicBoolean(false)

    val isReady: Boolean get() = session != null
    val videoSize: Size get() = spec.videoSize

    /** 当前补光灯是否开着（UI 用来渲染按钮高亮） */
    val isTorchOn: Boolean get() = torchOn

    fun open() {
        handler.post { doOpen() }
    }

    /**
     * 息屏且未录像时，系统可能把相机断掉（应用处于后台）。
     * 回到前台时调用这里重新打开，避免界面「黑掉」。
     */
    fun ensureOpen() {
        if (released.get()) return
        handler.post { if (device == null) doOpen() }
    }

    private fun doOpen() {
        Log.i(TAG, "doOpen device=${device}")
        if (released.get() || device != null || opening) return
        opening = true
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            opening = false
            onError("缺少相机权限")
            return
        }

        if (imageReader == null) {
            imageReader = createReader(photoSize)
        }

        try {
            manager.openCamera(spec.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "onOpened")
                    opening = false
                    if (released.get()) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.i(TAG, "onDisconnected")
                    opening = false
                    camera.close()
                    device = null
                    session = null
                    imageReader?.close()
                    imageReader = null
                    // 这里通常是息屏导致的正常断开，不打扰用户，回前台会自动重开
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close()
                    device = null
                    onError("相机错误码 $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            opening = false
            onError("相机权限被拒绝")
        } catch (e: Exception) {
            opening = false
            onError("打开相机失败：${e.message}")
        }
    }

    /**
     * 建 JPEG 输出。缓冲数取 4：连拍时间隔最短 1 秒，但如果上一张的
     * decode + 重编码还在排队，2 个缓冲会让第 3 张直接丢帧。
     */
    private fun createReader(size: Size): ImageReader =
        ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 4).apply {
            setOnImageAvailableListener({ reader -> handleJpeg(reader) }, handler)
        }

    private fun createSession(camera: CameraDevice) {
        // 会话代次：startStreaming 是异步回调，若期间 restartPreview/setStreaming
        // 已经关掉旧会话并建了新会话，旧回调必须丢弃——否则会把已关闭的
        // session 赋回字段，随后的 setRepeatingRequest 抛 "Session has been closed"。
        val gen = ++sessionGen
        val surfaces = mutableListOf(targetSurface)
        imageReader?.let { surfaces.add(it.surface) }
        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    Log.i(TAG, "onConfigured gen=$gen current=$sessionGen")
                    if (released.get() || gen != sessionGen) {
                        s.close()
                        return
                    }
                    session = s
                    startStreaming()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == sessionGen) onError("创建相机会话失败")
                }
            }, handler)
        } catch (e: Exception) {
            onError("创建相机会话异常：${e.message}")
        }
    }

    private fun startStreaming() {
        val camera = device
        val s = session
        Log.i(TAG, "startStreaming camera=${camera != null} session=${s != null}")
        if (camera == null || s == null || released.get()) return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(targetSurface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            applyZoom(builder)
            // 补光灯：重复请求里带 FLASH_MODE_TORCH 才能常亮；关灯时显式写 OFF
            if (spec.flashAvailable) {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
            s.setRepeatingRequest(builder.build(), null, handler)
            streaming = true
            Log.i(TAG, "repeating request submitted")
        } catch (e: IllegalStateException) {
            // 会话已被关闭（竞态/系统掐断）：重建一次，而不是黑屏了事
            Log.w(TAG, "session closed, recreate", e)
            session = null
            streaming = false
            createSession(camera)
        } catch (e: Exception) {
            Log.w(TAG, "startStreaming failed", e)
            onError("启动预览失败：${e.message}")
        }
    }

    /**
     * 回前台时重建预览会话。
     *
     * 后台期间相机可能被系统悄悄掐断（回调不一定给到我们），此时对旧会话
     * 重新 setRepeatingRequest 不会报错、但也不会再出帧，表现就是回来黑屏。
     * 所以这里直接把会话关掉重建，最稳。
     */
    fun restartPreview() {
        handler.post {
            if (released.get()) return@post
            val camera = device
            if (camera == null) {
                doOpen()
                return@post
            }
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            streaming = false
            // 让在途的 onConfigured 回调失效
            sessionGen++
            createSession(camera)
        }
    }

    /**
     * 补光灯开关（录像中也能切）。
     *
     * 用重复请求里的 FLASH_MODE_TORCH 实现，而不是 CameraManager.setTorchMode：
     * 后者在相机被本应用占用时常常返回 CAMERA_IN_USE，且与预览请求抢控制权；
     * 走请求则只改一个字段，编码器与预览流完全不中断。
     *
     * @param on true 开灯，false 关灯
     * @return 是否接受（相机没准备好或该摄像头无闪光灯时返回 false）
     */
    fun setTorch(on: Boolean): Boolean {
        if (!spec.flashAvailable) return false
        if (released.get()) return false
        torchOn = on
        handler.post { refreshRepeatingRequest() }
        return true
    }

    /** 拍照补光模式；影响下一次 [takePhoto] 的闪光灯策略 */
    fun setPhotoFlashMode(mode: Int) {
        photoFlashMode = mode
    }

    /** JPEG 质量档位：见 [AppConfig.PHOTO_QUALITY_HIGH] / MEDIUM / LOW */
    fun setPhotoQuality(quality: Int) {
        jpegQuality = AppConfig.jpegQuality(quality)
    }

    /** 当前实际使用的拍照尺寸（已按设备能力回退过） */
    val currentPhotoSize: Size get() = photoSize

    /**
     * 切换拍照尺寸。
     *
     * ImageReader 的尺寸在创建时固定，换尺寸只能换一个 reader；而 reader 是
     * CaptureSession 的输出目标之一，所以必须关会话 → 换 reader → 重建会话。
     * 预览流（[targetSurface]）不动，画面不会黑。
     *
     * **录像中不要调用**：会话重建会让编码器拿不到连续帧。是否允许切换由上层
     * （[com.dsh.bodycam.MainActivity]）按录像状态判断，控制器不做拦截。
     *
     * @return true = 已排入切换队列（或本来就是目标尺寸）
     */
    fun setPhotoSize(size: Size): Boolean {
        if (released.get()) return false
        if (size == photoSize && imageReader != null) return true
        photoSize = size
        handler.post {
            if (released.get()) return@post
            val camera = device ?: return@post
            // 换 reader：先放掉旧的，再按新尺寸建一个新的
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            streaming = false
            sessionGen++
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = createReader(size)
            Log.i(TAG, "photo size -> ${size.width}x${size.height}")
            createSession(camera)
        }
        return true
    }

    /** 该摄像头的最大变焦倍率；<= 1 表示不支持缩放 */
    val zoomMax: Float get() = spec.zoomMax

    /**
     * 设置数字变焦倍率（1..zoomMax），只改重复请求里的 SCALER_CROP_REGION，
     * 会话不重建，录像中拖缩放条也不会打断编码。
     */
    fun setZoom(ratio: Float) {
        if (released.get()) return
        zoomRatio = ratio.coerceIn(1f, spec.zoomMax.coerceAtLeast(1f))
        handler.post { refreshRepeatingRequest() }
    }

    /** 把当前倍率写进请求；不支持缩放或区域无效时整段跳过 */
    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (spec.zoomMax <= 1f || spec.activeArray.isEmpty) return
        builder.set(CaptureRequest.SCALER_CROP_REGION, spec.cropRegionFor(zoomRatio))
    }

    /**
     * 用当前字段重建一次重复请求：只改 FLASH_MODE，会话不动。
     * 预览没在跑（退后台）时什么都不做，回前台重建会话会带上最新状态。
     */
    private fun refreshRepeatingRequest() {
        if (!streaming || released.get()) return
        startStreaming()
    }

    /** 息屏或退到后台且没在录像时，停掉预览流省电 */
    fun setStreaming(enabled: Boolean) {
        Log.i(TAG, "setStreaming $enabled")
        handler.post {
            if (enabled) {
                // 会话可能是旧的/已关闭的（后台被系统掐断），直接重建一次
                if (!streaming) {
                    val camera = device
                    if (camera != null && session == null) createSession(camera)
                    else startStreaming()
                }
            } else if (streaming) {
                try {
                    session?.stopRepeating()
                } catch (_: Exception) {
                }
                streaming = false
            }
        }
    }

    /**
     * 录像中拍照：单次 capture，两个 target（JPEG + 预览流），
     * 不会中断 setRepeatingRequest 建立的录像数据流。
     */
    fun takePhoto() {
        handler.post {
            val camera = device ?: return@post
            val s = session ?: return@post
            val reader = imageReader ?: return@post
            if (released.get()) return@post
            try {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.addTarget(targetSurface)
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientationHint())
                // 照片与预览保持同一视野：缩放后拍照也裁到同样区域
                applyZoom(builder)
                applyPhotoFlash(builder)
                s.capture(builder.build(), null, handler)
            } catch (e: Exception) {
                onError("拍照失败：${e.message}")
            }
        }
    }

    /**
     * 拍照补光策略：
     * - OFF：不闪（但若 torch 已在常亮，保持常亮，不额外闪）；
     * - AUTO：环境暗才闪，交给 AE 预闪判断（AE_MODE_ON_AUTO_FLASH）；
     * - ALWAYS：每次必闪（AE_MODE_ON_ALWAYS_FLASH）。
     * 无闪光灯的设备整个跳过。
     */
    private fun applyPhotoFlash(builder: CaptureRequest.Builder) {
        if (!spec.flashAvailable) return
        // 补光常亮时不必再闪：继续保持 TORCH 即可，避免拍一张闪一下
        if (torchOn) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            return
        }
        when (photoFlashMode) {
            AppConfig.PHOTO_FLASH_AUTO ->
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
            AppConfig.PHOTO_FLASH_ALWAYS ->
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
            else -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        // AE 带闪光模式时，闪光灯本身要允许触发；此时 FLASH_MODE 交给 AE 管
        if (photoFlashMode != AppConfig.PHOTO_FLASH_OFF) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
        }
    }

    private fun handleJpeg(reader: ImageReader) {
        // 用 acquireNextImage 而不是 acquireLatestImage：连拍时多张 JPEG 会同时
        // 排在 reader 队列里，取 latest 会把中间几张直接丢弃。
        // maxImages=2 保证队列不会无界增长，回调本身是串行投递到 handler 的。
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            onPhoto(bytes)
        } catch (e: Exception) {
            onError("读取照片失败：${e.message}")
        } finally {
            image.close()
        }
    }

    /** 视频/照片需要写入的角度（后置相机公式） */
    fun orientationHint(): Int {
        val deviceDegrees = rotationToDegrees(rotationProvider())
        return (spec.sensorOrientation - deviceDegrees + 360) % 360
    }

    fun close() {
        if (!released.compareAndSet(false, true)) return
        // 同步等待相机线程收完尾，避免 GL 资源先于相机会话释放导致回调野指针
        val latch = CountDownLatch(1)
        handler.post {
            streaming = false
            try {
                session?.stopRepeating()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            try {
                device?.close()
            } catch (_: Exception) {
            }
            device = null
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
            latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "BodyCamCam"

        /**
         * 枚举设备上所有可用摄像头（后摄在前，前摄在后）。
         * 同一朝向下按 id 升序，优先 id 小的主摄——大多数机型 "0" 是主摄。
         * 名字：后摄（唯一时）/ 后摄 1、后摄 2…（多个）。
         */
        fun listCameras(context: Context): List<CameraSpec> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                val ids = manager.cameraIdList
                val backs = mutableListOf<CameraSpec>()
                val fronts = mutableListOf<CameraSpec>()
                for (id in ids) {
                    val spec = buildSpec(manager, id) ?: continue
                    if (spec.isFront) fronts += spec else backs += spec
                }
                val labeled = mutableListOf<CameraSpec>()
                labeled += backs.mapIndexed { i, s ->
                    s.copy(label = if (backs.size > 1) "后摄 ${i + 1}" else "后摄")
                }
                labeled += fronts.mapIndexed { i, s ->
                    s.copy(label = if (fronts.size > 1) "前摄 ${i + 1}" else "前摄")
                }
                labeled
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun buildSpec(manager: CameraManager, id: String): CameraSpec? {
            return try {
                val characteristics = manager.getCameraCharacteristics(id)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return null
                val videoSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
                val videoSize = chooseVideoSize(videoSizes)
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                val photoSize = choosePhotoSize(jpegSizes, videoSize)
                val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                // 数字变焦上限：优先 API 30 的 CONTROL_ZOOM_RATIO_RANGE，
                // 老机型退到 SCALER_AVAILABLE_MAX_DIGITAL_ZOOM（通常 4~10）
                val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null
                val zoomMax = zoomRange?.upper?.takeIf { it > 1f }
                    ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?.takeIf { it > 1f }
                    ?: 1f
                CameraSpec(
                    cameraId = id,
                    videoSize = videoSize,
                    photoSize = photoSize,
                    sensorOrientation = sensor,
                    lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK,
                    flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                        ?: false,
                    activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?: Rect(),
                    zoomMax = zoomMax,
                    jpegSizes = jpegSizes.toList()
                )
            } catch (e: Exception) {
                null
            }
        }

        /** 优先 1080p，其次 16:9 且不超过 1080p 的最大尺寸 */
        private fun chooseVideoSize(sizes: Array<Size>): Size {
            sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
            val target = 1920 * 1080
            val sixteenNine = sizes.filter { it.width * 9 == it.height * 16 }
            val pool = if (sixteenNine.isNotEmpty()) sixteenNine else sizes.toList()
            return pool.filter { it.width.toLong() * it.height <= target }
                .maxByOrNull { it.width * it.height }
                ?: pool.minByOrNull { it.width * it.height }
                ?: sizes[0]
        }

        /** 拍照尺寸尽量跟视频同比例，避免预览/照片畸变 */
        private fun choosePhotoSize(sizes: Array<Size>, video: Size): Size {
            if (sizes.isEmpty()) return video
            val sameRatio = sizes.filter { it.width * video.height == it.height * video.width }
            val pool = if (sameRatio.isNotEmpty()) sameRatio else sizes.toList()
            val limit = 2560L * 1440L
            return pool.filter { it.width.toLong() * it.height <= limit }
                .maxByOrNull { it.width * it.height }
                ?: pool.maxByOrNull { it.width * it.height }
                ?: video
        }
    }
}

