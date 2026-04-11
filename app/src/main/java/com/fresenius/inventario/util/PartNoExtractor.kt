package com.fresenius.inventario.util

import android.util.Log

/**
 * Extracts Part No. from OCR text of Fresenius Medical Care labels.
 * Labels always contain "Part No." followed by the reference number.
 * References can start with letters (F, M) or be purely numeric.
 */
object PartNoExtractor {

    private const val TAG = "PartNoExtractor"

    // Pattern: "Part No." or "Part No" followed by optional whitespace/newline, then the reference
    // Also handles OCR errors like "Part No," or "Pari No." etc.
    private val PART_NO_PATTERN = Regex(
        """(?i)(?:Part|Pari|Parf)\s*(?:No|N[oO0])\s*[.,:;]?\s*[\n\r]?\s*([A-Z]?\d{4,10}[A-Z]?)""",
        RegexOption.MULTILINE
    )

    // Fallback: look for standalone references that match Fresenius format (F or M prefix)
    private val REFERENCE_PATTERN = Regex(
        """(?<!\w)([FM]\d{5,9})(?!\d)"""
    )

    // Numeric-only references (7 digits, common in the Excel)
    private val NUMERIC_REF_PATTERN = Regex(
        """(?<!\d)(\d{7})(?!\d)"""
    )

    fun extract(ocrText: String): String? {
        Log.d(TAG, "Extracting from OCR text: ${ocrText.take(200)}")

        // Try the primary pattern first
        PART_NO_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via Part No. pattern: $result")
            return result
        }

        // Try F/M-prefixed references
        REFERENCE_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via F/M reference pattern: $result")
            return result
        }

        // Try 7-digit numeric references
        NUMERIC_REF_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via numeric pattern: $result")
            return result
        }

        Log.d(TAG, "No Part No. found in text")
        return null
    }
}
