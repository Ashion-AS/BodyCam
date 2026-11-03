package com.dsh.bodycam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dsh.bodycam.util.rotationToDegrees
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 探测到的相机能力：分辨率、传感器方向 */
data class CameraSpec(
    val cameraId: String,
    val videoSize: Size,
    val photoSize: Size,
    val sensorOrientation: Int
)

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

    /** 防止 open() 与 ensureOpen() 在相机真正打开前重复调用 openCamera */
    @Volatile
    private var opening = false

    private val released = AtomicBoolean(false)

    val isReady: Boolean get() = session != null
    val videoSize: Size get() = spec.videoSize

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
            imageReader = ImageReader.newInstance(
                spec.photoSize.width, spec.photoSize.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader -> handleJpeg(reader) }, handler)
            }
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
                builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientationHint())
                s.capture(builder.build(), null, handler)
            } catch (e: Exception) {
                onError("拍照失败：${e.message}")
            }
        }
    }

    private fun handleJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
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
        fun probe(context: Context): CameraSpec? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                val ids = manager.cameraIdList
                if (ids.isEmpty()) return null
                val backId = ids.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.first()

                val characteristics = manager.getCameraCharacteristics(backId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return null

                val videoSizes = map.getOutputSizes(SurfaceTexture::class.java)
                    ?: return null
                val videoSize = chooseVideoSize(videoSizes)
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                val photoSize = choosePhotoSize(jpegSizes, videoSize)
                val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                CameraSpec(backId, videoSize, photoSize, sensor)
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

