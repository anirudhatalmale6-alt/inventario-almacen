package com.fresenius.inventario.data.local

import android.content.Context
import com.fresenius.inventario.data.remote.SheetsManager
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.util.Gs1Barcode
import com.fresenius.inventario.util.PartNoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class SyncResult(val synced: Int, val failed: Int, val total: Int)

class ProductRepository(context: Context) {

    private val localDb = LocalDatabase(context)
    private val sheetsManager = SheetsManager(context)
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    fun getSheetsManager() = sheetsManager

    fun loadLocal() {
        _products.value = localDb.loadProducts()
    }

    fun hasLocalData(): Boolean = localDb.hasProducts()

    suspend fun refresh() {
        if (localDb.hasProducts()) {
            _products.value = localDb.loadProducts()
        } else {
            syncFromSheets()
        }
    }

    suspend fun syncFromSheets() {
        sheetsManager.ensureHeaders()
        val products = sheetsManager.loadProducts()
        localDb.saveProducts(products)
        _products.value = products
    }

    suspend fun syncToSheets(): SyncResult {
        val pending = localDb.getPendingChangesGrouped()
        if (pending.isEmpty()) return SyncResult(0, 0, 0)

        val freshProducts = sheetsManager.loadProducts()

        val batchUpdates = mutableMapOf<Int, Int>()
        for ((partNo, totalDelta) in pending) {
            val product = freshProducts.find { it.partNo == partNo } ?: continue
            val newStock = (product.inStock + totalDelta).coerceAtLeast(0)
            batchUpdates[product.sheetRow] = newStock
            product.inStock = newStock
        }

        var synced = 0
        var failed = 0

        if (batchUpdates.isNotEmpty()) {
            try {
                val result = sheetsManager.batchUpdateStock(batchUpdates)
                if (result.has("error")) {
                    failed = batchUpdates.size
                } else {
                    synced = result.optInt("updated", batchUpdates.size)
                }
            } catch (_: Exception) {
                failed = batchUpdates.size
            }
        }

        localDb.saveProducts(freshProducts)
        if (failed == 0) {
            localDb.clearPendingChanges()
        }
        _products.value = freshProducts
        return SyncResult(synced, failed, pending.size)
    }

    fun getPendingCount(): Int = localDb.getPendingCount()

    fun findByPartNo(partNo: String): Product? {
        _products.value.find {
            it.partNo.equals(partNo, ignoreCase = true)
        }?.let { return it }

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

    fun updateStockLocal(product: Product, newStock: Int, delta: Int, type: String) {
        product.inStock = newStock
        localDb.updateStock(product.partNo, newStock)
        localDb.addPendingChange(product.partNo, delta, type)
        _products.value = localDb.loadProducts()
    }

    suspend fun updateStock(product: Product, newStock: Int) {
        val delta = newStock - product.inStock
        val type = if (delta >= 0) "ENTRADA" else "SALIDA"
        updateStockLocal(product, newStock, delta, type)
    }

    suspend fun linkBarcode(product: Product, barcode: String) {
        product.barcode = barcode
        localDb.updateBarcode(product.partNo, barcode)
        sheetsManager.updateBarcode(product, barcode)
    }

    suspend fun setMinStock(product: Product, minStock: Int) {
        product.minStock = minStock
        sheetsManager.updateMinStock(product, minStock)
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
        localDb.saveProducts(_products.value)
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
