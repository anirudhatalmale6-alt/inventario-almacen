package com.fresenius.inventario.ui.scan

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.fresenius.inventario.model.ScanResult
import com.fresenius.inventario.util.Gs1Barcode
import com.fresenius.inventario.util.PartNoExtractor
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

enum class ScanMode {
    OCR_ONLY,      // Step 1: Read Part No. from label text
    BARCODE_ONLY,  // Step 2: Read barcode only (faster, more reliable)
    BOTH           // Try both simultaneously
}

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
    @Volatile
    var scanMode: ScanMode = ScanMode.OCR_ONLY

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

            when (scanMode) {
                ScanMode.BARCODE_ONLY -> scanBarcodeOnly(image, imageProxy)
                ScanMode.OCR_ONLY -> scanOcrOnly(image, imageProxy)
                ScanMode.BOTH -> scanBoth(image, imageProxy, mediaImage, imageProxy.imageInfo.rotationDegrees)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analyze error: ${e.message}")
            isProcessing = false
            imageProxy.close()
        }
    }

    private fun scanBarcodeOnly(image: InputImage, imageProxy: ImageProxy) {
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.first()
                    val raw = barcode.rawValue
                    val format = when (barcode.format) {
                        Barcode.FORMAT_CODE_128 -> "CODE_128"
                        Barcode.FORMAT_EAN_13 -> "EAN_13"
                        Barcode.FORMAT_EAN_8 -> "EAN_8"
                        Barcode.FORMAT_QR_CODE -> "QR_CODE"
                        Barcode.FORMAT_CODE_39 -> "CODE_39"
                        else -> "OTHER"
                    }
                    Log.d(TAG, "Barcode detected: $raw ($format)")
                    deliverResult(raw, format, null, imageProxy)
                } else {
                    // No barcode found in this frame, keep trying
                    isProcessing = false
                    imageProxy.close()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Barcode scan failed: ${e.message}")
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun scanOcrOnly(image: InputImage, imageProxy: ImageProxy) {
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                val ocrText = result.text
                if (ocrText.isNotEmpty()) {
                    val partNo = PartNoExtractor.extract(ocrText)
                    if (partNo != null) {
                        Log.d(TAG, "OCR Part No: $partNo")
                        deliverResult(null, null, ocrText, imageProxy)
                        return@addOnSuccessListener
                    }
                }
                // No Part No. found, keep trying
                isProcessing = false
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun scanBoth(image: InputImage, imageProxy: ImageProxy, mediaImage: android.media.Image, rotation: Int) {
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                var barcodeResult: String? = null
                var barcodeFormat: String? = null

                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes.first()
                    barcodeResult = barcode.rawValue
                    barcodeFormat = when (barcode.format) {
                        Barcode.FORMAT_CODE_128 -> "CODE_128"
                        else -> "OTHER"
                    }
                }

                try {
                    val ocrImage = InputImage.fromMediaImage(mediaImage, rotation)
                    textRecognizer.process(ocrImage)
                        .addOnSuccessListener { textResult ->
                            deliverResult(barcodeResult, barcodeFormat, textResult.text, imageProxy)
                        }
                        .addOnFailureListener {
                            deliverResult(barcodeResult, barcodeFormat, null, imageProxy)
                        }
                } catch (e: Exception) {
                    deliverResult(barcodeResult, barcodeFormat, null, imageProxy)
                }
            }
            .addOnFailureListener {
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun deliverResult(
        barcode: String?,
        barcodeFormat: String?,
        ocrText: String?,
        imageProxy: ImageProxy
    ) {
        try {
            val partNo = ocrText?.let { PartNoExtractor.extract(it) }
            // Clean GS1-128 barcode: strip ]C1 prefix, format AIs with parentheses
            val cleanBarcode = Gs1Barcode.clean(barcode)

            if (cleanBarcode != null || partNo != null) {
                val result = ScanResult(
                    barcode = cleanBarcode,
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
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        try {
            barcodeScanner.close()
            textRecognizer.close()
        } catch (_: Exception) {}
    }
}
