package com.docscan.util.textedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Text Removal & Inpainting Engine. Completely eliminates original document text
 * underneath edited/deleted regions by sampling surrounding background paper texture and gradients.
 */
object DocumentTextInpainter {

    /**
     * Wipes multiple text regions on a copy of the source bitmap.
     */
    suspend fun inpaintRegions(
        source: Bitmap,
        regions: List<RectF>
    ): Bitmap = withContext(Dispatchers.Default) {
        if (regions.isEmpty()) return@withContext source

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()

        for (normRect in regions) {
            inpaintSingleRegionOnCanvas(output, canvas, normRect, w, h)
        }

        output
    }

    /**
     * Wipes a single text region on a copy of the source bitmap.
     */
    suspend fun inpaintRegion(
        source: Bitmap,
        normRect: RectF
    ): Bitmap = withContext(Dispatchers.Default) {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val w = output.width.toFloat()
        val h = output.height.toFloat()
        inpaintSingleRegionOnCanvas(output, canvas, normRect, w, h)
        output
    }

    private fun inpaintSingleRegionOnCanvas(
        bitmap: Bitmap,
        canvas: Canvas,
        normRect: RectF,
        bitmapW: Float,
        bitmapH: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height

        val left = (normRect.left * bitmapW).toInt().coerceIn(0, w - 1)
        val top = (normRect.top * bitmapH).toInt().coerceIn(0, h - 1)
        val right = (normRect.right * bitmapW).toInt().coerceIn(left + 1, w)
        val bottom = (normRect.bottom * bitmapH).toInt().coerceIn(top + 1, h)

        val boxW = right - left
        val boxH = bottom - top

        // Sample perimeter colors around the box (top, bottom, left, right)
        val sampleOffset = (min(boxW, boxH) * 0.2f).toInt().coerceIn(2, 16)
        val topSampleY = (top - sampleOffset).coerceIn(0, h - 1)
        val bottomSampleY = (bottom + sampleOffset).coerceIn(0, h - 1)
        val leftSampleX = (left - sampleOffset).coerceIn(0, w - 1)
        val rightSampleX = (right + sampleOffset).coerceIn(0, w - 1)

        val stepX = max(1, boxW / 12)
        val stepY = max(1, boxH / 6)
        val perimeterPixels = mutableListOf<Int>()

        for (x in left until right step stepX) {
            perimeterPixels.add(bitmap.getPixel(x, topSampleY))
            perimeterPixels.add(bitmap.getPixel(x, bottomSampleY))
        }
        for (y in top until bottom step stepY) {
            perimeterPixels.add(bitmap.getPixel(leftSampleX, y))
            perimeterPixels.add(bitmap.getPixel(rightSampleX, y))
        }

        // Separate light and dark clusters to reject text ink
        val sortedByLum = perimeterPixels.sortedByDescending { pixel ->
            val r = AndroidColor.red(pixel)
            val g = AndroidColor.green(pixel)
            val b = AndroidColor.blue(pixel)
            0.299 * r + 0.587 * g + 0.114 * b
        }

        // Paper color is usually the majority higher-luminance cluster for light paper
        val validSampleCount = max(1, (sortedByLum.size * 0.75f).toInt())
        var totalR = 0L; var totalG = 0L; var totalB = 0L
        for (i in 0 until validSampleCount) {
            val p = sortedByLum[i]
            totalR += AndroidColor.red(p)
            totalG += AndroidColor.green(p)
            totalB += AndroidColor.blue(p)
        }

        val avgR = (totalR / validSampleCount).toInt().coerceIn(0, 255)
        val avgG = (totalG / validSampleCount).toInt().coerceIn(0, 255)
        val avgB = (totalB / validSampleCount).toInt().coerceIn(0, 255)
        val paperColor = AndroidColor.rgb(avgR, avgG, avgB)

        // Expand boundary slightly by 1-2 pixels to absorb anti-aliased font edges
        val marginX = (bitmapW * 0.003f).coerceAtLeast(1.5f)
        val marginY = (bitmapH * 0.003f).coerceAtLeast(1.5f)

        val cleanRect = RectF(
            (left.toFloat() - marginX).coerceAtLeast(0f),
            (top.toFloat() - marginY).coerceAtLeast(0f),
            (right.toFloat() + marginX).coerceAtMost(bitmapW),
            (bottom.toFloat() + marginY).coerceAtMost(bitmapH)
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = paperColor
        }

        canvas.drawRect(cleanRect, paint)
    }
}
