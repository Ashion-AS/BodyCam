package com.dsh.bodycam.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import com.dsh.bodycam.core.AppConfig

/**
 * 把「时间 / GPS / 设备号 / 录制状态」画成一张位图。
 *
 * 两个使用方：
 *  - 视频：每秒重画一次，交给 OpenGL 贴到画面左下角；
 *  - 照片：按照片宽度画一张更大的，再用 Canvas 叠加到 JPEG 上。
 *
 * 所有尺寸都按 [render] 传入的 targetWidth 等比计算，所以两种场景共用同一套样式。
 * 注意：**不是线程安全的**（内部复用 Bitmap 与 Paint），视频链路和照片链路各用各的实例。
 */
class WatermarkPainter {

    private var slot: Slot? = null

    fun render(data: WatermarkData, targetWidth: Int): Bitmap {
        val width = targetWidth.coerceIn(320, 8192)
        var current = slot
        if (current == null || current.width != width) {
            current = Slot(width)
            slot = current
        }
        current.draw(data)
        return current.bitmap
    }

    private class Slot(val width: Int) {
        val padX = width * 0.030f
        val padY = width * 0.024f
        val barWidth = width * 0.010f
        val textGap = width * 0.016f
        val titleSize = width * 0.060f
        val subSize = width * 0.033f

        val height = (padY * 2 + titleSize + textGap + subSize * 1.32f * 2)
            .toInt().coerceAtLeast(64)

        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 6, 10, 16)
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 57, 53)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (width * 0.003f).coerceAtLeast(1f)
            color = Color.argb(70, 255, 255, 255)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.argb(150, 0, 0, 0))
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 234, 245)
            textSize = subSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(2f, 0f, 1f, Color.argb(150, 0, 0, 0))
        }

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = width * 0.014f

        fun draw(data: WatermarkData) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            canvas.drawRect(
                padX * 0.55f + barWidth * 0.5f,
                padY * 0.75f,
                padX * 0.55f + barWidth * 1.5f,
                height - padY * 0.75f,
                barPaint
            )

            val textX = padX * 0.55f + barWidth * 2.2f
            var baseline = padY + titleSize * 0.86f
            canvas.drawText("${data.dateText}  ${data.timeText}", textX, baseline, titlePaint)

            baseline += textGap + subSize * 1.05f
            canvas.drawText(data.gpsText, textX, baseline, subPaint)

            baseline += subSize * 1.32f
            canvas.drawText("${data.statusText}   ${data.deviceId}", textX, baseline, subPaint)
        }
    }
}
