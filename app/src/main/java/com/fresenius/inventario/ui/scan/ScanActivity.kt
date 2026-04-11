package com.fresenius.inventario.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
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

    companion object {
        private const val TAG = "ScanActivity"
    }

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var repository: ProductRepository
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true
    private var productsLoaded = false

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

        // Load products first, then start camera
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
                binding.tvStatus.text = "$count productos cargados - Apunta la cámara a una etiqueta"
                Log.d(TAG, "Loaded $count products")

                // Now start camera
                if (ContextCompat.checkSelfPermission(this@ScanActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startCamera()
                } else {
                    requestPermission.launch(Manifest.permission.CAMERA)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading products: ${e.message}", e)
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Error cargando productos: ${e.message}"
                Toast.makeText(this@ScanActivity,
                    "Error conectando con Google Sheets.\nVerifica tu conexión a internet.",
                    Toast.LENGTH_LONG).show()
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
                            Log.e(TAG, "Error handling scan result: ${e.message}", e)
                            binding.tvStatus.text = "Error: ${e.message}"
                        }
                    }
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, scanAnalyzer!!) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera: ${e.message}", e)
                Toast.makeText(this, "Error iniciando cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isScanning) return
        if (!productsLoaded) return

        val now = System.currentTimeMillis()
        if (now - lastScanTime < 2000) return // Debounce 2 seconds
        lastScanTime = now

        Log.d(TAG, "Scan result: barcode=${result.barcode}, partNo=${result.partNo}, ocrText=${result.ocrFullText?.take(50)}")

        // Vibrate for feedback
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Vibration not critical
        }

        val barcode = result.barcode
        val partNo = result.partNo

        // First check if barcode is already linked to a product
        if (barcode != null) {
            val existingProduct = repository.findByBarcode(barcode)
            if (existingProduct != null) {
                Log.d(TAG, "Found product by barcode: ${existingProduct.partNo}")
                showProductFound(existingProduct, barcode)
                return
            }
        }

        // If we got a Part No. from OCR, look it up (includes fuzzy matching)
        if (partNo != null) {
            val product = repository.findByPartNo(partNo)
            if (product != null) {
                val fuzzyNote = if (!product.partNo.equals(partNo, ignoreCase = true)) {
                    Log.d(TAG, "Fuzzy match: OCR read '$partNo', matched to '${product.partNo}'")
                    " (OCR leyó: $partNo)"
                } else ""
                Log.d(TAG, "Found product by Part No: ${product.partNo}$fuzzyNote")
                if (barcode != null && product.barcode.isNullOrEmpty()) {
                    showLinkBarcodeDialog(product, barcode, fuzzyNote)
                } else {
                    showProductFound(product, barcode, fuzzyNote)
                }
                return
            }
        }

        // Nothing found - show what was detected
        isScanning = false
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "No encontrado en la base de datos"
        binding.tvResultPartNo.text = "Ref detectada: ${partNo ?: "No detectada"}"
        binding.tvResultBarcode.text = "Código: ${barcode ?: "No detectado"}"
        binding.tvResultDescription.text = if (!result.ocrFullText.isNullOrEmpty())
            "Texto OCR detectado:\n${result.ocrFullText.take(300)}" else "No se detectó texto"
        binding.tvResultStock.text = ""
        binding.layoutActions.visibility = View.GONE
        binding.btnRescan.visibility = View.VISIBLE
    }

    private fun showProductFound(product: Product, barcode: String?, fuzzyNote: String = "") {
        isScanning = false
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "Producto encontrado"
        binding.tvResultPartNo.text = "Ref: ${product.partNo}$fuzzyNote"
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

    private fun showLinkBarcodeDialog(product: Product, barcode: String, fuzzyNote: String = "") {
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
                        Log.e(TAG, "Error linking barcode: ${e.message}", e)
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
                            "Vinculado: ${product.partNo} -> $barcode\nStock mínimo: $minStock",
                            Toast.LENGTH_LONG).show()
                        showProductFound(product, barcode)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting min stock: ${e.message}", e)
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
                                "ALERTA: Stock bajo mínimo (${product.minStock})",
                                Toast.LENGTH_LONG).show()
                        }

                        showProductFound(product, product.barcode)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating stock: ${e.message}", e)
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
