package com.fresenius.inventario.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fresenius.inventario.R
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.databinding.ActivityFastScanBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.model.ScanResult
import com.fresenius.inventario.util.SoundManager
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fast barcode-only scan activity for registering entries/exits.
 * Designed for speed: scan barcode -> identify product -> enter quantity -> done.
 * Uses sounds for immediate feedback (success beep / error tone).
 */
class FastScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FastScanActivity"
    }

    private lateinit var binding: ActivityFastScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var repository: ProductRepository
    private lateinit var soundManager: SoundManager
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true
    private var productsLoaded = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Se necesita permiso de camara", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFastScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        soundManager = SoundManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRescan.setOnClickListener { resumeScanning() }

        loadProductsAndStartCamera()
    }

    private fun loadProductsAndStartCamera() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Cargando productos..."

        lifecycleScope.launch {
            try {
                repository.refresh()
                val count = repository.products.value.size
                productsLoaded = true
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Escanea el codigo de barras"

                if (ContextCompat.checkSelfPermission(this@FastScanActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startCamera()
                } else {
                    requestPermission.launch(Manifest.permission.CAMERA)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Error: ${e.message}"
                soundManager.playError()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                scanAnalyzer = ScanAnalyzer { result ->
                    runOnUiThread {
                        try {
                            handleScanResult(result)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error: ${e.message}", e)
                        }
                    }
                }
                // Barcode-only mode for fast scanning
                scanAnalyzer?.scanMode = ScanMode.BARCODE_ONLY

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, scanAnalyzer!!) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera error: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isScanning || !productsLoaded) return

        val now = System.currentTimeMillis()
        if (now - lastScanTime < 1500) return
        lastScanTime = now

        val barcode = result.barcode ?: return

        Log.d(TAG, "Barcode scanned: $barcode")

        // Find product by barcode
        val product = repository.findByBarcode(barcode)
        if (product != null) {
            // SUCCESS - product found
            isScanning = false
            soundManager.playSuccess()
            showProductFound(product)
        } else {
            // ERROR - barcode not recognized
            soundManager.playError()
            showError("Codigo no reconocido:\n$barcode")
        }
    }

    private fun showProductFound(product: Product) {
        binding.layoutResult.visibility = View.VISIBLE
        binding.btnRescan.visibility = View.VISIBLE

        binding.tvProductInfo.text = product.partNo
        binding.tvProductDesc.text = product.description
        binding.tvStockInfo.text = "Stock: ${product.inStock} | Min: ${product.minStock}"

        if (product.inStock < product.minStock) {
            binding.tvStockInfo.setTextColor(ContextCompat.getColor(this, R.color.stock_low))
        } else {
            binding.tvStockInfo.setTextColor(ContextCompat.getColor(this, R.color.stock_ok))
        }

        binding.btnEntry.setOnClickListener { showStockDialog(product, true) }
        binding.btnExit.setOnClickListener { showStockDialog(product, false) }

        binding.tvStatus.text = product.partNo + " - " + product.description
        binding.tvFeedback.visibility = View.GONE
    }

    private fun showError(message: String) {
        // Show large error overlay on camera
        binding.tvFeedback.text = message
        binding.tvFeedback.setBackgroundColor(0xCC_C62828.toInt()) // semi-transparent red
        binding.tvFeedback.visibility = View.VISIBLE

        // Hide after 2 seconds
        binding.tvFeedback.postDelayed({
            binding.tvFeedback.visibility = View.GONE
        }, 2000)
    }

    private fun showStockDialog(product: Product, isEntry: Boolean) {
        val title = if (isEntry) "Entrada de stock" else "Salida de stock"

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Cantidad"
            setText("") // Empty - user types directly
            requestFocus()
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("${product.partNo} - ${product.description}\nStock actual: ${product.inStock}")
            .setView(container)
            .setPositiveButton("Confirmar") { _, _ ->
                val qty = input.text.toString().toIntOrNull() ?: 0
                if (qty <= 0) {
                    Toast.makeText(this, "Introduce una cantidad valida", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newStock = if (isEntry) product.inStock + qty else (product.inStock - qty).coerceAtLeast(0)

                lifecycleScope.launch {
                    try {
                        repository.updateStock(product, newStock)
                        soundManager.playSuccess()
                        val action = if (isEntry) "Entrada" else "Salida"
                        Toast.makeText(this@FastScanActivity,
                            "$action: $qty uds. | Nuevo stock: $newStock",
                            Toast.LENGTH_SHORT).show()

                        if (newStock < product.minStock) {
                            Toast.makeText(this@FastScanActivity,
                                "ALERTA: Stock bajo minimo (${product.minStock})",
                                Toast.LENGTH_LONG).show()
                        }

                        // Update displayed stock
                        binding.tvStockInfo.text = "Stock: $newStock | Min: ${product.minStock}"
                        if (newStock < product.minStock) {
                            binding.tvStockInfo.setTextColor(ContextCompat.getColor(this@FastScanActivity, R.color.stock_low))
                        } else {
                            binding.tvStockInfo.setTextColor(ContextCompat.getColor(this@FastScanActivity, R.color.stock_ok))
                        }
                    } catch (e: Exception) {
                        soundManager.playError()
                        Toast.makeText(this@FastScanActivity,
                            "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()

        // Auto-show keyboard when dialog opens
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun resumeScanning() {
        isScanning = true
        binding.layoutResult.visibility = View.GONE
        binding.btnRescan.visibility = View.GONE
        binding.tvFeedback.visibility = View.GONE
        binding.tvStatus.text = "Escanea el codigo de barras"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanAnalyzer?.close()
        soundManager.release()
    }
}
