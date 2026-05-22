package com.fresenius.inventario.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.databinding.ActivityMainBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.ui.history.HistoryActivity
import com.fresenius.inventario.ui.products.ProductAdapter
import com.fresenius.inventario.ui.scan.ScanActivity
import com.fresenius.inventario.ui.scan.FastScanActivity
import com.fresenius.inventario.ui.sheets.SheetsSetupActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProductRepository
    private lateinit var adapter: ProductAdapter
    private var allProducts: List<Product> = emptyList()
    private var currentFilter = "Todos"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        adapter = ProductAdapter { product -> showProductDetail(product) }

        binding.recyclerProducts.layoutManager = LinearLayoutManager(this)
        binding.recyclerProducts.adapter = adapter

        binding.btnAddNew.setOnClickListener {
            if (!repository.getSheetsManager().isConfigured()) {
                Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SheetsSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.btnEntryExit.setOnClickListener {
            if (!repository.getSheetsManager().isConfigured()) {
                Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SheetsSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, FastScanActivity::class.java))
        }

        binding.btnManualEntry.setOnClickListener {
            if (!repository.getSheetsManager().isConfigured()) {
                Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SheetsSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, ManualEntryActivity::class.java))
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SheetsSetupActivity::class.java))
        }

        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnLowStock.setOnClickListener { showLowStockReport() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener { loadProducts() }

        lifecycleScope.launch {
            repository.products.collectLatest { products ->
                allProducts = products
                applyFilter()
                updateStats()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (repository.getSheetsManager().isConfigured()) {
            loadProducts()
            binding.layoutSetupPrompt.visibility = View.GONE
        } else {
            binding.layoutSetupPrompt.visibility = View.VISIBLE
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            try {
                binding.swipeRefresh.isRefreshing = true
                repository.refresh()
                binding.swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@MainActivity,
                    "Error cargando: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            "Todos" -> allProducts
            "Sin codigo EAN" -> allProducts.filter { it.barcode.isNullOrEmpty() }
            "Con codigo EAN" -> allProducts.filter { !it.barcode.isNullOrEmpty() }
            "Stock inferior al minimo" -> allProducts.filter { it.inStock < it.minStock }
            "Stock igual al minimo" -> allProducts.filter { it.inStock == it.minStock }
            "Stock mayor al minimo" -> allProducts.filter { it.inStock > it.minStock }
            else -> allProducts
        }
        adapter.submitList(filtered)
        binding.tvFilter.text = "Filtro: $currentFilter (${filtered.size})"
    }

    private fun updateStats() {
        val total = allProducts.size
        val withBarcode = allProducts.count { !it.barcode.isNullOrEmpty() }
        val lowStock = allProducts.count { it.inStock < it.minStock }
        binding.tvStats.text = "Total: $total | Con EAN: $withBarcode | Stock bajo: $lowStock"
    }

    private fun showFilterDialog() {
        val filters = arrayOf(
            "Todos",
            "Sin codigo EAN",
            "Con codigo EAN",
            "Stock inferior al minimo",
            "Stock igual al minimo",
            "Stock mayor al minimo"
        )

        AlertDialog.Builder(this)
            .setTitle("Filtrar por")
            .setItems(filters) { _, which ->
                currentFilter = filters[which]
                applyFilter()
            }
            .show()
    }

    private fun showProductDetail(product: Product) {
        val status = if (product.barcode.isNullOrEmpty()) "Sin codigo de barras" else "Codigo: ${product.barcode}"
        val stockStatus = if (product.inStock < product.minStock) "BAJO MINIMO" else "OK"

        AlertDialog.Builder(this)
            .setTitle(product.partNo)
            .setMessage(
                "Descripcion: ${product.description}\n\n" +
                "Grupo: ${product.itemGroup}\n" +
                "Stock: ${product.inStock} (Min: ${product.minStock}) - $stockStatus\n\n" +
                status
            )
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showLowStockReport() {
        val lowStock = repository.getProductsWithLowStock()
        if (lowStock.isEmpty()) {
            Toast.makeText(this, "No hay productos con stock bajo", Toast.LENGTH_SHORT).show()
            return
        }

        val message = lowStock.joinToString("\n\n") { p ->
            "${p.partNo} - ${p.description}\n" +
            "Stock: ${p.inStock} / Min: ${p.minStock} (faltan ${p.minStock - p.inStock})"
        }

        AlertDialog.Builder(this)
            .setTitle("Productos con stock bajo (${lowStock.size})")
            .setMessage(message)
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
