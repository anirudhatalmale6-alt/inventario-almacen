package com.fresenius.inventario.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.databinding.ActivitySearchBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.ui.products.ProductAdapter
import kotlinx.coroutines.launch

private enum class StockFilter { NONE, ALL, BELOW_MIN, EQUAL_MIN, ABOVE_MIN }

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repository: ProductRepository
    private lateinit var adapter: ProductAdapter
    private var activeFilter = StockFilter.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        adapter = ProductAdapter { product -> showProductDetail(product) }

        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnFilterAll.setOnClickListener { toggleFilter(StockFilter.ALL) }
        binding.btnFilterLow.setOnClickListener { toggleFilter(StockFilter.BELOW_MIN) }
        binding.btnFilterEqual.setOnClickListener { toggleFilter(StockFilter.EQUAL_MIN) }
        binding.btnFilterOk.setOnClickListener { toggleFilter(StockFilter.ABOVE_MIN) }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })

        loadProducts()
    }

    private fun loadProducts() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                repository.refresh()
                binding.progressBar.visibility = View.GONE
                binding.tvResultCount.text = "${repository.products.value.size} piezas en base de datos"
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@SearchActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleFilter(filter: StockFilter) {
        activeFilter = if (activeFilter == filter) StockFilter.NONE else filter
        updateFilterButtons()
        applyFilters()
    }

    private fun updateFilterButtons() {
        val allButtons = listOf(binding.btnFilterAll, binding.btnFilterLow, binding.btnFilterEqual, binding.btnFilterOk)
        val activeButton = when (activeFilter) {
            StockFilter.ALL -> binding.btnFilterAll
            StockFilter.BELOW_MIN -> binding.btnFilterLow
            StockFilter.EQUAL_MIN -> binding.btnFilterEqual
            StockFilter.ABOVE_MIN -> binding.btnFilterOk
            StockFilter.NONE -> null
        }
        allButtons.forEach { it.alpha = if (activeButton == null || it == activeButton) 1.0f else 0.4f }
    }

    private fun applyFilters() {
        val query = binding.etSearch.text?.toString()?.trim() ?: ""
        var results = repository.products.value

        when (activeFilter) {
            StockFilter.ALL -> {}
            StockFilter.BELOW_MIN -> results = results.filter { it.minStock > 0 && it.inStock < it.minStock }
            StockFilter.EQUAL_MIN -> results = results.filter { it.minStock > 0 && it.inStock == it.minStock }
            StockFilter.ABOVE_MIN -> results = results.filter { it.minStock > 0 && it.inStock > it.minStock }
            StockFilter.NONE -> {}
        }

        if (query.length >= 2) {
            results = results.filter { product ->
                product.partNo.contains(query, ignoreCase = true) ||
                product.description.contains(query, ignoreCase = true)
            }
        } else if (activeFilter == StockFilter.NONE) {
            adapter.submitList(emptyList())
            binding.tvResultCount.text = ""
            return
        }

        val limited = results.take(200)
        adapter.submitList(limited)

        val label = when (activeFilter) {
            StockFilter.ALL -> "piezas"
            StockFilter.BELOW_MIN -> "bajo minimo"
            StockFilter.EQUAL_MIN -> "igual al minimo"
            StockFilter.ABOVE_MIN -> "sobre minimo"
            StockFilter.NONE -> "resultados"
        }
        binding.tvResultCount.text = "${limited.size} $label" +
            if (results.size > 200) " (de ${results.size} total)" else ""
    }

    private fun showProductDetail(product: Product) {
        val barcodeStatus = if (product.barcode.isNullOrEmpty()) "Sin codigo EAN" else "EAN: ${product.barcode}"
        val stockStatus = when {
            product.minStock == 0 -> "Sin minimo definido"
            product.inStock < product.minStock -> "BAJO MINIMO (faltan ${product.minStock - product.inStock})"
            product.inStock == product.minStock -> "IGUAL AL MINIMO"
            else -> "OK (${product.inStock - product.minStock} por encima del minimo)"
        }

        AlertDialog.Builder(this)
            .setTitle(product.partNo)
            .setMessage(
                "Descripcion: ${product.description}\n\n" +
                "Grupo: ${product.itemGroup}\n\n" +
                "Stock actual: ${product.inStock}\n" +
                "Stock minimo: ${product.minStock}\n" +
                "Estado: $stockStatus\n\n" +
                barcodeStatus
            )
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
