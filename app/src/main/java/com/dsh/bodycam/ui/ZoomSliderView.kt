package com.dsh.bodycam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.dsh.bodycam.R

/**
 * 竖向缩放条：拖动改变相机数字变焦倍率，上端是最大倍率、下端是 1x。
 *
 * 交互按「拖到哪就是哪」实现（不是相对滑动），手指按住即跳到对应倍率，
 * 执法场景下能一步到位；松手后条本身不弹回，倍率保持。
 *
 * 视觉分层，两者透明度不同：
 *  - 形状层（轨道 / 填充 / 把手）压到约 75%，存在感低一点、不压画面；
 *  - 文字层**不跟着淡**：倍率读数要一直看得清，字号走令牌 text_md + 加粗 + 实投影。
 * 颜色仍然全部取自设计令牌，不写死任何十六进制。
 */
class ZoomSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 倍率变化回调；调用方负责把值送进 CameraController */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** 当前最大倍率；<= 1 时控件自行隐藏（该摄像头不支持缩放） */
    var maxRatio: Float = 1f
        set(value) {
            field = value.coerceAtLeast(1f)
            ratio = ratio.coerceIn(1f, field)
            visibility = if (field > 1f) VISIBLE else GONE
            invalidate()
        }

    /** 当前倍率，1..maxRatio */
    var ratio: Float = 1f
        private set

    /**
     * 换摄像头时按新能力重置：上限更新、倍率回到 1x，控件随上限显隐。
     * 摄像头不支持缩放（max <= 1）时隐藏。
     */
    fun setZoomRange(max: Float) {
        maxRatio = max.coerceAtLeast(1f)
        ratio = 1f
        invalidate()
    }

    private val density = resources.displayMetrics.density

    /** 形状层透明度（约 75%）：条子不该比画面更抢眼 */
    private val shapeAlpha = 191

    /** 把手略实一点（约 85%），手指仍能一眼找到 */
    private val thumbAlpha = 217

    private val trackWidth = 4f * density
    private val thumbRadius = 9f * density
    private val tickRadius = 1.5f * density

    /** 倍率读数走字号令牌：当前值与上限同档，都是 text_md */
    private val labelSize = resources.getDimension(R.dimen.text_md)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.rule_2)
        alpha = shapeAlpha
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        alpha = shapeAlpha
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        alpha = thumbAlpha
    }

    private val thumbHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.paper_3)
        style = Paint.Style.STROKE
        alpha = thumbAlpha
    }

    /**
     * 倍率读数（当前值 / 上限共用同一套字形与字号）。
     * 文字层**不跟着形状层变淡**：
     * 它是这块控件唯一要读的信息，加粗 + 实投影保证压在亮画面上也清楚。
     */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = labelSize
        // 用 text_glow（比普通叠字投影更实），字紧邻一圈压到 near-black
        setShadowLayer(5f * density, 0f, 1f * density, context.getColor(R.color.text_glow))
    }

    /** 上限读数：只压一档颜色弱化，字号与当前值一致 */
    private val maxLabelPaint = Paint(labelPaint).apply {
        color = context.getColor(R.color.ink_2)
    }

    /** 轨道可用的上下边界（含把手半径的内缩，避免把手画出控件外） */
    private val trackRect = RectF()

    /** onDraw 里复用，避免每帧新建对象（lint: DrawAllocation） */
    private val segRect = RectF()

    /** 轨道上方留给「最大倍率」文字的高度（含把手外沿） */
    private val labelTopSpace = labelSize + thumbRadius + 4f * density

    /** 轨道下方留给「当前倍率」文字的高度（含把手外沿） */
    private val labelBottomSpace = labelSize + thumbRadius + 6f * density

    init {
        thumbHaloPaint.strokeWidth = 2f * density
        // 上下留白按字号现算，保证换字号后顶部/底部读数都不会被裁掉
        setPadding(
            (thumbRadius + 2 * density).toInt(),
            labelTopSpace.toInt(),
            (thumbRadius + 2 * density).toInt(),
            labelBottomSpace.toInt()
        )
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = paddingLeft + (w - paddingLeft - paddingRight) / 2f
        val top = paddingTop + thumbRadius
        val bottom = h - paddingBottom - thumbRadius
        trackRect.set(cx - trackWidth / 2f, top, cx + trackWidth / 2f, bottom)
    }

    /** 手势起点到底端（=1x）的距离比例换算成倍率 */
    private fun ratioForY(y: Float): Float {
        val top = trackRect.top
        val bottom = trackRect.bottom
        if (bottom <= top) return 1f
        val t = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        // 线性映射到 1..max：底端 1x、顶端最大倍率
        return 1f + t * (maxRatio - 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (maxRatio <= 1f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // 手势落在整条上，靠近条就接管，扩大命中区（缩放条本身很窄）
                parent?.requestDisallowInterceptTouchEvent(true)
                val next = ratioForY(event.y)
                if (next != ratio) {
                    ratio = next
                    invalidate()
                    onZoomChanged?.invoke(ratio)
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxRatio <= 1f || trackRect.height() <= 0f) return

        val cx = trackRect.centerX()
        val top = trackRect.top
        val bottom = trackRect.bottom

        // 轨道：整条 muted 细线
        val halfTrack = trackWidth / 2f
        segRect.set(cx - halfTrack, top, cx + halfTrack, bottom)
        canvas.drawRoundRect(segRect, halfTrack, halfTrack, trackPaint)

        // 已填充段：从底部（1x）一直到当前倍率
        val thumbY = bottom - (ratio - 1f) / (maxRatio - 1f) * (bottom - top)
        segRect.set(cx - halfTrack, thumbY, cx + halfTrack, bottom)
        canvas.drawRoundRect(segRect, halfTrack, halfTrack, fillPaint)

        // 刻度：1x / 中间档 / 最大倍率各一个小点，给手指定位参考
        for (i in 0..2) {
            val ty = bottom - i / 2f * (bottom - top)
            canvas.drawCircle(cx, ty, tickRadius, trackPaint)
        }

        // 把手：实心圆 + 一圈深底描边，压在亮画面上也能看清
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbPaint)
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbHaloPaint)

        // 底部当前倍率，顶部最大倍率（基线按各自的留白摆放，换字号不会被裁）
        canvas.drawText(formatRatio(ratio), cx, height - 4f * density, labelPaint)
        canvas.drawText(
            formatRatio(maxRatio), cx,
            top - thumbRadius - 2f * density, maxLabelPaint
        )
    }

    /** 1.0x / 2.5x；整数倍不留小数尾巴 */
    private fun formatRatio(value: Float): String {
        val tenths = (value * 10f).toInt()
        return if (tenths % 10 == 0) "${tenths / 10}x" else "${tenths / 10}.${tenths % 10}x"
    }
}