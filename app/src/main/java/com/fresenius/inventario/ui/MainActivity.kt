package com.fresenius.inventario.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.databinding.ActivityMainBinding
import com.fresenius.inventario.ui.history.HistoryActivity
import com.fresenius.inventario.ui.scan.ScanActivity
import com.fresenius.inventario.ui.scan.FastScanActivity
import com.fresenius.inventario.ui.sheets.SheetsSetupActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProductRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)

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

        binding.btnSearch.setOnClickListener {
            if (!repository.getSheetsManager().isConfigured()) {
                Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SheetsSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SheetsSetupActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (repository.getSheetsManager().isConfigured()) {
            binding.layoutSetupPrompt.visibility = View.GONE
            loadStats()
        } else {
            binding.layoutSetupPrompt.visibility = View.VISIBLE
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            try {
                repository.refresh()
                val products = repository.products.value
                val total = products.size
                val withBarcode = products.count { !it.barcode.isNullOrEmpty() }
                val lowStock = products.count { it.inStock < it.minStock }
                binding.tvStats.text = "Total: $total | Con EAN: $withBarcode | Stock bajo: $lowStock"
            } catch (e: Exception) {
                binding.tvStats.text = "Error cargando datos"
            }
        }
    }
}
