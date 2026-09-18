package com.docscan.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

object MlKitDocumentScannerHelper {

    data class ScanResult(
        val imageUris: List<Uri>,
        val pdfUri: Uri?,
        val pageCount: Int
    )

    fun createScannerOptions(pageLimit: Int = 100): GmsDocumentScannerOptions {
        return GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL) // Enables real-time edge detection, perspective correction, shadow removal, and auto-capture
            .build()
    }

    fun getScanner(options: GmsDocumentScannerOptions = createScannerOptions()): GmsDocumentScanner {
        return GmsDocumentScanning.getClient(options)
    }

    fun startScanning(
        activity: Activity,
        options: GmsDocumentScannerOptions = createScannerOptions(),
        onIntentSenderReady: (IntentSender) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val scanner = getScanner(options)
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                onIntentSenderReady(intentSender)
            }
            .addOnFailureListener { exception ->
                onError(exception)
            }
    }

    fun extractResult(data: Intent?): ScanResult? {
        if (data == null) return null
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null
        val imageUris = result.pages?.mapNotNull { it.imageUri } ?: emptyList()
        val pdfUri = result.pdf?.uri
        val pageCount = result.pdf?.pageCount ?: imageUris.size
        return ScanResult(
            imageUris = imageUris,
            pdfUri = pdfUri,
            pageCount = pageCount
        )
    }
}
