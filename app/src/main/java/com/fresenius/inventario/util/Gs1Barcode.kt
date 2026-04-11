package com.fresenius.inventario.util

import android.util.Log

/**
 * Cleans and formats GS1-128 barcode data from ML Kit.
 *
 * ML Kit returns raw barcode data that may include:
 * - AIM symbology identifier: ]C1 (Code 128 with FNC1)
 * - Group Separator (GS, ASCII 29) between variable-length fields
 * - Raw AI data without parentheses
 *
 * This utility strips control characters and formats the data
 * into human-readable GS1 format: (01)GTIN(13)DATE etc.
 */
object Gs1Barcode {

    private const val TAG = "Gs1Barcode"

    // Known GS1 Application Identifiers and their fixed lengths (data portion only)
    // AI -> data length (null = variable length, terminated by GS)
    private val AI_LENGTHS = mapOf(
        "01" to 14,   // GTIN
        "02" to 14,   // GTIN of contained items
        "10" to null,  // Batch/Lot number (variable, up to 20)
        "11" to 6,    // Production date (YYMMDD)
        "13" to 6,    // Packaging date (YYMMDD)
        "15" to 6,    // Best before date (YYMMDD)
        "17" to 6,    // Expiration date (YYMMDD)
        "20" to 2,    // Product variant
        "21" to null,  // Serial number (variable, up to 20)
        "30" to null,  // Count of items (variable, up to 8)
        "37" to null,  // Count of trade items (variable, up to 8)
    )

    /**
     * Clean and format a barcode raw value from ML Kit.
     * Strips AIM identifiers, parses GS1 AIs, and returns human-readable format.
     */
    fun clean(rawValue: String?): String? {
        if (rawValue.isNullOrBlank()) return null

        var data = rawValue.trim()

        // Strip AIM symbology identifier ]C1 (GS1-128) or ]c1 or similar
        if (data.startsWith("]")) {
            val aimEnd = if (data.length >= 3) 3 else data.length
            val stripped = data.substring(aimEnd)
            Log.d(TAG, "Stripped AIM identifier '${data.substring(0, aimEnd)}' -> '$stripped'")
            data = stripped
        }

        // Remove GS characters (ASCII 29) - used as field separators
        data = data.replace("\u001D", "")

        // If data is just digits, try to parse as GS1 element string
        if (data.matches(Regex("^\\d+$")) && data.length >= 16) {
            val formatted = parseGs1Elements(data)
            if (formatted != null) {
                Log.d(TAG, "Formatted GS1: $formatted")
                return formatted
            }
        }

        // If already contains parentheses, it's already formatted
        if (data.contains("(") && data.contains(")")) {
            Log.d(TAG, "Already formatted: $data")
            return data
        }

        // Return cleaned data as-is if we can't parse it
        Log.d(TAG, "Returning cleaned: $data")
        return data
    }

    /**
     * Parse a raw GS1 element string (digits only) into human-readable format.
     * e.g., "0104030064050340132604XX" -> "(01)04030064050340(13)2604XX"
     */
    private fun parseGs1Elements(data: String): String? {
        val result = StringBuilder()
        var pos = 0

        while (pos < data.length) {
            var matched = false

            // Try 2-digit AIs first
            if (pos + 2 <= data.length) {
                val ai2 = data.substring(pos, pos + 2)
                val fixedLen = AI_LENGTHS[ai2]
                if (fixedLen != null) {
                    // Fixed-length AI
                    val endPos = minOf(pos + 2 + fixedLen, data.length)
                    val value = data.substring(pos + 2, endPos)
                    result.append("($ai2)$value")
                    pos = endPos
                    matched = true
                } else if (AI_LENGTHS.containsKey(ai2)) {
                    // Variable-length AI - take remaining digits
                    val value = data.substring(pos + 2)
                    result.append("($ai2)$value")
                    pos = data.length
                    matched = true
                }
            }

            if (!matched) {
                // Can't parse further, append remaining as-is
                if (result.isNotEmpty()) {
                    // We already parsed some AIs, append the rest
                    result.append(data.substring(pos))
                } else {
                    return null // Couldn't parse anything
                }
                break
            }
        }

        return if (result.isNotEmpty()) result.toString() else null
    }
}
