package com.dsh.bodycam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * JNI 门面：方法名与 cpp/jni_bridge.cpp 中的符号一一对应。
 * 改签名时两边必须同时改，否则会抛 UnsatisfiedLinkError。
 */
object WatermarkEngine {
    init {
        System.loadLibrary("bodycam")
    }

    external fun nativeCreate(videoWidth: Int, videoHeight: Int): Long
    external fun nativeCameraTexture(handle: Long): Int
    external fun nativeAttachSurfaceTexture(handle: Long, surfaceTexture: SurfaceTexture): Boolean
    external fun nativeSetPreviewWindow(handle: Long, surface: Surface?, viewWidth: Int, viewHeight: Int): Boolean
    external fun nativeUpdatePreviewSize(handle: Long, viewWidth: Int, viewHeight: Int)
    external fun nativeSetPreviewVisible(handle: Long, visible: Boolean)
    external fun nativeSetVideoSize(handle: Long, width: Int, height: Int)
    external fun nativeSetEncoderWindow(handle: Long, surface: Surface): Boolean
    external fun nativeClearEncoderWindow(handle: Long)
    external fun nativeSetRecording(handle: Long, recording: Boolean)
    external fun nativeSetWatermark(
        handle: Long,
        bitmap: Bitmap,
        leftFrac: Float,
        bottomFrac: Float,
        widthFrac: Float
    )
    external fun nativeSetRotation(handle: Long, degrees: Float)
    external fun nativeOnFrameAvailable(handle: Long)
    external fun nativeRelease(handle: Long)
}
