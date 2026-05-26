package com.fresenius.inventario.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fresenius.inventario.BuildConfig
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

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

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

        binding.btnSync.setOnClickListener { performSync() }
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
        repository.loadLocal()
        val products = repository.products.value
        if (products.isNotEmpty()) {
            val total = products.size
            val withBarcode = products.count { !it.barcode.isNullOrEmpty() }
            val lowStock = products.count { it.minStock > 0 && it.inStock < it.minStock }
            binding.tvStats.text = "Total: $total | Con EAN: $withBarcode | Stock bajo: $lowStock"
        } else {
            binding.tvStats.text = "Sin datos locales - pulsa Sincronizar"
        }
        updatePendingBadge()
    }

    private fun updatePendingBadge() {
        val pending = repository.getPendingCount()
        if (pending > 0) {
            binding.tvPendingCount.text = "$pending pendientes"
            binding.tvPendingCount.visibility = View.VISIBLE
        } else {
            binding.tvPendingCount.visibility = View.GONE
        }
    }

    private fun performSync() {
        if (!repository.getSheetsManager().isConfigured()) {
            Toast.makeText(this, "Primero configura Google Sheets", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnSync.isEnabled = false
        binding.btnSync.text = "Sincronizando..."

        lifecycleScope.launch {
            try {
                val pending = repository.getPendingCount()
                if (pending > 0) {
                    val result = repository.syncToSheets()
                    if (result.failed > 0) {
                        Toast.makeText(this@MainActivity,
                            "Sincronizado: ${result.synced} OK, ${result.failed} errores",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "Sincronizado: ${result.synced} cambios subidos",
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    repository.syncFromSheets()
                    Toast.makeText(this@MainActivity,
                        "Datos actualizados: ${repository.products.value.size} piezas",
                        Toast.LENGTH_LONG).show()
                }
                loadStats()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    "Error de sincronizacion: ${e.message}",
                    Toast.LENGTH_LONG).show()
            } finally {
                binding.btnSync.isEnabled = true
                binding.btnSync.text = "Sincronizar"
            }
        }
    }
}
