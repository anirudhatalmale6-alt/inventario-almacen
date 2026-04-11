package com.fresenius.inventario.util

import android.util.Log

/**
 * Extracts Part No. from OCR text of Fresenius Medical Care labels.
 * Labels always contain "Part No." followed by the reference number.
 * References can start with letters (F, M) or be purely numeric.
 */
object PartNoExtractor {

    private const val TAG = "PartNoExtractor"

    // Pattern: "Part No." or OCR variations, followed by the reference
    private val PART_NO_PATTERN = Regex(
        """(?i)(?:Part|Pari|Parf)\s*(?:No|N[oO0c])\s*[.,:;]?\s*[\n\r]?\s*([A-Z]?\d{4,10}[A-Z]?)""",
        RegexOption.MULTILINE
    )

    // Fallback: F or M prefixed references
    private val REFERENCE_PATTERN = Regex(
        """(?<!\w)([FM]\d{5,9})(?!\d)"""
    )

    // Numeric-only references (7 digits)
    private val NUMERIC_REF_PATTERN = Regex(
        """(?<!\d)(\d{7})(?!\d)"""
    )

    fun extract(ocrText: String): String? {
        Log.d(TAG, "Extracting from: ${ocrText.take(200)}")

        PART_NO_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via Part No. pattern: $result")
            return result
        }

        REFERENCE_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via F/M pattern: $result")
            return result
        }

        NUMERIC_REF_PATTERN.find(ocrText)?.let { match ->
            val result = match.groupValues[1].trim()
            Log.d(TAG, "Found via numeric pattern: $result")
            return result
        }

        Log.d(TAG, "No Part No. found")
        return null
    }

    /**
     * Fuzzy matching: finds the closest Part No. in a list of known references.
     * Handles:
     * - OCR character substitutions (4→6, 3→8, 5→6, etc.) - up to 2 differences
     * - Missing trailing digit (label shows M46523 but Excel has M465231)
     * - Extra trailing digit
     */
    fun findClosestMatch(detected: String, knownPartNos: List<String>): String? {
        val detectedUpper = detected.uppercase()

        // 1. Exact match
        knownPartNos.find { it.equals(detectedUpper, ignoreCase = true) }?.let { return it }

        // 2. Detected is prefix of a known Part No. (missing trailing digits)
        //    e.g., M46523 matches M465231
        val prefixMatches = knownPartNos.filter {
            it.uppercase().startsWith(detectedUpper) && it.length <= detectedUpper.length + 2
        }
        if (prefixMatches.size == 1) {
            Log.d(TAG, "Prefix match: '$detected' -> '${prefixMatches[0]}'")
            return prefixMatches[0]
        }

        // 3. Known Part No. is prefix of detected (extra trailing digits from OCR)
        //    e.g., M4652310 matches M465231
        val suffixMatches = knownPartNos.filter {
            detectedUpper.startsWith(it.uppercase()) && detectedUpper.length <= it.length + 2
        }
        if (suffixMatches.size == 1) {
            Log.d(TAG, "Suffix match: '$detected' -> '${suffixMatches[0]}'")
            return suffixMatches[0]
        }

        // 4. Same length, up to 2 character differences (OCR misread digits)
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE

        for (known in knownPartNos) {
            if (kotlin.math.abs(known.length - detectedUpper.length) > 1) continue

            val distance = if (known.length == detectedUpper.length) {
                charDifferences(detectedUpper, known.uppercase())
            } else {
                // Allow 1 length difference + substitutions
                levenshtein(detectedUpper, known.uppercase())
            }

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

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
