package com.fresenius.inventario.data.local

import android.content.Context
import com.fresenius.inventario.data.remote.SheetsManager
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.util.Gs1Barcode
import com.fresenius.inventario.util.PartNoExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProductRepository(context: Context) {

    private val sheetsManager = SheetsManager(context)
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    fun getSheetsManager() = sheetsManager

    suspend fun refresh() {
        sheetsManager.ensureHeaders()
        _products.value = sheetsManager.loadProducts()
    }

    fun findByPartNo(partNo: String): Product? {
        // Exact match first
        _products.value.find {
            it.partNo.equals(partNo, ignoreCase = true)
        }?.let { return it }

        // Fuzzy match (OCR can misread digits: 4/6, 3/8, 5/6, etc.)
        val knownPartNos = _products.value.map { it.partNo }
        val fuzzyMatch = PartNoExtractor.findClosestMatch(partNo, knownPartNos)
        if (fuzzyMatch != null) {
            return _products.value.find { it.partNo == fuzzyMatch }
        }
        return null
    }

    fun findByBarcode(barcode: String): Product? {
        val scannedEan = Gs1Barcode.extractEan13(barcode)

        for (product in _products.value) {
            val storedBarcodes = product.barcode ?: continue
            val eanList = storedBarcodes.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            for (ean in eanList) {
                if (ean.equals(barcode, ignoreCase = true)) return product
                if (scannedEan != null && ean.equals(scannedEan, ignoreCase = true)) return product
                val storedEan = Gs1Barcode.extractEan13(ean)
                if (storedEan != null && scannedEan != null && storedEan.equals(scannedEan, ignoreCase = true)) return product
            }
        }
        return null
    }

    suspend fun linkBarcode(product: Product, barcode: String) {
        product.barcode = barcode
        sheetsManager.updateBarcode(product, barcode)
    }

    suspend fun setMinStock(product: Product, minStock: Int) {
        product.minStock = minStock
        sheetsManager.updateMinStock(product, minStock)
    }

    suspend fun updateStock(product: Product, newStock: Int) {
        product.inStock = newStock
        sheetsManager.updateStock(product, newStock)
    }

    suspend fun addProduct(partNo: String, description: String, itemGroup: String, barcode: String, minStock: Int): Product {
        val sheetRow = sheetsManager.addProduct(partNo, description, itemGroup, barcode, minStock)
        val product = Product(
            partNo = partNo,
            description = description,
            itemGroup = itemGroup,
            inStock = 0,
            minStock = minStock,
            barcode = barcode.ifEmpty { null },
            sheetRow = sheetRow
        )
        _products.value = _products.value + product
        return product
    }

    fun getProductsWithLowStock(): List<Product> {
        return _products.value.filter { it.inStock < it.minStock }
    }

    fun getProductsWithBarcode(): List<Product> {
        return _products.value.filter { !it.barcode.isNullOrEmpty() }
    }

    fun getProductsWithoutBarcode(): List<Product> {
        return _products.value.filter { it.barcode.isNullOrEmpty() }
    }

    fun getItemGroups(): List<String> {
        return _products.value.map { it.itemGroup }.distinct().sorted()
    }
}
