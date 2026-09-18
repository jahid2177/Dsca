package com.docscan.scanner

// Scanner pipeline revision: 2026-09 — stable page-boundary tuning.

import android.graphics.Bitmap
import android.util.Log
import com.docscan.data.model.FilterType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Premium OpenCV Image Filter Engine for Professional Document Scanning.
 * Upgraded with ML-Kit style illumination normalization and shadow removal.
 */
object OpenCvFilterProcessor {

    private const val TAG = "OpenCvFilterProcessor"

    fun applyFilter(
        source: Bitmap,
        filterType: FilterType,
        brightness: Float = 0f, 
        contrast: Float = 1f   
    ): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null

        val srcMat = Mat()
        var outMat: Mat? = null
        return try {
            Utils.bitmapToMat(source, srcMat)

            outMat = when (filterType) {
                FilterType.ORIGINAL -> applyOriginal(srcMat, brightness, contrast)
                FilterType.MAGIC_COLOR -> applyPremiumMagicColor(srcMat, brightness, contrast)
                FilterType.BW -> applyUltraBw(srcMat, brightness, contrast)
                FilterType.GRAYSCALE -> applyGrayscale(srcMat, brightness, contrast)
                FilterType.DOCUMENT -> applyDocument(srcMat, brightness, contrast)
                FilterType.CLEAR -> applyClear(srcMat, brightness, contrast)
                FilterType.LIGHTEN -> applyLighten(srcMat, brightness, contrast)
                FilterType.AUTO -> applyAuto(srcMat, brightness, contrast)
            }

            val resultBitmap = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, resultBitmap)
            resultBitmap
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV filter execution failed for type $filterType", e)
            null
        } finally {
            srcMat.release()
            outMat?.release()
        }
    }

    /**
     * PREMIUM UPGRADE: Magic Color
     * Advanced shadow removal and uneven lighting correction while preserving signatures.
     */
    private fun applyPremiumMagicColor(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val rgbMat = Mat()
        val ycrcbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)

        val channels = ArrayList<Mat>(3)
        Core.split(ycrcbMat, channels)
        val y = channels[0]   
        val cr = channels[1]  
        val cb = channels[2]  

        // 1. Premium Shadow Removal (Morphological Background Estimation)
        val background = Mat()
        // Using a dynamically sized kernel based on image resolution for better shadow mapping
        val kernelSize = (Math.max(srcMat.width(), srcMat.height()) / 45.0).toInt().coerceAtLeast(15)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize.toDouble(), kernelSize.toDouble()))
        
        Imgproc.morphologyEx(y, background, Imgproc.MORPH_DILATE, kernel)
        Imgproc.GaussianBlur(background, background, Size(kernelSize.toDouble() + 6, kernelSize.toDouble() + 6), 0.0)

        // 2. Division for Illumination Normalization
        val yFloat = Mat()
        val bgFloat = Mat()
        val yNorm = Mat()
        y.convertTo(yFloat, CvType.CV_32F)
        background.convertTo(bgFloat, CvType.CV_32F)
        Core.add(bgFloat, Scalar(1.0), bgFloat) 
        Core.divide(yFloat, bgFloat, yNorm, 255.0)

        val yNorm8u = Mat()
        yNorm.convertTo(yNorm8u, CvType.CV_8U)

        // 3. Smart Contrast (CLAHE)
        val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
        val yClahe = Mat()
        clahe.apply(yNorm8u, yClahe)

        // 4. Adaptive Unsharp Masking for crisp text
        val yBlur = Mat()
        Imgproc.GaussianBlur(yClahe, yBlur, Size(0.0, 0.0), 1.8)
        val ySharp = Mat()
        Core.addWeighted(yClahe, 1.5, yBlur, -0.5, 0.0, ySharp)

        // 5. Signature & Stamp Color Preservation
        val crEnhanced = Mat()
        val cbEnhanced = Mat()
        cr.convertTo(crEnhanced, -1, 1.45, -57.6) // Boost red/blue saturation safely
        cb.convertTo(cbEnhanced, -1, 1.45, -57.6)

        val mergedYCrCb = Mat()
        Core.merge(listOf(ySharp, crEnhanced, cbEnhanced), mergedYCrCb)
        val outRgb = Mat()
        Imgproc.cvtColor(mergedYCrCb, outRgb, Imgproc.COLOR_YCrCb2RGB)

        // 6. User Adjustments
        val finalRgb = Mat()
        val alpha = (contrast * 1.15f).toDouble()
        val beta = (brightness * 60.0 + 10.0)
        outRgb.convertTo(finalRgb, -1, alpha, beta)

        val outRgba = Mat()
        Imgproc.cvtColor(finalRgb, outRgba, Imgproc.COLOR_RGB2RGBA)

        // Memory Cleanup
        rgbMat.release(); ycrcbMat.release(); channels.forEach { it.release() }
        background.release(); kernel.release(); yFloat.release(); bgFloat.release()
        yNorm.release(); yNorm8u.release(); yClahe.release(); yBlur.release(); ySharp.release()
        crEnhanced.release(); cbEnhanced.release(); mergedYCrCb.release(); outRgb.release(); finalRgb.release()
        clahe.collectGarbage()

        return outRgba
    }

    /**
     * PREMIUM UPGRADE: Ultra B&W
     * Perfect text extraction ignoring paper grain and folded shadows.
     */
    private fun applyUltraBw(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val grayMat = Mat()
        Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        // 1. Edge-Preserving Denoise
        val smoothed = Mat()
        Imgproc.bilateralFilter(grayMat, smoothed, 9, 75.0, 75.0)

        // 2. Localized Adaptive Thresholding
        val cOffset = (15.0 - (brightness * 20.0)).coerceIn(5.0, 30.0)
        val binary = Mat()
        
        // Larger block size handles larger text fonts and deep shadows better
        val blockSize = (Math.max(srcMat.width(), srcMat.height()) / 35.0).toInt()
        val finalBlockSize = if (blockSize % 2 == 0) blockSize + 1 else blockSize

        Imgproc.adaptiveThreshold(
            smoothed,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            finalBlockSize.coerceAtLeast(15),
            cOffset
        )

        // 3. Morphological Cleaning (Remove pepper noise)
        val clean = Mat()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, clean, Imgproc.MORPH_CLOSE, k)

        val outRgba = Mat()
        Imgproc.cvtColor(clean, outRgba, Imgproc.COLOR_GRAY2RGBA)

        grayMat.release(); smoothed.release(); binary.release(); clean.release(); k.release()
        return outRgba
    }

    // --- অন্যান্য ফিল্টারগুলো আগের মতোই অপরিবর্তিত থাকবে ---
    // (applyGrayscale, applyDocument, applyClear, applyLighten, applyAuto, applyOriginal)
    
    fun applyGrayscale(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val grayMat = Mat()
        Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
        val claheMat = Mat()
        clahe.apply(grayMat, claheMat)
        val blur = Mat()
        Imgproc.GaussianBlur(claheMat, blur, Size(0.0, 0.0), 1.2)
        val sharp = Mat()
        Core.addWeighted(claheMat, 1.25, blur, -0.25, 0.0, sharp)
        val adjusted = Mat()
        val alpha = (contrast * 1.1f).toDouble()
        val beta = (brightness * 50.0 + 8.0)
        sharp.convertTo(adjusted, -1, alpha, beta)
        val outRgba = Mat()
        Imgproc.cvtColor(adjusted, outRgba, Imgproc.COLOR_GRAY2RGBA)
        grayMat.release(); claheMat.release(); blur.release(); sharp.release(); adjusted.release()
        clahe.collectGarbage()
        return outRgba
    }

    fun applyDocument(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val rgbMat = Mat()
        val ycrcbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)
        val channels = ArrayList<Mat>(3)
        Core.split(ycrcbMat, channels)
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val yClahe = Mat()
        clahe.apply(channels[0], yClahe)
        val yBlur = Mat()
        Imgproc.GaussianBlur(yClahe, yBlur, Size(0.0, 0.0), 1.8)
        val ySharp = Mat()
        Core.addWeighted(yClahe, 1.6, yBlur, -0.6, 0.0, ySharp)
        val crMuted = Mat()
        val cbMuted = Mat()
        channels[1].convertTo(crMuted, -1, 0.85, 19.2)
        channels[2].convertTo(cbMuted, -1, 0.85, 19.2)
        val merged = Mat()
        Core.merge(listOf(ySharp, crMuted, cbMuted), merged)
        val outRgb = Mat()
        Imgproc.cvtColor(merged, outRgb, Imgproc.COLOR_YCrCb2RGB)
        val finalRgb = Mat()
        outRgb.convertTo(finalRgb, -1, (contrast * 1.3f).toDouble(), (brightness * 50.0 + 15.0))
        val outRgba = Mat()
        Imgproc.cvtColor(finalRgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        rgbMat.release(); ycrcbMat.release(); channels.forEach { it.release() }
        yClahe.release(); yBlur.release(); ySharp.release(); crMuted.release(); cbMuted.release()
        merged.release(); outRgb.release(); finalRgb.release()
        clahe.collectGarbage()
        return outRgba
    }

    fun applyClear(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val rgbMat = Mat()
        val ycrcbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)
        val channels = ArrayList<Mat>(3)
        Core.split(ycrcbMat, channels)
        val yLift = Mat()
        channels[0].convertTo(yLift, -1, 1.15, 20.0)
        val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
        val yClahe = Mat()
        clahe.apply(yLift, yClahe)
        val merged = Mat()
        Core.merge(listOf(yClahe, channels[1], channels[2]), merged)
        val outRgb = Mat()
        Imgproc.cvtColor(merged, outRgb, Imgproc.COLOR_YCrCb2RGB)
        val finalRgb = Mat()
        outRgb.convertTo(finalRgb, -1, (contrast * 1.2f).toDouble(), (brightness * 45.0 + 12.0))
        val outRgba = Mat()
        Imgproc.cvtColor(finalRgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        rgbMat.release(); ycrcbMat.release(); channels.forEach { it.release() }
        yLift.release(); yClahe.release(); merged.release(); outRgb.release(); finalRgb.release()
        clahe.collectGarbage()
        return outRgba
    }

    fun applyLighten(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val rgbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        val brightRgb = Mat()
        rgbMat.convertTo(brightRgb, -1, (contrast * 1.1f).toDouble(), (brightness * 50.0 + 35.0))
        val outRgba = Mat()
        Imgproc.cvtColor(brightRgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        rgbMat.release(); brightRgb.release()
        return outRgba
    }

    fun applyAuto(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        val rgbMat = Mat()
        val ycrcbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)
        val channels = ArrayList<Mat>(3)
        Core.split(ycrcbMat, channels)
        val clahe = Imgproc.createCLAHE(1.6, Size(8.0, 8.0))
        val yClahe = Mat()
        clahe.apply(channels[0], yClahe)
        val yBlur = Mat()
        Imgproc.GaussianBlur(yClahe, yBlur, Size(0.0, 0.0), 1.2)
        val ySharp = Mat()
        Core.addWeighted(yClahe, 1.3, yBlur, -0.3, 0.0, ySharp)
        val merged = Mat()
        Core.merge(listOf(ySharp, channels[1], channels[2]), merged)
        val outRgb = Mat()
        Imgproc.cvtColor(merged, outRgb, Imgproc.COLOR_YCrCb2RGB)
        val finalRgb = Mat()
        outRgb.convertTo(finalRgb, -1, (contrast * 1.2f).toDouble(), (brightness * 50.0 + 10.0))
        val outRgba = Mat()
        Imgproc.cvtColor(finalRgb, outRgba, Imgproc.COLOR_RGB2RGBA)
        rgbMat.release(); ycrcbMat.release(); channels.forEach { it.release() }
        yClahe.release(); yBlur.release(); ySharp.release(); merged.release(); outRgb.release(); finalRgb.release()
        clahe.collectGarbage()
        return outRgba
    }

    private fun applyOriginal(srcMat: Mat, brightness: Float = 0f, contrast: Float = 1f): Mat {
        if (brightness == 0f && contrast == 1f) return srcMat.clone()
        val adjusted = Mat()
        srcMat.convertTo(adjusted, -1, contrast.toDouble(), (brightness * 100.0))
        return adjusted
    }
}
