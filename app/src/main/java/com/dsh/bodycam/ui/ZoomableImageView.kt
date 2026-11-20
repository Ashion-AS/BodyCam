package com.dsh.bodycam.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * 看图用的 ImageView：双指缩放、拖动、双击在「适应屏幕 / 2 倍」间切换。
 *
 * 只维护一个 [Matrix]；图片切换时（设完 drawable 后调 [reset]）回到适应屏幕。
 * 缩放下限就是适应屏幕的比例，不允许缩到比屏幕还小，避免拖出一片空白。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 4f

    /** reset() 因视图还没量过而没算成时置位，等 onSizeChanged 补一次 */
    private var pendingReset = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = target / currentScale
                if (factor == 1f) return true
                currentScale = target
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (!scaleDetector.isInProgress) {
                    matrix.postTranslate(-dx, -dy)
                    imageMatrix = matrix
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale > minScale * 1.05f) minScale else minScale * 2f
                val factor = target / currentScale
                currentScale = target
                matrix.postScale(factor, factor, e.x, e.y)
                imageMatrix = matrix
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    /** 新图片设进来后调用：按「适应屏幕」重算基础比例 */
    fun reset() {
        val d: Drawable = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) {
            pendingReset = true
            return
        }
        pendingReset = false
        val base = minOf(vw / dw, vh / dh)
        minScale = base
        currentScale = base
        maxScale = base * 4f
        matrix.reset()
        matrix.postScale(base, base)
        matrix.postTranslate((vw - dw * base) / 2f, (vh - dh * base) / 2f)
        imageMatrix = matrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingReset) reset()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}