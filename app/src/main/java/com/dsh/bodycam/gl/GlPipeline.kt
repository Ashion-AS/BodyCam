package com.dsh.bodycam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * 对 [WatermarkEngine] 的一层 Kotlin 包装，隐藏 handle 的生命周期。
 *
 * 典型用法：构造 → 拿 [cameraTextureId] 创建 SurfaceTexture 给相机 →
 * 把预览 Surface 与编码器 Surface 交给它 → release()。
 */
class GlPipeline(val videoWidth: Int, val videoHeight: Int) {

    private var handle: Long = WatermarkEngine.nativeCreate(videoWidth, videoHeight)

    val available: Boolean get() = handle != 0L

    /** 摄像头输出用的 OES 纹理 id，用于 new SurfaceTexture(id) */
    val cameraTextureId: Int
        get() = if (available) WatermarkEngine.nativeCameraTexture(handle) else 0

    /** 当前总变换下画面是竖幅（短边 × 长边）还是横幅，用于动态决定输出画幅 */
    val isUprightPortrait: Boolean
        get() = available && WatermarkEngine.nativeIsPortrait(handle)

    fun attachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        if (available) WatermarkEngine.nativeAttachSurfaceTexture(handle, surfaceTexture)
    }

    /** surface 为 null 表示预览窗口销毁 */
    fun setPreviewWindow(surface: Surface?, viewWidth: Int, viewHeight: Int) {
        if (available) WatermarkEngine.nativeSetPreviewWindow(handle, surface, viewWidth, viewHeight)
    }

    fun updatePreviewSize(viewWidth: Int, viewHeight: Int) {
        if (available) WatermarkEngine.nativeUpdatePreviewSize(handle, viewWidth, viewHeight)
    }

    fun setPreviewVisible(visible: Boolean) {
        if (available) WatermarkEngine.nativeSetPreviewVisible(handle, visible)
    }

    fun setVideoSize(width: Int, height: Int) {
        if (available) WatermarkEngine.nativeSetVideoSize(handle, width, height)
    }

    fun setEncoderWindow(surface: Surface) {
        if (available) WatermarkEngine.nativeSetEncoderWindow(handle, surface)
    }

    fun clearEncoderWindow() {
        if (available) WatermarkEngine.nativeClearEncoderWindow(handle)
    }

    /**
     * 预录缓存编码器：传非 null surface 时，每帧同时画到它上面（与正式
     * 录像编码器互斥——录像开始时主页面会先停掉预录缓存再传 null）。
     */
    fun setPreRecordSurface(surface: Surface?) {
        if (available) WatermarkEngine.nativeSetPreRecordSurface(handle, surface)
    }

    /**
     * 该调用是同步的：返回后渲染线程已经不再往编码器画帧，
     * 可以安全地调用 MediaRecorder.stop()。
     */
    fun setRecording(recording: Boolean) {
        if (available) WatermarkEngine.nativeSetRecording(handle, recording)
    }

    /**
     * 更新一块水印（四角各一个 slot）。
     *
     * @param anchorLeft true = leftFrac 是左边界；false = leftFrac 是右边界
     * @param anchorBottom true = bottomFrac 是下边界；false = bottomFrac 是上边界
     */
    fun setWatermark(
        slot: Int,
        bitmap: Bitmap,
        leftFrac: Float,
        bottomFrac: Float,
        widthFrac: Float,
        anchorLeft: Boolean,
        anchorBottom: Boolean
    ) {
        if (available) {
            WatermarkEngine.nativeSetWatermark(
                handle, slot, bitmap, leftFrac, bottomFrac, widthFrac, anchorLeft, anchorBottom
            )
        }
    }

    /** 清空某一块水印（该角没有内容） */
    fun clearWatermark(slot: Int) {
        if (available) WatermarkEngine.nativeClearWatermark(handle, slot)
    }

    /** 画面方向（0/90/180/270）：只转纹理坐标，画幅尺寸由调用方同步交换 */
    fun setRotation(degrees: Float) {
        if (available) WatermarkEngine.nativeSetRotation(handle, degrees)
    }

    fun onFrameAvailable() {
        if (available) WatermarkEngine.nativeOnFrameAvailable(handle)
    }

    fun release() {
        if (handle != 0L) {
            WatermarkEngine.nativeRelease(handle)
            handle = 0L
        }
    }
}
