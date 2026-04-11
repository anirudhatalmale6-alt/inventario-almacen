package com.fresenius.inventario.data.remote

import android.content.Context
import com.fresenius.inventario.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Communicates with Google Sheets via a Google Apps Script web app.
 * No OAuth/Google Cloud setup needed - the script runs under the client's account.
 */
class SheetsManager(private val context: Context) {

    companion object {
        const val PREFS_NAME = "sheets_prefs"
        const val PREF_SCRIPT_URL = "script_url"
    }

    fun isConfigured(): Boolean {
        return getScriptUrl() != null
    }

    fun getScriptUrl(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SCRIPT_URL, null)
    }

    fun saveConfig(scriptUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SCRIPT_URL, scriptUrl.trim())
            .apply()
    }

    private suspend fun callScript(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val baseUrl = getScriptUrl() ?: throw IllegalStateException("Script URL no configurada")
        val queryString = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val fullUrl = "$baseUrl?$queryString"

        var connection: HttpURLConnection? = null
        try {
            var url = URL(fullUrl)
            // Follow redirects (Apps Script redirects on deploy)
            var redirectCount = 0
            while (redirectCount < 5) {
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == 307) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = URL(newUrl)
                    redirectCount++
                    continue
                }
                break
            }

            val response = connection!!.inputStream.bufferedReader().readText()
            JSONObject(response)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun testConnection(): String {
        val result = callScript(mapOf("action" to "ping"))
        return if (result.has("error")) {
            "Error: ${result.getString("error")}"
        } else {
            result.optString("message", "Conexión OK")
        }
    }

    suspend fun loadProducts(): List<Product> {
        val result = callScript(mapOf("action" to "getProducts"))

        if (result.has("error")) {
            throw RuntimeException(result.getString("error"))
        }

        val productsArray = result.getJSONArray("products")
        val products = mutableListOf<Product>()

        for (i in 0 until productsArray.length()) {
            val obj = productsArray.getJSONObject(i)
            products.add(Product(
                partNo = obj.getString("partNo"),
                description = obj.optString("description", ""),
                itemGroup = obj.optString("itemGroup", ""),
                inStock = obj.optInt("inStock", 0),
                responsible = obj.optString("responsible", ""),
                barcode = obj.optString("barcode", "").ifEmpty { null },
                minStock = obj.optInt("minStock", 1),
                sheetRow = obj.getInt("sheetRow")
            ))
        }

        return products
    }

    suspend fun updateBarcode(product: Product, barcode: String) {
        val result = callScript(mapOf(
            "action" to "updateBarcode",
            "row" to product.sheetRow.toString(),
            "barcode" to barcode
        ))
        if (result.has("error")) throw RuntimeException(result.getString("error"))
    }

    suspend fun updateMinStock(product: Product, minStock: Int) {
        val result = callScript(mapOf(
            "action" to "updateMinStock",
            "row" to product.sheetRow.toString(),
            "minStock" to minStock.toString()
        ))
        if (result.has("error")) throw RuntimeException(result.getString("error"))
    }

    suspend fun updateStock(product: Product, newStock: Int) {
        val result = callScript(mapOf(
            "action" to "updateStock",
            "row" to product.sheetRow.toString(),
            "stock" to newStock.toString()
        ))
        if (result.has("error")) throw RuntimeException(result.getString("error"))
    }

    suspend fun ensureHeaders() {
        val result = callScript(mapOf("action" to "ensureHeaders"))
        if (result.has("error")) throw RuntimeException(result.getString("error"))
    }

    fun clearAuth() {
        // No auth to clear with Apps Script approach
    }
}
