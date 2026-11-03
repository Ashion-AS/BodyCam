package com.dsh.bodycam.util

/**
 * 把 `Surface.ROTATION_*` 换成角度。
 *
 * 相机侧（JPEG_ORIENTATION / 方向公式）和渲染侧（预览旋转）都要做这个映射，
 * 之前各写了一份 `when`，加一种方向就得改两处。
 */
fun rotationToDegrees(rotation: Int): Int = when (rotation) {
    android.view.Surface.ROTATION_90 -> 90
    android.view.Surface.ROTATION_180 -> 180
    android.view.Surface.ROTATION_270 -> 270
    else -> 0
}
