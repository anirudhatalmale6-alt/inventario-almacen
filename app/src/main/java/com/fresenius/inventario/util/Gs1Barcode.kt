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

    // Regex to extract GTIN part: (01)XXXXXXXXXXXXXXX
    private val GTIN_REGEX = Regex("""\(01\)(\d{13,14})""")
    // Regex to extract date after (13): (13)XXXXXX
    private val DATE_REGEX = Regex("""\(13\)(\d{6})""")

    /**
     * Clean and format a barcode raw value from ML Kit.
     */
    fun clean(rawValue: String?): String? {
        if (rawValue.isNullOrBlank()) return null

        var data = rawValue.trim()

        // Strip AIM symbology identifier ]C1
        if (data.startsWith("]")) {
            val aimEnd = if (data.length >= 3) 3 else data.length
            data = data.substring(aimEnd)
        }

        // Remove GS characters (ASCII 29)
        data = data.replace("\u001D", "")

        // If data is just digits, try to parse as GS1 element string
        if (data.matches(Regex("^\\d+$")) && data.length >= 16) {
            val formatted = parseGs1Elements(data)
            if (formatted != null) return formatted
        }

        if (data.contains("(") && data.contains(")")) return data

        return data
    }

    /**
     * Extract only the GTIN (product identifier) from a GS1-128 barcode,
     * ignoring the date and other fields.
     * e.g., "(01)04030064050340(13)200423" -> "(01)04030064050340"
     *
     * This is used for matching: two barcodes with the same GTIN but
     * different manufacturing dates refer to the same product.
     */
    fun extractGtin(barcode: String?): String? {
        if (barcode.isNullOrBlank()) return null
        val match = GTIN_REGEX.find(barcode)
        return if (match != null) "(01)${match.groupValues[1]}" else barcode
    }

    /**
     * Extract the manufacturing date from a GS1-128 barcode.
     * e.g., "(01)04030064050340(13)200423" -> "200423" (YYMMDD)
     */
    fun extractDate(barcode: String?): String? {
        if (barcode.isNullOrBlank()) return null
        val match = DATE_REGEX.find(barcode)
        return match?.groupValues?.get(1)
    }

    /**
     * Format a YYMMDD date string to a readable format.
     * e.g., "200423" -> "23/04/2020"
     */
    fun formatDate(yymmdd: String?): String? {
        if (yymmdd == null || yymmdd.length != 6) return null
        val yy = yymmdd.substring(0, 2)
        val mm = yymmdd.substring(2, 4)
        val dd = yymmdd.substring(4, 6)
        val year = if (yy.toIntOrNull() ?: 0 > 50) "19$yy" else "20$yy"
        return "$dd/$mm/$year"
    }

    private fun parseGs1Elements(data: String): String? {
        val result = StringBuilder()
        var pos = 0

        while (pos < data.length) {
            var matched = false

            if (pos + 2 <= data.length) {
                val ai2 = data.substring(pos, pos + 2)
                val fixedLen = AI_LENGTHS[ai2]
                if (fixedLen != null) {
                    val endPos = minOf(pos + 2 + fixedLen, data.length)
                    val value = data.substring(pos + 2, endPos)
                    result.append("($ai2)$value")
                    pos = endPos
                    matched = true
                } else if (AI_LENGTHS.containsKey(ai2)) {
                    val value = data.substring(pos + 2)
                    result.append("($ai2)$value")
                    pos = data.length
                    matched = true
                }
            }

            if (!matched) {
                if (result.isNotEmpty()) {
                    result.append(data.substring(pos))
                } else {
                    return null
                }
                break
            }
        }

        return if (result.isNotEmpty()) result.toString() else null
    }
}
