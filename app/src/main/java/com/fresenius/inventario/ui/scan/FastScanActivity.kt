package com.fresenius.inventario.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
 * Fast barcode-only scan activity - works like a supermarket scanner.
 * Scan barcode -> beep -> auto-add 1 unit -> ready for next scan.
 * No keyboard, no buttons to press. Just scan and go.
 */
class FastScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FastScanActivity"
        private const val FEEDBACK_DURATION = 1500L // ms to show feedback
    }

    private lateinit var binding: ActivityFastScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var repository: ProductRepository
    private lateinit var soundManager: SoundManager
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true
    private var productsLoaded = false
    // Track: true = entrada mode, false = salida mode
    private var isEntryMode = true

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

        // Entry/Exit mode toggle buttons
        binding.btnEntry.setOnClickListener {
            isEntryMode = true
            updateModeButtons()
        }
        binding.btnExit.setOnClickListener {
            isEntryMode = false
            updateModeButtons()
        }

        // Start in entry mode
        updateModeButtons()

        loadProductsAndStartCamera()
    }

    private fun updateModeButtons() {
        if (isEntryMode) {
            binding.btnEntry.alpha = 1.0f
            binding.btnExit.alpha = 0.4f
            binding.tvStatus.text = "ENTRADA: Escanea el codigo de barras"
        } else {
            binding.btnEntry.alpha = 0.4f
            binding.btnExit.alpha = 1.0f
            binding.tvStatus.text = "SALIDA: Escanea el codigo de barras"
        }
    }

    private fun loadProductsAndStartCamera() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Cargando productos..."

        lifecycleScope.launch {
            try {
                repository.refresh()
                productsLoaded = true
                binding.progressBar.visibility = View.GONE
                updateModeButtons()

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

        // Find product by barcode (matches by GTIN, ignores date)
        val product = repository.findByBarcode(barcode)
        if (product != null) {
            // SUCCESS - auto add/remove 1 unit
            soundManager.playSuccess()
            autoUpdateStock(product)
        } else {
            // ERROR - barcode not recognized
            soundManager.playError()
            showFeedback(
                "Codigo no reconocido",
                0xCC_C62828.toInt(), // red
                barcode
            )
        }
    }

    private fun autoUpdateStock(product: Product) {
        val newStock = if (isEntryMode) {
            product.inStock + 1
        } else {
            (product.inStock - 1).coerceAtLeast(0)
        }

        val action = if (isEntryMode) "+1" else "-1"

        lifecycleScope.launch {
            try {
                repository.updateStock(product, newStock)

                val actionText = if (isEntryMode) "1 Unidad sumada al stock" else "1 Unidad restada del stock"
                val bgColor = if (isEntryMode) 0xCC_2E7D32.toInt() else 0xCC_E65100.toInt()

                showFeedback(
                    "$actionText\n${product.partNo}\nStock: $newStock",
                    bgColor,
                    null
                )

                // Show low stock warning
                if (newStock < product.minStock) {
                    binding.tvProductDesc.text = "ALERTA: Stock bajo minimo (min: ${product.minStock})"
                    binding.tvProductDesc.setTextColor(ContextCompat.getColor(this@FastScanActivity, R.color.stock_low))
                }

            } catch (e: Exception) {
                soundManager.playError()
                showFeedback(
                    "Error: ${e.message}",
                    0xCC_C62828.toInt(),
                    null
                )
            }
        }
    }

    private fun showFeedback(message: String, bgColor: Int, subtitle: String?) {
        // Show large feedback overlay on camera
        binding.tvFeedback.text = message
        binding.tvFeedback.setBackgroundColor(bgColor)
        binding.tvFeedback.visibility = View.VISIBLE

        // Show product info at bottom
        binding.tvProductInfo.text = message.split("\n").firstOrNull() ?: ""
        binding.tvProductDesc.text = subtitle ?: ""
        binding.layoutResult.visibility = View.VISIBLE

        // Auto-hide and resume scanning after delay
        binding.tvFeedback.removeCallbacks(resumeRunnable)
        binding.tvFeedback.postDelayed(resumeRunnable, FEEDBACK_DURATION)
    }

    private val resumeRunnable = Runnable {
        binding.tvFeedback.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        updateModeButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.tvFeedback.removeCallbacks(resumeRunnable)
        cameraExecutor.shutdown()
        scanAnalyzer?.close()
        soundManager.release()
    }
}
