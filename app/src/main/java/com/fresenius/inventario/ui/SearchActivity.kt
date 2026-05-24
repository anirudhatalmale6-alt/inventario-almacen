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

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var repository: ProductRepository
    private lateinit var adapter: ProductAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        adapter = ProductAdapter { product -> showProductDetail(product) }

        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) searchProducts(query)
                else {
                    adapter.submitList(emptyList())
                    binding.tvResultCount.text = ""
                }
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

    private fun searchProducts(query: String) {
        val results = repository.products.value.filter { product ->
            product.partNo.contains(query, ignoreCase = true) ||
            product.description.contains(query, ignoreCase = true)
        }.take(50)

        adapter.submitList(results)
        binding.tvResultCount.text = "${results.size} resultados"
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
