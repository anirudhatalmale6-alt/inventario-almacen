package com.fresenius.inventario.model

data class StockMovement(
    val partNo: String,
    val type: MovementType,
    val quantity: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = ""
)

enum class MovementType {
    ENTRY, EXIT
}
