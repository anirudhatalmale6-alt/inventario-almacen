package com.fresenius.inventario.util

import android.util.Log

/**
 * Extracts barcode data from OCR text when the ML Kit barcode scanner fails.
 * Fresenius labels have the barcode number printed below the barcode:
 * Format: (01)GTIN(13)DATE e.g. (01)04039361018207(13)200423
 * OCR sometimes misreads parentheses and digits.
 */
object BarcodeFromOcr {

    private const val TAG = "BarcodeFromOcr"

    // Pattern for GS1-128 printed text: (01)digits(13)digits
    // OCR may misread () as [] or {} or other chars
    private val GS1_PATTERN = Regex(
        """[\(\[\{]?0[1l][\)\]\}]?\s*(\d{13,14})\s*[\(\[\{]?1[3][\)\]\}]?\s*(\d{6})"""
    )

    // Looser pattern: just find a 13-14 digit sequence (GTIN)
    private val GTIN_PATTERN = Regex(
        """(?<!\d)(0403\d{9,10})(?!\d)"""
    )

    fun extract(ocrText: String): String? {
        // Try strict GS1 pattern
        GS1_PATTERN.find(ocrText)?.let { match ->
            val gtin = match.groupValues[1]
            val date = match.groupValues[2]
            val result = "(01)$gtin(13)$date"
            Log.d(TAG, "GS1 barcode from OCR: $result")
            return result
        }

        // Try finding GTIN starting with 0403 (Fresenius prefix)
        GTIN_PATTERN.find(ocrText)?.let { match ->
            val gtin = match.groupValues[1]
            Log.d(TAG, "GTIN from OCR: $gtin")
            return gtin
        }

        return null
    }
}
