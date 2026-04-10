package com.fresenius.inventario.model

data class Product(
    val partNo: String,
    val description: String,
    val itemGroup: String,
    var inStock: Int,
    val responsible: String,
    var barcode: String? = null,
    var minStock: Int = 1,
    var sheetRow: Int = -1 // row index in Google Sheets (0-based, excluding header)
)
