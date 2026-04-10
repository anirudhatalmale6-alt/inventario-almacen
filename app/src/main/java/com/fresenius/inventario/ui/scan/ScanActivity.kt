package com.fresenius.inventario.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
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
import com.fresenius.inventario.databinding.ActivityScanBinding
import com.fresenius.inventario.model.Product
import com.fresenius.inventario.model.ScanResult
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var repository: ProductRepository
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProductRepository(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRescan.setOnClickListener { resumeScanning() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }

        // Load products from sheets
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                repository.refresh()
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "${repository.products.value.size} productos cargados"
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Error cargando productos: ${e.message}"
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            scanAnalyzer = ScanAnalyzer { result ->
                runOnUiThread { handleScanResult(result) }
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, scanAnalyzer!!) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Error iniciando cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isScanning) return
        val now = System.currentTimeMillis()
        if (now - lastScanTime < 2000) return // Debounce 2 seconds
        lastScanTime = now

        // Vibrate for feedback
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        val barcode = result.barcode
        val partNo = result.partNo

        // First check if barcode is already linked to a product
        if (barcode != null) {
            val existingProduct = repository.findByBarcode(barcode)
            if (existingProduct != null) {
                showProductFound(existingProduct, barcode)
                return
            }
        }

        // If we got a Part No. from OCR, look it up
        if (partNo != null) {
            val product = repository.findByPartNo(partNo)
            if (product != null) {
                if (barcode != null && product.barcode.isNullOrEmpty()) {
                    // Link the barcode to this product
                    showLinkBarcodeDialog(product, barcode)
                } else {
                    showProductFound(product, barcode)
                }
                return
            }
        }

        // Nothing found
        isScanning = false
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "No encontrado"
        binding.tvResultPartNo.text = "Ref: ${partNo ?: "No detectada"}"
        binding.tvResultBarcode.text = "Código: ${barcode ?: "No detectado"}"
        binding.tvResultDescription.text = if (result.ocrFullText != null)
            "Texto OCR: ${result.ocrFullText.take(200)}" else ""
        binding.tvResultStock.text = ""
        binding.layoutActions.visibility = View.GONE
        binding.btnRescan.visibility = View.VISIBLE
    }

    private fun showProductFound(product: Product, barcode: String?) {
        isScanning = false
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "Producto encontrado"
        binding.tvResultPartNo.text = "Ref: ${product.partNo}"
        binding.tvResultBarcode.text = "Código: ${product.barcode ?: barcode ?: "Sin código"}"
        binding.tvResultDescription.text = product.description
        binding.tvResultStock.text = "Stock: ${product.inStock} | Mínimo: ${product.minStock} | Grupo: ${product.itemGroup}"
        binding.layoutActions.visibility = View.VISIBLE
        binding.btnRescan.visibility = View.VISIBLE

        if (product.inStock < product.minStock) {
            binding.tvResultStock.setTextColor(ContextCompat.getColor(this, R.color.stock_low))
        } else {
            binding.tvResultStock.setTextColor(ContextCompat.getColor(this, R.color.stock_ok))
        }

        binding.btnEntry.setOnClickListener { showStockDialog(product, true) }
        binding.btnExit.setOnClickListener { showStockDialog(product, false) }
    }

    private fun showLinkBarcodeDialog(product: Product, barcode: String) {
        isScanning = false

        AlertDialog.Builder(this)
            .setTitle("Vincular código de barras")
            .setMessage(
                "Se detectó:\n\n" +
                "Referencia: ${product.partNo}\n" +
                "Descripción: ${product.description}\n" +
                "Código de barras: $barcode\n\n" +
                "¿Vincular este código de barras a esta pieza?"
            )
            .setPositiveButton("Sí, vincular") { _, _ ->
                lifecycleScope.launch {
                    try {
                        repository.linkBarcode(product, barcode)
                        showMinStockDialog(product, barcode)
                    } catch (e: Exception) {
                        Toast.makeText(this@ScanActivity,
                            "Error vinculando: ${e.message}", Toast.LENGTH_LONG).show()
                        resumeScanning()
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> resumeScanning() }
            .setCancelable(false)
            .show()
    }

    private fun showMinStockDialog(product: Product, barcode: String) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(product.minStock.toString())
            hint = "Stock mínimo"
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Stock mínimo")
            .setMessage("Define el stock mínimo para:\n${product.partNo} - ${product.description}")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val minStock = input.text.toString().toIntOrNull() ?: 1
                lifecycleScope.launch {
                    try {
                        repository.setMinStock(product, minStock)
                        Toast.makeText(this@ScanActivity,
                            "Vinculado: ${product.partNo} → $barcode\nStock mínimo: $minStock",
                            Toast.LENGTH_LONG).show()
                        showProductFound(product, barcode)
                    } catch (e: Exception) {
                        Toast.makeText(this@ScanActivity,
                            "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        resumeScanning()
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showStockDialog(product: Product, isEntry: Boolean) {
        val title = if (isEntry) "Entrada de stock" else "Salida de stock"
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
            hint = "Cantidad"
        }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("${product.partNo} - ${product.description}\nStock actual: ${product.inStock}")
            .setView(container)
            .setPositiveButton("Confirmar") { _, _ ->
                val qty = input.text.toString().toIntOrNull() ?: 0
                if (qty <= 0) return@setPositiveButton

                val newStock = if (isEntry) product.inStock + qty else (product.inStock - qty).coerceAtLeast(0)

                lifecycleScope.launch {
                    try {
                        repository.updateStock(product, newStock)
                        val action = if (isEntry) "Entrada" else "Salida"
                        Toast.makeText(this@ScanActivity,
                            "$action: $qty uds. de ${product.partNo}\nNuevo stock: $newStock",
                            Toast.LENGTH_LONG).show()

                        if (newStock < product.minStock) {
                            Toast.makeText(this@ScanActivity,
                                "⚠ ALERTA: Stock bajo mínimo (${product.minStock})",
                                Toast.LENGTH_LONG).show()
                        }

                        showProductFound(product, product.barcode)
                    } catch (e: Exception) {
                        Toast.makeText(this@ScanActivity,
                            "Error actualizando stock: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resumeScanning() {
        isScanning = true
        binding.resultCard.visibility = View.GONE
        binding.tvStatus.text = "Apunta la cámara a una etiqueta..."
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanAnalyzer?.close()
    }
}
