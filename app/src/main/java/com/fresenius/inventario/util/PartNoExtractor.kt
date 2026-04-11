package com.fresenius.inventario.util

import android.util.Log

/**
 * Extracts Part No. from OCR text of Fresenius Medical Care labels.
 * Labels always contain "Part No." followed by the reference number.
 * References can start with letters (F, M) or be purely numeric.
 */
object PartNoExtractor {

    private const val TAG = "PartNoExtractor"

    // Pattern: "Part No." or similar OCR variations, followed by the reference
    private val PART_NO_PATTERN = Regex(
        """(?i)(?:Part|Pari|Parf)\s*(?:No|N[oO0c])\s*[.,:;]?\s*[\n\r]?\s*([A-Z]?\d{4,10}[A-Z]?)""",
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

    /**
     * Fuzzy matching: finds the closest Part No. in a list of known references.
     * Allows up to 2 character substitutions (OCR often confuses similar digits: 4/6, 3/8, 5/6, etc.)
     */
    fun findClosestMatch(detected: String, knownPartNos: List<String>): String? {
        // First try exact match
        knownPartNos.find { it.equals(detected, ignoreCase = true) }?.let { return it }

        // Try fuzzy match with edit distance <= 2
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE

        for (known in knownPartNos) {
            if (known.length != detected.length) continue // Same length only for part numbers
            val distance = charDifferences(detected.uppercase(), known.uppercase())
            if (distance in 1..2 && distance < bestDistance) {
                bestDistance = distance
                bestMatch = known
            }
        }

        if (bestMatch != null) {
            Log.d(TAG, "Fuzzy match: '$detected' -> '$bestMatch' (distance: $bestDistance)")
        }
        return bestMatch
    }

    private fun charDifferences(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE
        return a.zip(b).count { (c1, c2) -> c1 != c2 }
    }
}
