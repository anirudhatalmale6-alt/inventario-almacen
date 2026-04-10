package com.fresenius.inventario.model

data class ScanResult(
    val barcode: String? = null,
    val barcodeFormat: String? = null,
    val partNo: String? = null,
    val ocrFullText: String? = null
)
