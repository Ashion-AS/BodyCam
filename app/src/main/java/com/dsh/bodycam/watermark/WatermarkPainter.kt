package com.dsh.bodycam.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.dsh.bodycam.core.AppConfig

/**
 * 把水印内容画成位图：一个角一张、内容由 [WatermarkField] 决定。
 *
 * 为什么每个角独立一张：四角各自定位，原生侧只要分别贴一块纹理，
 * 不用在 GL 里算「大图里哪一块该露出来」；没有内容的角直接返回 null，少贴一次。
 *
 * 位图按角缓存：水印每 500ms 重画一次，共用一张图会让四个角在四种尺寸间
 * 反复轮换、等于每帧重建 Bitmap，所以每个角各留一张。
 *
 * 两个使用方：
 *  - 视频：每 500ms 重画一次，交给 OpenGL 贴到画面四角；
 *  - 照片：按照片宽度画更大的一套，再用 Canvas 叠加到 JPEG 上。
 *
 * 注意：**不是线程安全的**（内部复用 Bitmap 与 Paint），视频链路和照片链路
 * 各持有自己的实例。
 */
class WatermarkPainter {

    /** 每个角一份画布缓存，索引即 WatermarkCorner.ordinal */
    private val slots = Array(WatermarkCorner.entries.size) { Slot() }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /**
     * 四个角一起出图，**共用同一个字号**。
     *
     * 同一行（上排 / 下排）的左右两块加起来不能超过画面宽度，超了就整体等比缩小字号。
     * 若按角各自约束，会出现「经纬度被缩小、录像状态没缩」这种字号不一致，
     * 所以把约束提到整幅画面级别：要么都大，要么都小。
     *
     * @param fields 角 → 内容类型；缺省视为 [WatermarkField.NONE]
     * @return 角 → 位图；该角无内容时为 null
     */
    fun renderAll(
        data: WatermarkData,
        fields: Map<WatermarkCorner, WatermarkField>,
        frameWidth: Int
    ): Map<WatermarkCorner, Bitmap?> {
        val frame = frameWidth.coerceIn(320, 8192).toFloat()
        val margin = frame * AppConfig.WATERMARK_MARGIN
        val linesByCorner = WatermarkCorner.entries.associateWith { corner ->
            linesFor(data, fields[corner] ?: WatermarkField.NONE)
        }

        var textSize = frame * TEXT_SIZE_RATIO
        textPaint.textSize = textSize
        val textWidths = WatermarkCorner.entries.associateWith { corner ->
            linesByCorner.getValue(corner).maxOfOrNull { textPaint.measureText(it) } ?: 0f
        }

        // 一行里左右两块 + 两侧边距的总需求
        fun rowNeed(left: WatermarkCorner, right: WatermarkCorner): Float {
            var need = 0f
            var any = false
            for (corner in listOf(left, right)) {
                val w = textWidths.getValue(corner)
                if (w <= 0f) continue
                any = true
                need += w + textSize * PAD_X_RATIO * 2f
            }
            return if (any) need + margin * 2f else 0f
        }

        val widestRow = maxOf(
            rowNeed(WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_RIGHT),
            rowNeed(WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_RIGHT)
        )
        if (widestRow > frame) {
            // 文本宽度与字号近似成正比，按比例缩一次；留 1% 余量吸收测量误差
            textSize *= frame / widestRow * 0.99f
            textPaint.textSize = textSize
        }

        return WatermarkCorner.entries.associateWith { corner ->
            draw(data, fields[corner] ?: WatermarkField.NONE, corner, textSize)
        }
    }

    private fun draw(
        data: WatermarkData,
        field: WatermarkField,
        corner: WatermarkCorner,
        textSize: Float
    ): Bitmap? {
        val lines = linesFor(data, field)
        if (lines.isEmpty()) return null

        textPaint.textSize = textSize
        textPaint.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.03f, Color.argb(170, 0, 0, 0))

        val padX = textSize * PAD_X_RATIO
        val padY = textSize * 0.16f
        val lineGap = textSize * 0.26f
        val lineH = textSize * 1.18f

        // 右侧两角文本右对齐，位图右边缘就是画面右边缘。
        // 必须用 Align.RIGHT —— drawText 的 x 是「对齐基准点」，左对齐时它是文字左边缘，
        // 只有右对齐时才是右边缘；早先用左对齐传「右边界」，整段文字被画到位图外裁掉了。
        val alignRight = corner == WatermarkCorner.TOP_RIGHT ||
            corner == WatermarkCorner.BOTTOM_RIGHT
        textPaint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT

        val textW = lines.maxOf { textPaint.measureText(it) }
        val needW = textW + padX * 2f
        val height = (padY * 2f + lineH * lines.size + lineGap * (lines.size - 1)).toInt()

        val slot = slots[corner.ordinal]
        val bmp = slot.ensure(needW.toInt(), height)
        val c = slot.canvas ?: return null
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        var baseline = padY + textSize * 0.86f
        for (line in lines) {
            val x = if (alignRight) bmp.width - padX else padX
            c.drawText(line, x, baseline, textPaint)
            baseline += lineH + lineGap
        }
        return bmp
    }

    /** 某一类内容要画哪几行字 */
    private fun linesFor(data: WatermarkData, field: WatermarkField): List<String> =
        when (field) {
            WatermarkField.GPS -> listOfNotNull(data.gpsText.takeIf { it.isNotBlank() })
            WatermarkField.TIME ->
                listOfNotNull("${data.dateText}  ${data.timeText}".takeIf { it.isNotBlank() })
            WatermarkField.DEVICE_STATUS -> listOfNotNull(
                data.deviceId.takeIf { it.isNotBlank() }?.let { "SN: $it" },
                data.statusText.takeIf { it.isNotBlank() }
            )
            WatermarkField.GROUP_USER -> listOfNotNull(
                data.groupName.takeIf { it.isNotBlank() },
                data.userName.takeIf { it.isNotBlank() }
            )
            WatermarkField.NONE -> emptyList()
        }

    /**
     * 一个角的画布缓存。
     *
     * 宽度用实测值（不取整）：右侧两角是「位图右边缘贴画面右边」，
     * 多余的透明边会把文字往画面中间推，破坏四角的对称留白。
     * 数字在系统字体里宽度一致（等宽数字），所以时间这类每秒变化的文本
     * 实测宽度也是稳定的，实际不会反复重建。
     */
    private class Slot {
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        private var cachedW = 0
        private var cachedH = 0

        fun ensure(width: Int, height: Int): Bitmap {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val existing = bitmap
            if (existing != null && !existing.isRecycled && cachedW == w && cachedH == h) {
                return existing
            }
            existing?.recycle()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            canvas = Canvas(bmp)
            cachedW = w
            cachedH = h
            return bmp
        }
    }

    private companion object {
        /**
         * 字号占画幅宽度的比例。四个角共用这一个字号。
         *
         * 3.4% 是实机观感折中的结果：再大四角内容会顶到画面中间、显得喧哗，
         * 再小则在 720P 成片上不易辨认。
         * 这是「首选的」字号，不是硬上限：同一行左右两块放不下时 [renderAll]
         * 会按比例整体缩小，四个角始终保持同一字号。
         */
        const val TEXT_SIZE_RATIO = 0.034f

        /** 单侧水平内边距，占字号的比例 */
        const val PAD_X_RATIO = 0.36f
    }
}