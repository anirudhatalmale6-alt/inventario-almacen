package com.fresenius.inventario.ui.sheets

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fresenius.inventario.data.remote.SheetsManager
import com.fresenius.inventario.databinding.ActivitySheetsSetupBinding
import kotlinx.coroutines.launch

class SheetsSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySheetsSetupBinding
    private lateinit var sheetsManager: SheetsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySheetsSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sheetsManager = SheetsManager(this)

        // Load existing config
        sheetsManager.getScriptUrl()?.let {
            binding.etScriptUrl.setText(it)
        }

        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun saveConfig() {
        val url = binding.etScriptUrl.text.toString().trim()

        if (url.isEmpty()) {
            Toast.makeText(this, "Introduce la URL del script", Toast.LENGTH_SHORT).show()
            return
        }

        if (!url.contains("script.google.com")) {
            Toast.makeText(this, "La URL debe ser de Google Apps Script", Toast.LENGTH_LONG).show()
            return
        }

        sheetsManager.saveConfig(url)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val url = binding.etScriptUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Primero introduce la URL", Toast.LENGTH_SHORT).show()
            return
        }

        saveConfig()

        lifecycleScope.launch {
            try {
                binding.tvTestResult.text = "Conectando..."

                // First test ping
                val pingResult = sheetsManager.testConnection()
                binding.tvTestResult.text = "Ping: $pingResult\nCargando productos..."

                // Then load products
                sheetsManager.ensureHeaders()
                val products = sheetsManager.loadProducts()
                val withBarcode = products.count { !it.barcode.isNullOrEmpty() }

                binding.tvTestResult.text =
                    "Conexión exitosa!\n\n" +
                    "Productos encontrados: ${products.size}\n" +
                    "Con código de barras: $withBarcode\n" +
                    "Sin código: ${products.size - withBarcode}\n\n" +
                    "Todo listo para escanear!"
            } catch (e: Exception) {
                binding.tvTestResult.text = "Error: ${e.message}"
            }
        }
    }
}
