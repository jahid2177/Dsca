package com.docscan.util.textedit

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Clean OCR Engine abstraction interface to allow pluggable text recognition engines.
 */
interface TextRecognitionEngine {
    suspend fun recognizeText(bitmap: Bitmap): OcrDocument
}

/**
 * Production-ready Google ML Kit on-device Text Recognition implementation.
 * Extracts full document hierarchy (Blocks -> Lines -> Words) with accurate bounding box
 * coordinates normalized relative to original bitmap dimensions.
 */
class MlKitTextRecognitionEngine : TextRecognitionEngine {

    companion object {
        private const val TAG = "MlKitTextRecognition"
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognizeText(bitmap: Bitmap): OcrDocument = withContext(Dispatchers.Default) {
        val origWidth = bitmap.width
        val origHeight = bitmap.height

        // 1. Run Preprocessing on a temporary working copy to enhance faint text
        val preprocessed = try {
            DocumentImagePreprocessor.preprocessForOcr(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Preprocessing failed, falling back to original bitmap: ${e.message}")
            bitmap
        }

        val prepWidth = preprocessed.width
        val prepHeight = preprocessed.height
        val scaleX = origWidth.toFloat() / prepWidth.toFloat()
        val scaleY = origHeight.toFloat() / prepHeight.toFloat()

        try {
            val inputImage = InputImage.fromBitmap(preprocessed, 0)

            val visionText = suspendCancellableCoroutine { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        continuation.resume(result)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                        continuation.resume(null)
                    }
            }

            if (preprocessed != bitmap) {
                preprocessed.recycle()
            }

            if (visionText == null || visionText.textBlocks.isEmpty()) {
                return@withContext OcrDocument(
                    fullText = "",
                    blocks = emptyList(),
                    imageWidth = origWidth,
                    imageHeight = origHeight
                )
            }

            // Map OCR elements back to original image coordinate space
            val parsedBlocks = mutableListOf<OcrTextBlock>()

            for (block in visionText.textBlocks) {
                val blockBox = block.boundingBox ?: continue

                val origBlockPixel = Rect(
                    (blockBox.left * scaleX).toInt().coerceIn(0, origWidth),
                    (blockBox.top * scaleY).toInt().coerceIn(0, origHeight),
                    (blockBox.right * scaleX).toInt().coerceIn(0, origWidth),
                    (blockBox.bottom * scaleY).toInt().coerceIn(0, origHeight)
                )

                val blockNormRect = RectF(
                    origBlockPixel.left.toFloat() / origWidth.toFloat(),
                    origBlockPixel.top.toFloat() / origHeight.toFloat(),
                    origBlockPixel.right.toFloat() / origWidth.toFloat(),
                    origBlockPixel.bottom.toFloat() / origHeight.toFloat()
                )

                // Style estimation for the block
                val blockStyle = TextStyleEstimator.estimate(bitmap, origBlockPixel)

                val parsedLines = mutableListOf<OcrTextLine>()

                for (line in block.lines) {
                    val lineBox = line.boundingBox ?: continue

                    val origLinePixel = Rect(
                        (lineBox.left * scaleX).toInt().coerceIn(0, origWidth),
                        (lineBox.top * scaleY).toInt().coerceIn(0, origHeight),
                        (lineBox.right * scaleX).toInt().coerceIn(0, origWidth),
                        (lineBox.bottom * scaleY).toInt().coerceIn(0, origHeight)
                    )

                    val lineNormRect = RectF(
                        origLinePixel.left.toFloat() / origWidth.toFloat(),
                        origLinePixel.top.toFloat() / origHeight.toFloat(),
                        origLinePixel.right.toFloat() / origWidth.toFloat(),
                        origLinePixel.bottom.toFloat() / origHeight.toFloat()
                    )

                    val lineStyle = TextStyleEstimator.estimate(bitmap, origLinePixel)

                    val parsedWords = mutableListOf<OcrTextElement>()
                    for (element in line.elements) {
                        val elemBox = element.boundingBox ?: continue
                        val origElemPixel = Rect(
                            (elemBox.left * scaleX).toInt().coerceIn(0, origWidth),
                            (elemBox.top * scaleY).toInt().coerceIn(0, origHeight),
                            (elemBox.right * scaleX).toInt().coerceIn(0, origWidth),
                            (elemBox.bottom * scaleY).toInt().coerceIn(0, origHeight)
                        )
                        val elemNormRect = RectF(
                            origElemPixel.left.toFloat() / origWidth.toFloat(),
                            origElemPixel.top.toFloat() / origHeight.toFloat(),
                            origElemPixel.right.toFloat() / origWidth.toFloat(),
                            origElemPixel.bottom.toFloat() / origHeight.toFloat()
                        )
                        parsedWords.add(
                            OcrTextElement(
                                text = element.text,
                                pixelRect = origElemPixel,
                                normalizedRect = elemNormRect,
                                confidence = element.confidence
                            )
                        )
                    }

                    parsedLines.add(
                        OcrTextLine(
                            text = line.text,
                            pixelRect = origLinePixel,
                            normalizedRect = lineNormRect,
                            elements = parsedWords,
                            confidence = line.confidence,
                            rotationAngle = line.angle,
                            estimatedTextSizeSp = lineStyle.fontSizeSp,
                            estimatedTextColor = lineStyle.textColor,
                            estimatedBackgroundColor = lineStyle.backgroundColor,
                            estimatedFontStyle = lineStyle.fontStyle
                        )
                    )
                }

                parsedBlocks.add(
                    OcrTextBlock(
                        text = block.text,
                        pixelRect = origBlockPixel,
                        normalizedRect = blockNormRect,
                        lines = parsedLines,
                        rotationAngle = 0f,
                        estimatedTextSizeSp = blockStyle.fontSizeSp,
                        estimatedTextColor = blockStyle.textColor,
                        estimatedBackgroundColor = blockStyle.backgroundColor,
                        estimatedFontStyle = blockStyle.fontStyle
                    )
                )
            }

            OcrDocument(
                fullText = visionText.text,
                blocks = parsedBlocks,
                imageWidth = origWidth,
                imageHeight = origHeight
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OCR parsing: ${e.message}", e)
            OcrDocument(
                fullText = "",
                blocks = emptyList(),
                imageWidth = origWidth,
                imageHeight = origHeight
            )
        }
    }
}
