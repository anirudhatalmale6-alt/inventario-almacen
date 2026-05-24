package com.fresenius.inventario.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.data.local.ScanHistoryManager
import com.fresenius.inventario.databinding.ActivityManualEntryBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.ui.products.ProductAdapter
import com.fresenius.inventario.util.SoundManager
import kotlinx.coroutines.launch

class ManualEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualEntryBinding
    private lateinit var repository: ProductRepository
    private lateinit var soundManager: SoundManager
    private lateinit var historyManager: ScanHistoryManager
    private lateinit var searchAdapter: ProductAdapter
    private var selectedProduct: Product? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        soundManager = SoundManager(this)
        historyManager = ScanHistoryManager(this)

        searchAdapter = ProductAdapter { product -> selectProduct(product) }
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = searchAdapter

        binding.btnBack.setOnClickListener { finish() }

        binding.etPartNo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) searchProducts(query) else hideResults()
            }
        })

        binding.btnMinus.setOnClickListener {
            val current = binding.etQuantity.text.toString().toIntOrNull() ?: 1
            if (current > 1) binding.etQuantity.setText((current - 1).toString())
        }

        binding.btnPlus.setOnClickListener {
            val current = binding.etQuantity.text.toString().toIntOrNull() ?: 1
            binding.etQuantity.setText((current + 1).toString())
        }

        binding.btnConfirm.setOnClickListener { confirmEntry() }

        binding.overlayConfirm.setOnClickListener {
            binding.overlayConfirm.visibility = View.GONE
        }

        loadProducts()
    }

    private fun loadProducts() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                repository.refresh()
                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ManualEntryActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun searchProducts(query: String) {
        val results = repository.products.value.filter { product ->
            product.partNo.contains(query, ignoreCase = true) ||
            product.description.contains(query, ignoreCase = true)
        }.take(10)

        if (results.isNotEmpty()) {
            searchAdapter.submitList(results)
            binding.recyclerResults.visibility = View.VISIBLE
        } else {
            binding.recyclerResults.visibility = View.GONE
        }
    }

    private fun hideResults() {
        binding.recyclerResults.visibility = View.GONE
        searchAdapter.submitList(emptyList())
    }

    private fun selectProduct(product: Product) {
        selectedProduct = product
        hideResults()
        hideKeyboard()
        binding.etPartNo.clearFocus()

        binding.layoutSearch.visibility = View.GONE

        binding.tvSelectedPartNo.text = product.partNo
        binding.tvSelectedDesc.text = product.description
        binding.tvSelectedGroup.text = "Grupo: ${product.itemGroup}"
        binding.tvSelectedStock.text = "Stock actual: ${product.inStock}"
        binding.cardSelected.visibility = View.VISIBLE
        binding.layoutQuantity.visibility = View.VISIBLE

        binding.etQuantity.setText("1")
        binding.etQuantity.clearFocus()
    }

    private fun confirmEntry() {
        val product = selectedProduct ?: return
        val quantity = binding.etQuantity.text.toString().toIntOrNull() ?: 0
        if (quantity <= 0) {
            Toast.makeText(this, "Introduce una cantidad valida", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnConfirm.isEnabled = false

        lifecycleScope.launch {
            try {
                val newStock = product.inStock + quantity
                repository.updateStock(product, newStock)
                soundManager.playSuccess()

                historyManager.addEntry(product.partNo, product.description, quantity, "MANUAL")

                binding.progressBar.visibility = View.GONE
                binding.tvSelectedStock.text = "Stock actual: $newStock"

                showBigConfirmation(product.partNo, product.description, quantity, newStock)

                selectedProduct = null
                binding.cardSelected.visibility = View.GONE
                binding.layoutQuantity.visibility = View.GONE
                binding.layoutSearch.visibility = View.VISIBLE
                binding.etPartNo.setText("")
                binding.btnConfirm.isEnabled = true
                hideKeyboard()
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnConfirm.isEnabled = true
                soundManager.playError()
                Toast.makeText(this@ManualEntryActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBigConfirmation(partNo: String, description: String, quantity: Int, newStock: Int) {
        binding.tvConfirmPartNo.text = partNo
        binding.tvConfirmDesc.text = description
        binding.tvConfirmQty.text = "+$quantity unidades"
        binding.tvConfirmStock.text = "Stock actual: $newStock"

        binding.overlayConfirm.alpha = 0f
        binding.overlayConfirm.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(binding.overlayConfirm, "alpha", 0f, 1f).apply {
            duration = 200
            start()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}
