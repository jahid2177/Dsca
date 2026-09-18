package com.docscan.util.textedit

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Heuristics-based text styling estimator. Analyzes pixel distributions in and around
 * bounding boxes to estimate background paper color, ink text color, font size, and stroke weight.
 */
object TextStyleEstimator {

    data class EstimatedStyle(
        val textColor: Color,
        val backgroundColor: Color,
        val fontSizeSp: Float,
        val fontStyle: FontStyleEstimate
    )

    /**
     * Estimates text styling parameters directly from the source bitmap and detected bounding box.
     */
    fun estimate(
        bitmap: Bitmap,
        pixelRect: Rect,
        documentRefWidth: Int = bitmap.width
    ): EstimatedStyle {
        val w = bitmap.width
        val h = bitmap.height

        val left = pixelRect.left.coerceIn(0, w - 1)
        val top = pixelRect.top.coerceIn(0, h - 1)
        val right = pixelRect.right.coerceIn(left + 1, w)
        val bottom = pixelRect.bottom.coerceIn(top + 1, h)

        val boxW = right - left
        val boxH = bottom - top

        // 1. Sample Background Color from perimeter ring (outside box to avoid text ink)
        val perimeterColors = mutableListOf<Int>()
        val marginX = (boxW * 0.15f).toInt().coerceIn(2, 20)
        val marginY = (boxH * 0.25f).toInt().coerceIn(2, 20)

        val sampleTop = (top - marginY).coerceIn(0, h - 1)
        val sampleBottom = (bottom + marginY).coerceIn(0, h - 1)
        val sampleLeft = (left - marginX).coerceIn(0, w - 1)
        val sampleRight = (right + marginX).coerceIn(0, w - 1)

        val stepX = max(1, (sampleRight - sampleLeft) / 16)
        val stepY = max(1, (sampleBottom - sampleTop) / 8)

        // Top and bottom edges
        for (x in sampleLeft..sampleRight step stepX) {
            perimeterColors.add(bitmap.getPixel(x, sampleTop))
            perimeterColors.add(bitmap.getPixel(x, sampleBottom))
        }
        // Left and right edges
        for (y in sampleTop..sampleBottom step stepY) {
            perimeterColors.add(bitmap.getPixel(sampleLeft, y))
            perimeterColors.add(bitmap.getPixel(sampleRight, y))
        }

        // Calculate average background (ignoring outliers)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var bgCount = 0
        for (pixel in perimeterColors) {
            val r = AndroidColor.red(pixel)
            val g = AndroidColor.green(pixel)
            val b = AndroidColor.blue(pixel)
            // Filter out accidentally sampled dark ink
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            if (lum > 80) {
                sumR += r
                sumG += g
                sumB += b
                bgCount++
            }
        }

        val estimatedBg = if (bgCount > 0) {
            Color(
                (sumR / bgCount).toInt().coerceIn(0, 255),
                (sumG / bgCount).toInt().coerceIn(0, 255),
                (sumB / bgCount).toInt().coerceIn(0, 255)
            )
        } else {
            Color.White
        }

        // 2. Sample Text Ink Color (darkest pixels inside bounding box)
        val insidePixels = mutableListOf<Int>()
        val inStepX = max(1, boxW / 24)
        val inStepY = max(1, boxH / 12)

        for (x in left until right step inStepX) {
            for (y in top until bottom step inStepY) {
                insidePixels.add(bitmap.getPixel(x, y))
            }
        }

        // Sort pixels by luminance to find ink cluster
        val sortedByLuminance = insidePixels.sortedBy { pixel ->
            val r = AndroidColor.red(pixel)
            val g = AndroidColor.green(pixel)
            val b = AndroidColor.blue(pixel)
            0.299 * r + 0.587 * g + 0.114 * b
        }

        // Take the darkest 20% pixels
        val inkSampleCount = max(1, (sortedByLuminance.size * 0.2f).toInt())
        var inkR = 0L
        var inkG = 0L
        var inkB = 0L
        for (i in 0 until inkSampleCount) {
            val p = sortedByLuminance[i]
            inkR += AndroidColor.red(p)
            inkG += AndroidColor.green(p)
            inkB += AndroidColor.blue(p)
        }

        val avgInkR = (inkR / inkSampleCount).toInt().coerceIn(0, 255)
        val avgInkG = (inkG / inkSampleCount).toInt().coerceIn(0, 255)
        val avgInkB = (inkB / inkSampleCount).toInt().coerceIn(0, 255)

        val estimatedText = Color(avgInkR, avgInkG, avgInkB)

        // 3. Estimate Font Size (SP) based on bounding box height relative to reference viewport (e.g. 400dp width)
        val docScale = 400f / documentRefWidth.coerceAtLeast(100).toFloat()
        val estimatedSizeSp = (boxH * docScale * 0.72f).coerceIn(8f, 72f)

        // 4. Estimate Font Style (Bold if ink density is high)
        val isBold = if (insidePixels.isNotEmpty()) {
            val darkPixelsCount = sortedByLuminance.count { p ->
                val lum = 0.299 * AndroidColor.red(p) + 0.587 * AndroidColor.green(p) + 0.114 * AndroidColor.blue(p)
                lum < 120
            }
            (darkPixelsCount.toFloat() / insidePixels.size) > 0.35f
        } else {
            false
        }

        return EstimatedStyle(
            textColor = estimatedText,
            backgroundColor = estimatedBg,
            fontSizeSp = estimatedSizeSp,
            fontStyle = FontStyleEstimate(isBold = isBold)
        )
    }
}
