package com.dsh.bodycam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.dsh.bodycam.R

/**
 * 滚动式声音波形。
 *
 * 维护一组从旧到新的电平（0~1），[pushLevel] 进来一格就往左挤、尾部补新值。
 * 柱子以中线为对称轴上下镜像，静音时退化成一条基线。
 *
 * 颜色全部取自设计令牌：柱子用 accent（通用交互色，这里表示「有声」），
 * 基线用 rule_2。不写死任何十六进制。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 横向柱子数；越多越细，滚动越慢 */
        private const val COLUMNS = 56
    }

    private val levels = FloatArray(COLUMNS)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
        color = context.getColor(R.color.rule_2)
    }

    init {
        // 柱子两头是圆帽，描边宽度即柱宽；随控件高度缩放，保持观感一致
        barPaint.strokeWidth = resources.displayMetrics.density * 3f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 柱宽随格宽自适应：控件越宽，柱子越粗，但不超过格宽一半（onDraw 里再兜一次底）
        val plotW = (w - paddingLeft - paddingRight).toFloat()
        if (plotW > 0) {
            val step = plotW / COLUMNS
            barPaint.strokeWidth = (step * 0.32f).coerceIn(1f, resources.displayMetrics.density * 8f)
        }
    }

    /** 追加一格电平（0~1）。调用方负责切到主线程 */
    fun pushLevel(level: Float) {
        System.arraycopy(levels, 1, levels, 0, COLUMNS - 1)
        levels[COLUMNS - 1] = level.coerceIn(0f, 1f)
        invalidate()
    }

    /** 清空波形，回到静音基线 */
    fun reset() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        val midY = (top + bottom) / 2f
        canvas.drawLine(left, midY, right, midY, baselinePaint)

        val plotW = right - left
        val plotH = bottom - top
        val step = plotW / COLUMNS
        val maxBar = plotH / 2f * 0.92f
        // 柱宽不超过格宽的一半，避免相邻柱子粘连
        barPaint.strokeWidth = minOf(barPaint.strokeWidth, step * 0.5f).coerceAtLeast(1f)

        val minLen = barPaint.strokeWidth
        for (i in 0 until COLUMNS) {
            val level = levels[i]
            // 有线电平也留一个最小可见高度，画面不会「断」成空白
            val len = (level * maxBar).coerceAtLeast(if (level > 0.001f) minLen else 0f)
            if (len <= 0f) continue
            val x = left + step * i + step / 2f
            canvas.drawLine(x, midY - len, x, midY + len, barPaint)
        }
    }
}