package com.docscan.util.textedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/**
 * Text Layout & Rendering Engine for Document Replacement.
 * Renders single or multi-line edited text accurately within original or expanded bounding boxes.
 */
object DocumentTextRenderer {

    /**
     * Renders a list of edit operations onto a Canvas representing the document bitmap.
     */
    fun renderOperationsOnCanvas(
        canvas: Canvas,
        operations: List<EditTextOperation>,
        bitmapWidth: Float,
        bitmapHeight: Float
    ) {
        for (op in operations) {
            if (op.isDeleted || op.newText.isBlank()) continue
            renderSingleOperation(canvas, op, bitmapWidth, bitmapHeight)
        }
    }

    /**
     * Directly renders an edit operation onto a copy of the given bitmap,
     * returning the newly composited Bitmap.
     */
    fun renderOperationOnBitmap(
        source: Bitmap,
        op: EditTextOperation
    ): Bitmap {
        if (op.isDeleted || op.newText.isBlank()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        renderSingleOperation(canvas, op, output.width.toFloat(), output.height.toFloat())
        return output
    }

    fun renderSingleOperation(
        canvas: Canvas,
        op: EditTextOperation,
        bitmapW: Float,
        bitmapH: Float
    ) {
        val rect = op.targetNormalizedRect
        val left = rect.left * bitmapW
        val top = rect.top * bitmapH
        val right = rect.right * bitmapW
        val bottom = rect.bottom * bitmapH
        val boxWidth = (right - left).coerceAtLeast(10f)
        val boxHeight = (bottom - top).coerceAtLeast(10f)

        val tfType = when (op.fontFamilyType) {
            "SERIF" -> Typeface.SERIF
            "MONO" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val typeface = if (op.isBold) {
            if (op.isItalic) Typeface.create(tfType, Typeface.BOLD_ITALIC) else Typeface.create(tfType, Typeface.BOLD)
        } else if (op.isItalic) {
            Typeface.create(tfType, Typeface.ITALIC)
        } else {
            tfType
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = op.textColor.toArgb()
            this.typeface = typeface
            isUnderlineText = op.isUnderline
        }

        val text = op.newText
        val isSingleLineForFit = !text.contains("\n")

        // Auto-fit: measure directly against the original detected box so the
        // replacement text always matches the original document text's size,
        // instead of guessing via an external sp->px scale factor.
        textPaint.textSize = fitTextSizeToBox(textPaint, text, boxWidth, boxHeight, isSingleLineForFit)

        // If background color is specified and not transparent
        if (op.backgroundColor != Color.Transparent) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = op.backgroundColor.toArgb()
                style = Paint.Style.FILL
            }
            canvas.drawRect(RectF(left, top, right, bottom), bgPaint)
        }

        // Multi-line StaticLayout rendering
        val align = when (op.alignment) {
            "CENTER" -> Layout.Alignment.ALIGN_CENTER
            "RIGHT" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        // Text size was already fit to boxWidth/boxHeight above, so the layout
        // width can simply be the box width (with a tiny safety margin for
        // sub-pixel measurement rounding).
        val measuredTextWidth = textPaint.measureText(text)
        val layoutWidth = max(10, max(boxWidth, measuredTextWidth + 2f).toInt())

        val staticLayout = StaticLayout.Builder.obtain(
            text, 0, text.length, textPaint, layoutWidth
        )
            .setAlignment(align)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()

        val textTotalHeight = staticLayout.height.toFloat()

        // Vertically center text in bounding box if single line, or align to top
        val startY = if (staticLayout.lineCount == 1) {
            top + (boxHeight - textTotalHeight) / 2f
        } else {
            top
        }

        canvas.save()
        canvas.translate(left, startY)
        staticLayout.draw(canvas)
        canvas.restore()
    }

    /**
     * Finds the largest text size (in px) at which [text] still fits inside
     * [boxWidth] x [boxHeight]. This makes the rendered text automatically
     * match the size of the original detected text region on the document,
     * instead of relying on an external sp value or a fixed scale factor.
     *
     * Uses binary search over [paint]'s textSize, so it works for any
     * device density / bitmap resolution without a hardcoded reference width.
     */
    private fun fitTextSizeToBox(
        paint: TextPaint,
        text: String,
        boxWidth: Float,
        boxHeight: Float,
        isSingleLine: Boolean
    ): Float {
        var low = 4f
        var high = boxHeight.coerceAtLeast(low + 1f)
        var best = low

        repeat(14) {
            val mid = (low + high) / 2f
            paint.textSize = mid

            val fits = if (isSingleLine) {
                val fm = paint.fontMetrics
                val lineHeight = fm.descent - fm.ascent
                val textWidth = paint.measureText(text)
                lineHeight <= boxHeight && textWidth <= boxWidth
            } else {
                val layout = StaticLayout.Builder.obtain(
                    text, 0, text.length, paint, max(1, boxWidth.toInt())
                )
                    .setLineSpacing(0f, 1.1f)
                    .setIncludePad(false)
                    .build()
                layout.height <= boxHeight
            }

            if (fits) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }
}
