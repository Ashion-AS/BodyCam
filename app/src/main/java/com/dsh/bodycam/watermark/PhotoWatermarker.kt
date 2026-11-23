package com.dsh.bodycam.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.dsh.bodycam.core.AppConfig
import java.io.ByteArrayOutputStream

/**
 * 给相机直出的 JPEG 烧水印。
 *
 * 方向处理不依赖 EXIF：不同机型对 `JPEG_ORIENTATION` 的实现不一致，
 * 有的只写 EXIF 标签，有的直接把像素转正。这里用「应用算出的旋转角 +
 * 解码后的长宽」交叉判断，两种实现都能得到正向照片：
 *
 *   - 旋转 90/270：解码后仍是横向像素才需要转（说明设备没转、只写了标签）
 *   - 旋转 180  ：直接转
 *   - 旋转 0    ：不动
 *
 * 转正后按与视频相同的比例叠水印，再重新编码成 JPEG。
 * 不保留原 EXIF —— 像素已经真正转正，留着标签会被播放器再转一次。
 */
object PhotoWatermarker {

    /** 超过这个边长就降采样，控制内存峰值 */
    private const val MAX_DIMENSION = 4096
    private const val JPEG_QUALITY = 92

    /** 照片在相机线程调用：水印内容由调用方准备好，这里不碰 UI 也不读设置 */
    fun watermark(
        jpeg: ByteArray,
        painter: WatermarkPainter,
        data: WatermarkData,
        fields: Map<WatermarkCorner, WatermarkField>,
        rotationDeg: Int
    ): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return jpeg

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2

        val src = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return jpeg

        val needRotate = when (rotationDeg) {
            90, 270 -> src.width > src.height
            180 -> true
            else -> false
        }
        val swap = needRotate && (rotationDeg == 90 || rotationDeg == 270)
        val outWidth = if (swap) src.height else src.width
        val outHeight = if (swap) src.width else src.height

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (needRotate) {
            canvas.drawBitmap(src, rotationMatrix(rotationDeg, src.width, src.height), paint)
        } else {
            canvas.drawBitmap(src, 0f, 0f, paint)
        }
        src.recycle()

        // 四角各贴一块，位置/字号与视频同一套比例（共用同一字号）
        val marginX = outWidth * AppConfig.WATERMARK_MARGIN
        // 纵向边距按像素对齐横向边距，竖拍时四边留白才一致
        val marginY = marginX
        val bitmaps = painter.renderAll(data, fields, outWidth)
        for (corner in WatermarkCorner.entries) {
            val wm = bitmaps[corner] ?: continue
            val width = wm.width.toFloat()
            val height = width * wm.height / wm.width
            val left = when (corner) {
                WatermarkCorner.TOP_LEFT, WatermarkCorner.BOTTOM_LEFT -> marginX
                WatermarkCorner.TOP_RIGHT, WatermarkCorner.BOTTOM_RIGHT ->
                    outWidth - marginX - width
            }
            val top = when (corner) {
                WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_RIGHT -> marginY
                WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_RIGHT ->
                    outHeight - marginY - height
            }
            val dst = RectF(left, top, left + width, top + height)
            canvas.drawBitmap(wm, null, dst, paint)
        }

        val bos = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        output.recycle()
        return bos.toByteArray()
    }

    /** 顺时针旋转 deg 度，目标画布使用交换后的宽高 */
    private fun rotationMatrix(deg: Int, w: Int, h: Int): Matrix {
        val matrix = Matrix()
        when (deg) {
            90 -> {
                matrix.setRotate(90f)
                matrix.postTranslate(h.toFloat(), 0f)
            }
            180 -> {
                matrix.setRotate(180f)
                matrix.postTranslate(w.toFloat(), h.toFloat())
            }
            270 -> {
                matrix.setRotate(-90f)
                matrix.postTranslate(0f, w.toFloat())
            }
        }
        return matrix
    }
}
