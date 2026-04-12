package com.fresenius.inventario.model

data class Product(
    val partNo: String,
    val description: String,
    val itemGroup: String,
    var inStock: Int,
    var minStock: Int = 1,
    var barcode: String? = null,
    var sheetRow: Int = -1
)
