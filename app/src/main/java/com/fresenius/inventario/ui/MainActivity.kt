package com.fresenius.inventario.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.R
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.databinding.ActivityMainBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.ui.products.ProductAdapter
import com.fresenius.inventario.ui.scan.ScanActivity
import com.fresenius.inventario.ui.sheets.SheetsSetupActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        binding.fabScan.setOnClickListener {
            if (!repository.getSheetsManager().isConfigured()) {
                Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SheetsSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SheetsSetupActivity::class.java))
        }

        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnLowStock.setOnClickListener { showLowStockReport() }
        binding.btnExport.setOnClickListener { exportLowStockExcel() }

        binding.swipeRefresh.setOnRefreshListener { loadProducts() }

        // Observe products
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
        val filtered = if (currentFilter == "Todos") {
            allProducts
        } else if (currentFilter == "Sin código") {
            allProducts.filter { it.barcode.isNullOrEmpty() }
        } else if (currentFilter == "Con código") {
            allProducts.filter { !it.barcode.isNullOrEmpty() }
        } else if (currentFilter == "Stock bajo") {
            allProducts.filter { it.inStock < it.minStock }
        } else {
            allProducts.filter { it.itemGroup == currentFilter }
        }
        adapter.submitList(filtered)
        binding.tvFilter.text = "Filtro: $currentFilter (${filtered.size})"
    }

    private fun updateStats() {
        val total = allProducts.size
        val withBarcode = allProducts.count { !it.barcode.isNullOrEmpty() }
        val lowStock = allProducts.count { it.inStock < it.minStock }
        binding.tvStats.text = "Total: $total | Con código: $withBarcode | Stock bajo: $lowStock"
    }

    private fun showFilterDialog() {
        val groups = mutableListOf("Todos", "Sin código", "Con código", "Stock bajo")
        groups.addAll(repository.getItemGroups())

        AlertDialog.Builder(this)
            .setTitle("Filtrar por")
            .setItems(groups.toTypedArray()) { _, which ->
                currentFilter = groups[which]
                applyFilter()
            }
            .show()
    }

    private fun showProductDetail(product: Product) {
        val status = if (product.barcode.isNullOrEmpty()) "Sin código de barras" else "Código: ${product.barcode}"
        val stockStatus = if (product.inStock < product.minStock) "⚠ BAJO MÍNIMO" else "OK"

        AlertDialog.Builder(this)
            .setTitle(product.partNo)
            .setMessage(
                "Descripción: ${product.description}\n\n" +
                "Grupo: ${product.itemGroup}\n" +
                "Stock: ${product.inStock} (Mín: ${product.minStock}) - $stockStatus\n" +
                "Responsable: ${product.responsible}\n\n" +
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
            "Stock: ${p.inStock} / Mín: ${p.minStock} (faltan ${p.minStock - p.inStock})"
        }

        AlertDialog.Builder(this)
            .setTitle("Productos con stock bajo (${lowStock.size})")
            .setMessage(message)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Exportar Excel") { _, _ -> exportLowStockExcel() }
            .show()
    }

    private fun exportLowStockExcel() {
        val lowStock = repository.getProductsWithLowStock()
        if (lowStock.isEmpty()) {
            Toast.makeText(this, "No hay productos con stock bajo", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val workbook = XSSFWorkbook()
                val sheet = workbook.createSheet("Stock Bajo")

                // Header
                val headerRow = sheet.createRow(0)
                headerRow.createCell(0).setCellValue("Part No.")
                headerRow.createCell(1).setCellValue("Descripción")
                headerRow.createCell(2).setCellValue("Grupo")
                headerRow.createCell(3).setCellValue("Stock Actual")
                headerRow.createCell(4).setCellValue("Stock Mínimo")
                headerRow.createCell(5).setCellValue("Faltan")

                // Data
                lowStock.forEachIndexed { index, product ->
                    val row = sheet.createRow(index + 1)
                    row.createCell(0).setCellValue(product.partNo)
                    row.createCell(1).setCellValue(product.description)
                    row.createCell(2).setCellValue(product.itemGroup)
                    row.createCell(3).setCellValue(product.inStock.toDouble())
                    row.createCell(4).setCellValue(product.minStock.toDouble())
                    row.createCell(5).setCellValue((product.minStock - product.inStock).toDouble())
                }

                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val fileName = "stock_bajo_${dateFormat.format(Date())}.xlsx"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)

                FileOutputStream(file).use { workbook.write(it) }
                workbook.close()

                Toast.makeText(this@MainActivity,
                    "Archivo guardado en Descargas:\n$fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Error exportando: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
