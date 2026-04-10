package com.fresenius.inventario.ui.sheets

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fresenius.inventario.data.remote.SheetsManager
import com.fresenius.inventario.databinding.ActivitySheetsSetupBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.launch

class SheetsSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySheetsSetupBinding
    private lateinit var sheetsManager: SheetsManager

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            binding.tvSignInStatus.text = "Conectado como: ${account?.email}"
            binding.btnSignIn.text = "Cambiar cuenta"
            Toast.makeText(this, "Cuenta conectada: ${account?.email}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            binding.tvSignInStatus.text = "Error al iniciar sesión"
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySheetsSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sheetsManager = SheetsManager(this)

        // Check existing sign-in
        val existingAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (existingAccount != null) {
            binding.tvSignInStatus.text = "Conectado como: ${existingAccount.email}"
            binding.btnSignIn.text = "Cambiar cuenta"
        }

        // Load existing config
        sheetsManager.getSpreadsheetId()?.let {
            binding.etSpreadsheetId.setText(it)
        }
        binding.etSheetName.setText(sheetsManager.getSheetName())

        binding.btnSignIn.setOnClickListener { signIn() }
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun signIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SheetsScopes.SPREADSHEETS))
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(client.signInIntent)
    }

    private fun saveConfig() {
        val spreadsheetId = binding.etSpreadsheetId.text.toString().trim()
        val sheetName = binding.etSheetName.text.toString().trim().ifEmpty { "report" }

        if (spreadsheetId.isEmpty()) {
            Toast.makeText(this, "Introduce el ID de la hoja de cálculo", Toast.LENGTH_SHORT).show()
            return
        }

        // Extract ID from URL if user pasted a full URL
        val id = extractSpreadsheetId(spreadsheetId)
        sheetsManager.saveConfig(id, sheetName)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val spreadsheetId = binding.etSpreadsheetId.text.toString().trim()
        if (spreadsheetId.isEmpty()) {
            Toast.makeText(this, "Primero introduce el ID", Toast.LENGTH_SHORT).show()
            return
        }

        saveConfig()

        lifecycleScope.launch {
            try {
                binding.tvTestResult.text = "Conectando..."
                sheetsManager.ensureHeaders()
                val products = sheetsManager.loadProducts()
                val withBarcode = products.count { !it.barcode.isNullOrEmpty() }
                binding.tvTestResult.text =
                    "Conexión exitosa\n" +
                    "Productos encontrados: ${products.size}\n" +
                    "Con código de barras: $withBarcode\n" +
                    "Sin código: ${products.size - withBarcode}"
            } catch (e: Exception) {
                binding.tvTestResult.text = "Error: ${e.message}"
            }
        }
    }

    private fun extractSpreadsheetId(input: String): String {
        // Handle full Google Sheets URLs
        val regex = Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)")
        regex.find(input)?.let { return it.groupValues[1] }
        return input
    }
}
