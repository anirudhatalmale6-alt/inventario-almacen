package com.fresenius.inventario.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
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
import com.fresenius.inventario.util.SoundManager
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
    private lateinit var soundManager: SoundManager
    private var scanAnalyzer: ScanAnalyzer? = null
    private var lastScanTime = 0L
    private var isScanning = true
    private var productsLoaded = false

    // For two-step linking flow
    private var pendingLinkProduct: Product? = null

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
                binding.tvStatus.text = "PASO 1: Apunta la cámara al texto 'Part No.' de la etiqueta"
                Log.d(TAG, "Loaded $count products")

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
                binding.tvStatus.text = "Error: ${e.message}"
                Toast.makeText(this@ScanActivity,
                    "Error conectando. Verifica tu conexión.", Toast.LENGTH_LONG).show()
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
                            Log.e(TAG, "Error handling result: ${e.message}", e)
                        }
                    }
                }
                // Start in OCR-only mode (Step 1)
                scanAnalyzer?.scanMode = ScanMode.OCR_ONLY

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
                Toast.makeText(this, "Error cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScanResult(result: ScanResult) {
        if (!isScanning) return
        if (!productsLoaded) return

        val now = System.currentTimeMillis()
        if (now - lastScanTime < 1500) return
        lastScanTime = now

        val currentMode = scanAnalyzer?.scanMode ?: ScanMode.OCR_ONLY

        when (currentMode) {
            ScanMode.OCR_ONLY -> handleOcrResult(result)
            ScanMode.BARCODE_ONLY -> handleBarcodeResult(result)
            ScanMode.BOTH -> handleOcrResult(result)
        }
    }

    // STEP 1: OCR detected a Part No.
    private fun handleOcrResult(result: ScanResult) {
        val partNo = result.partNo ?: return

        Log.d(TAG, "OCR detected Part No: $partNo")

        val product = repository.findByPartNo(partNo)
        if (product != null) {
            val fuzzyNote = if (!product.partNo.equals(partNo, ignoreCase = true)) {
                " (OCR leyó: $partNo)"
            } else ""

            isScanning = false
            soundManager.playSuccess()

            if (product.barcode.isNullOrEmpty()) {
                showScanBarcodePrompt(product, fuzzyNote)
            } else {
                showProductFound(product, product.barcode, fuzzyNote)
            }
        } else {
            isScanning = false
            soundManager.playError()
            showCreateProductPrompt(partNo)
        }
    }

    // Show prompt to scan barcode (Step 2)
    private fun showScanBarcodePrompt(product: Product, fuzzyNote: String) {
        pendingLinkProduct = product

        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "Producto encontrado"
        binding.tvResultPartNo.text = "Ref: ${product.partNo}$fuzzyNote"
        binding.tvResultBarcode.text = "Sin código de barras vinculado"
        binding.tvResultDescription.text = product.description
        binding.tvResultStock.text = "Stock: ${product.inStock} | Grupo: ${product.itemGroup}"

        // Show "Scan barcode" button instead of entry/exit
        binding.layoutActions.visibility = View.VISIBLE
        binding.btnEntry.text = "Escanear código de barras"
        binding.btnEntry.setOnClickListener { startBarcodeScan() }
        binding.btnExit.visibility = View.GONE
        binding.btnRescan.visibility = View.VISIBLE

        binding.tvStatus.text = "Producto encontrado. Pulsa el botón para escanear el código de barras."
    }

    // Product not found - offer to create it
    private fun showCreateProductPrompt(partNo: String) {
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "Referencia no encontrada"
        binding.tvResultPartNo.text = "Ref detectada: $partNo"
        binding.tvResultBarcode.text = ""
        binding.tvResultDescription.text = "Esta referencia no existe en tu hoja de cálculo."
        binding.tvResultStock.text = ""

        binding.layoutActions.visibility = View.VISIBLE
        binding.btnEntry.text = "Crear producto nuevo"
        binding.btnEntry.setOnClickListener { showCreateProductDialog(partNo) }
        binding.btnExit.visibility = View.GONE
        binding.btnRescan.visibility = View.VISIBLE

        binding.tvStatus.text = "Producto no encontrado. Puedes crearlo o escanear otra etiqueta."
    }

    private fun showCreateProductDialog(partNo: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }

        val etPartNo = android.widget.EditText(this).apply {
            hint = "Referencia (Part No.)"
            setText(partNo)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        layout.addView(etPartNo)

        val etDescription = android.widget.EditText(this).apply {
            hint = "Descripción"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(etDescription)

        val etGroup = android.widget.EditText(this).apply {
            hint = "Grupo (ej: SP 5008, SP Small Parts...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(etGroup)

        val etMinStock = android.widget.EditText(this).apply {
            hint = "Stock mínimo"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(etMinStock)

        AlertDialog.Builder(this)
            .setTitle("Crear producto nuevo")
            .setView(layout)
            .setPositiveButton("Crear y escanear código") { _, _ ->
                val newPartNo = etPartNo.text.toString().trim()
                val desc = etDescription.text.toString().trim()
                val group = etGroup.text.toString().trim()
                val minStock = etMinStock.text.toString().toIntOrNull() ?: 1

                if (newPartNo.isEmpty()) {
                    Toast.makeText(this, "La referencia es obligatoria", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        binding.tvStatus.text = "Creando producto..."
                        val product = repository.addProduct(newPartNo, desc, group, "", minStock)
                        Toast.makeText(this@ScanActivity,
                            "Producto $newPartNo creado", Toast.LENGTH_SHORT).show()
                        // Now offer to scan barcode for this new product
                        showScanBarcodePrompt(product, "")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating product: ${e.message}", e)
                        Toast.makeText(this@ScanActivity,
                            "Error creando: ${e.message}", Toast.LENGTH_LONG).show()
                        resumeScanning()
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> resumeScanning() }
            .show()
    }

    // Switch to barcode-only scanning mode
    private fun startBarcodeScan() {
        isScanning = true
        scanAnalyzer?.scanMode = ScanMode.BARCODE_ONLY
        binding.resultCard.visibility = View.GONE
        binding.tvStatus.text = "PASO 2: Apunta la cámara al CÓDIGO DE BARRAS de la etiqueta"

        Toast.makeText(this,
            "Apunta directamente al código de barras", Toast.LENGTH_LONG).show()
    }

    // STEP 2: Barcode detected
    private fun handleBarcodeResult(result: ScanResult) {
        val barcode = result.barcode ?: return
        val product = pendingLinkProduct ?: return

        Log.d(TAG, "Barcode detected for linking: $barcode -> ${product.partNo}")

        isScanning = false
        scanAnalyzer?.scanMode = ScanMode.OCR_ONLY // Reset for next scan

        // Check if barcode is already linked to another product
        val existingProduct = repository.findByBarcode(barcode)
        if (existingProduct != null && existingProduct.partNo != product.partNo) {
            AlertDialog.Builder(this)
                .setTitle("Código ya vinculado")
                .setMessage("Este código de barras ya está vinculado a:\n${existingProduct.partNo} - ${existingProduct.description}\n\n¿Quieres reasignarlo a ${product.partNo}?")
                .setPositiveButton("Sí, reasignar") { _, _ -> linkBarcodeAndSetMinStock(product, barcode) }
                .setNegativeButton("No, cancelar") { _, _ -> resumeScanning() }
                .show()
            return
        }

        // Show confirmation dialog
        AlertDialog.Builder(this)
            .setTitle("Vincular código de barras")
            .setMessage(
                "Referencia: ${product.partNo}\n" +
                "Descripción: ${product.description}\n" +
                "Código de barras: $barcode\n\n" +
                "¿Vincular este código de barras a esta pieza?"
            )
            .setPositiveButton("Sí, vincular") { _, _ -> linkBarcodeAndSetMinStock(product, barcode) }
            .setNegativeButton("Cancelar") { _, _ -> resumeScanning() }
            .setCancelable(false)
            .show()
    }

    private fun linkBarcodeAndSetMinStock(product: Product, barcode: String) {
        // First link barcode, then ask for min stock
        lifecycleScope.launch {
            try {
                repository.linkBarcode(product, barcode)
                showMinStockDialog(product, barcode)
            } catch (e: Exception) {
                Log.e(TAG, "Error linking: ${e.message}", e)
                Toast.makeText(this@ScanActivity,
                    "Error vinculando: ${e.message}", Toast.LENGTH_LONG).show()
                resumeScanning()
            }
        }
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
                        pendingLinkProduct = null
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

    private fun showProductFound(product: Product, barcode: String?, fuzzyNote: String = "") {
        isScanning = false
        binding.resultCard.visibility = View.VISIBLE
        binding.tvResultTitle.text = "Producto encontrado"
        binding.tvResultPartNo.text = "Ref: ${product.partNo}$fuzzyNote"
        binding.tvResultBarcode.text = "Código: ${product.barcode ?: barcode ?: "Sin código"}"
        binding.tvResultDescription.text = product.description
        binding.tvResultStock.text = "Stock: ${product.inStock} | Mínimo: ${product.minStock} | Grupo: ${product.itemGroup}"

        // Show entry/exit buttons
        binding.layoutActions.visibility = View.VISIBLE
        binding.btnEntry.text = "+ Entrada"
        binding.btnExit.visibility = View.VISIBLE
        binding.btnRescan.visibility = View.VISIBLE

        if (product.inStock < product.minStock) {
            binding.tvResultStock.setTextColor(ContextCompat.getColor(this, R.color.stock_low))
        } else {
            binding.tvResultStock.setTextColor(ContextCompat.getColor(this, R.color.stock_ok))
        }

        binding.btnEntry.setOnClickListener { showStockDialog(product, true) }
        binding.btnExit.setOnClickListener { showStockDialog(product, false) }
    }

    private fun showStockDialog(product: Product, isEntry: Boolean) {
        val title = if (isEntry) "Entrada de stock" else "Salida de stock"
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Cantidad"
            setText("")
            requestFocus()
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
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
                        Toast.makeText(this@ScanActivity,
                            "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun resumeScanning() {
        isScanning = true
        pendingLinkProduct = null
        scanAnalyzer?.scanMode = ScanMode.OCR_ONLY
        binding.resultCard.visibility = View.GONE
        binding.btnExit.visibility = View.VISIBLE
        binding.tvStatus.text = "PASO 1: Apunta la cámara al texto 'Part No.' de la etiqueta"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanAnalyzer?.close()
        soundManager.release()
    }
}
