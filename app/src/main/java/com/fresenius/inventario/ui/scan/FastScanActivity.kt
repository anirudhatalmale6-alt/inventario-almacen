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
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.R
import com.fresenius.inventario.data.local.ProductRepository
import com.fresenius.inventario.data.local.ScanHistoryManager
import com.fresenius.inventario.databinding.ActivityFastScanBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.model.ScanResult
import com.fresenius.inventario.ui.history.HistoryAdapter
import com.fresenius.inventario.util.SoundManager
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FastScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FastScanActivity"
        private const val FEEDBACK_DURATION = 1500L
    }

    private lateinit var binding: ActivityFastScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var repository: ProductRepository
    private lateinit var soundManager: SoundManager
    private lateinit var historyManager: ScanHistoryManager
    private lateinit var recentAdapter: HistoryAdapter
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true
    private var productsLoaded = false
    private var isEntryMode = true
    private var quantity = 1

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
        historyManager = ScanHistoryManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        recentAdapter = HistoryAdapter()
        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        binding.btnBack.setOnClickListener { finish() }

        binding.btnEntry.setOnClickListener {
            isEntryMode = true
            updateModeButtons()
        }
        binding.btnExit.setOnClickListener {
            isEntryMode = false
            updateModeButtons()
        }

        binding.btnQtyMinus.setOnClickListener {
            if (quantity > 1) {
                quantity--
                binding.tvQuantity.text = quantity.toString()
            }
        }
        binding.btnQtyPlus.setOnClickListener {
            quantity++
            binding.tvQuantity.text = quantity.toString()
        }

        updateModeButtons()
        refreshRecentHistory()
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

    private fun refreshRecentHistory() {
        val recent = historyManager.getRecent(4)
        if (recent.isNotEmpty()) {
            recentAdapter.submitList(recent)
            binding.dividerHistory.visibility = View.VISIBLE
            binding.tvRecentLabel.visibility = View.VISIBLE
            binding.recyclerRecent.visibility = View.VISIBLE
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

        val product = repository.findByBarcode(barcode)
        if (product != null) {
            soundManager.playSuccess()
            autoUpdateStock(product)
        } else {
            soundManager.playError()
            showFeedback(
                "Codigo no reconocido",
                0xCC_C62828.toInt(),
                barcode
            )
        }
    }

    private fun autoUpdateStock(product: Product) {
        val qty = quantity
        val newStock = if (isEntryMode) {
            product.inStock + qty
        } else {
            (product.inStock - qty).coerceAtLeast(0)
        }

        val type = if (isEntryMode) "ENTRADA" else "SALIDA"

        lifecycleScope.launch {
            try {
                repository.updateStock(product, newStock)

                historyManager.addEntry(product.partNo, product.description, qty, type)
                refreshRecentHistory()

                val sign = if (isEntryMode) "+" else "-"
                val actionText = "$sign$qty ${product.partNo}"
                val bgColor = if (isEntryMode) 0xCC_2E7D32.toInt() else 0xCC_E65100.toInt()

                showFeedback(
                    "$actionText\n${product.description}\nStock: $newStock",
                    bgColor,
                    null
                )

                if (newStock < product.minStock) {
                    binding.tvRecentLabel.text = "ALERTA: Stock bajo minimo (min: ${product.minStock})"
                    binding.tvRecentLabel.setTextColor(ContextCompat.getColor(this@FastScanActivity, R.color.stock_low))
                }

                // Reset quantity to 1 after scan
                quantity = 1
                binding.tvQuantity.text = "1"

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

    @Suppress("UNUSED_PARAMETER")
    private fun showFeedback(message: String, bgColor: Int, subtitle: String?) {
        binding.tvFeedback.text = message
        binding.tvFeedback.setBackgroundColor(bgColor)
        binding.tvFeedback.visibility = View.VISIBLE

        binding.tvFeedback.removeCallbacks(resumeRunnable)
        binding.tvFeedback.postDelayed(resumeRunnable, FEEDBACK_DURATION)
    }

    private val resumeRunnable = Runnable {
        binding.tvFeedback.visibility = View.GONE
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
