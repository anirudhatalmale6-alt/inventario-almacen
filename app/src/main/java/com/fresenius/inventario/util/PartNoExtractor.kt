package com.fresenius.inventario.util

/**
 * Extracts Part No. from OCR text of Fresenius Medical Care labels.
 * Labels always contain "Part No." followed by the reference number.
 * References can start with letters (F, M) or be purely numeric.
 */
object PartNoExtractor {

    // Pattern: "Part No." followed by optional whitespace/newline, then the reference
    // References: alphanumeric, typically 5-10 chars (e.g., F40011904, M281001, 5052621)
    private val PART_NO_PATTERN = Regex(
        """(?i)Part\s*(?:No\.?|Number)\s*[:\s]*([A-Z]?\d{4,10}[A-Z]?)""",
        RegexOption.MULTILINE
    )

    // Fallback: look for standalone references that match Fresenius format
    private val REFERENCE_PATTERN = Regex(
        """(?<!\d)([FM]\d{5,9})(?!\d)"""
    )

    // Numeric-only references (5-7 digits, common in the Excel)
    private val NUMERIC_REF_PATTERN = Regex(
        """(?<!\d)(\d{7})(?!\d)"""
    )

    fun extract(ocrText: String): String? {
        // Try the primary pattern first
        PART_NO_PATTERN.find(ocrText)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Try F/M-prefixed references
        REFERENCE_PATTERN.find(ocrText)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Try 7-digit numeric references
        NUMERIC_REF_PATTERN.find(ocrText)?.let { match ->
            return match.groupValues[1].trim()
        }

        return null
    }

    fun extractAll(ocrText: String): List<String> {
        val results = mutableListOf<String>()

        PART_NO_PATTERN.findAll(ocrText).forEach {
            results.add(it.groupValues[1].trim())
        }

        if (results.isEmpty()) {
            REFERENCE_PATTERN.findAll(ocrText).forEach {
                results.add(it.groupValues[1].trim())
            }
        }

        return results.distinct()
    }
}
