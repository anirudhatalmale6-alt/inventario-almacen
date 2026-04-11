package com.fresenius.inventario.ui.scan

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.fresenius.inventario.model.ScanResult
import com.fresenius.inventario.util.PartNoExtractor
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScanAnalyzer(
    private val onResult: (ScanResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "ScanAnalyzer"
    }

    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    @Volatile
    private var isProcessing = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // First: scan barcode
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var barcodeResult: String? = null
                    var barcodeFormat: String? = null

                    if (barcodes.isNotEmpty()) {
                        val barcode = barcodes.first()
                        barcodeResult = barcode.rawValue
                        barcodeFormat = when (barcode.format) {
                            Barcode.FORMAT_CODE_128 -> "CODE_128"
                            Barcode.FORMAT_EAN_13 -> "EAN_13"
                            Barcode.FORMAT_EAN_8 -> "EAN_8"
                            Barcode.FORMAT_QR_CODE -> "QR_CODE"
                            Barcode.FORMAT_CODE_39 -> "CODE_39"
                            else -> "OTHER"
                        }
                        Log.d(TAG, "Barcode detected: $barcodeResult ($barcodeFormat)")
                    }

                    // Then: run OCR on a new InputImage (the old one may be invalid after barcode processing)
                    val ocrImage: InputImage
                    try {
                        ocrImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        // mediaImage may already be closed, deliver barcode-only result
                        Log.w(TAG, "Could not create OCR image: ${e.message}")
                        deliverResult(barcodeResult, barcodeFormat, null, null, imageProxy)
                        return@addOnSuccessListener
                    }

                    textRecognizer.process(ocrImage)
                        .addOnSuccessListener { textResult ->
                            val ocrText = textResult.text
                            if (ocrText.isNotEmpty()) {
                                Log.d(TAG, "OCR text: ${ocrText.take(100)}")
                            }
                            deliverResult(barcodeResult, barcodeFormat, ocrText, null, imageProxy)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "OCR failed: ${e.message}")
                            deliverResult(barcodeResult, barcodeFormat, null, null, imageProxy)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scan failed: ${e.message}")
                    // Try OCR even if barcode fails
                    try {
                        val ocrImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        textRecognizer.process(ocrImage)
                            .addOnSuccessListener { textResult ->
                                deliverResult(null, null, textResult.text, null, imageProxy)
                            }
                            .addOnFailureListener {
                                deliverResult(null, null, null, null, imageProxy)
                            }
                    } catch (ex: Exception) {
                        deliverResult(null, null, null, null, imageProxy)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Analyze error: ${e.message}")
            isProcessing = false
            imageProxy.close()
        }
    }

    private fun deliverResult(
        barcode: String?,
        barcodeFormat: String?,
        ocrText: String?,
        error: String?,
        imageProxy: ImageProxy
    ) {
        try {
            val partNo = ocrText?.let { PartNoExtractor.extract(it) }

            if (barcode != null || partNo != null) {
                Log.d(TAG, "Delivering result: barcode=$barcode, partNo=$partNo")
                val result = ScanResult(
                    barcode = barcode,
                    barcodeFormat = barcodeFormat,
                    partNo = partNo,
                    ocrFullText = ocrText
                )
                onResult(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering result: ${e.message}")
        } finally {
            isProcessing = false
            try {
                imageProxy.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing imageProxy: ${e.message}")
            }
        }
    }

    fun close() {
        try {
            barcodeScanner.close()
            textRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing scanners: ${e.message}")
        }
    }
}
