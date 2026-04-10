package com.fresenius.inventario.ui.scan

import android.annotation.SuppressLint
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

    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private var isProcessing = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        isProcessing = true
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        // Run barcode and OCR in parallel
        var barcodeResult: String? = null
        var barcodeFormat: String? = null
        var ocrText: String? = null
        var barcodeComplete = false
        var ocrComplete = false

        fun checkComplete() {
            if (barcodeComplete && ocrComplete) {
                val partNo = ocrText?.let { PartNoExtractor.extract(it) }
                val result = ScanResult(
                    barcode = barcodeResult,
                    barcodeFormat = barcodeFormat,
                    partNo = partNo,
                    ocrFullText = ocrText
                )

                if (result.barcode != null || result.partNo != null) {
                    onResult(result)
                }

                isProcessing = false
                imageProxy.close()
            }
        }

        // Barcode scanning
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
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
                }
                barcodeComplete = true
                checkComplete()
            }
            .addOnFailureListener {
                barcodeComplete = true
                checkComplete()
            }

        // Text recognition (OCR)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                ocrText = result.text
                ocrComplete = true
                checkComplete()
            }
            .addOnFailureListener {
                ocrComplete = true
                checkComplete()
            }
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
    }
}
