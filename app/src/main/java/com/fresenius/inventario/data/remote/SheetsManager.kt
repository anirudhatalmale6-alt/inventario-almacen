package com.fresenius.inventario.data.remote

import android.content.Context
import com.fresenius.inventario.model.Product
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SheetsManager(private val context: Context) {

    private var sheetsService: Sheets? = null
    private var spreadsheetId: String? = null

    // Column mapping based on client's Excel structure:
    // A=Part No., B=Description, C=Item Group, D=in Stock, E=Responsible, F=Barcode, G=Min Stock
    companion object {
        const val COL_PART_NO = 0      // A
        const val COL_DESCRIPTION = 1  // B
        const val COL_ITEM_GROUP = 2   // C
        const val COL_IN_STOCK = 3     // D
        const val COL_RESPONSIBLE = 4  // E
        const val COL_BARCODE = 5      // F (new column)
        const val COL_MIN_STOCK = 6    // G (new column)
        const val HEADER_ROW = 2       // Row 2 has headers (row 1 is title)
        const val DATA_START_ROW = 3   // Data starts at row 3
        const val PREFS_NAME = "sheets_prefs"
        const val PREF_SPREADSHEET_ID = "spreadsheet_id"
        const val PREF_SHEET_NAME = "sheet_name"
    }

    fun isConfigured(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SPREADSHEET_ID, null) != null
    }

    fun getSpreadsheetId(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SPREADSHEET_ID, null)
    }

    fun getSheetName(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SHEET_NAME, "report") ?: "report"
    }

    fun saveConfig(spreadsheetId: String, sheetName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SPREADSHEET_ID, spreadsheetId)
            .putString(PREF_SHEET_NAME, sheetName)
            .apply()
        this.spreadsheetId = spreadsheetId
    }

    private fun getService(): Sheets {
        sheetsService?.let { return it }

        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("No Google account signed in")

        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(SheetsScopes.SPREADSHEETS)
        )
        credential.selectedAccount = account.account

        val service = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Inventario Almacen")
            .build()

        sheetsService = service
        return service
    }

    suspend fun loadProducts(): List<Product> = withContext(Dispatchers.IO) {
        val service = getService()
        val id = getSpreadsheetId() ?: throw IllegalStateException("Spreadsheet not configured")
        val sheetName = getSheetName()

        val range = "$sheetName!A${DATA_START_ROW}:G"
        val response = service.spreadsheets().values()
            .get(id, range)
            .execute()

        val values = response.getValues() ?: return@withContext emptyList()

        values.mapIndexedNotNull { index, row ->
            val partNo = row.getOrNull(COL_PART_NO)?.toString()?.trim() ?: return@mapIndexedNotNull null
            if (partNo.isEmpty()) return@mapIndexedNotNull null

            Product(
                partNo = partNo,
                description = row.getOrNull(COL_DESCRIPTION)?.toString()?.trim() ?: "",
                itemGroup = row.getOrNull(COL_ITEM_GROUP)?.toString()?.trim() ?: "",
                inStock = row.getOrNull(COL_IN_STOCK)?.toString()?.trim()?.toIntOrNull() ?: 0,
                responsible = row.getOrNull(COL_RESPONSIBLE)?.toString()?.trim() ?: "",
                barcode = row.getOrNull(COL_BARCODE)?.toString()?.trim()?.ifEmpty { null },
                minStock = row.getOrNull(COL_MIN_STOCK)?.toString()?.trim()?.toIntOrNull() ?: 1,
                sheetRow = index + DATA_START_ROW // actual row in sheet
            )
        }
    }

    suspend fun updateBarcode(product: Product, barcode: String) = withContext(Dispatchers.IO) {
        val service = getService()
        val id = getSpreadsheetId() ?: return@withContext
        val sheetName = getSheetName()

        val range = "$sheetName!F${product.sheetRow}"
        val body = ValueRange().setValues(listOf(listOf(barcode)))
        service.spreadsheets().values()
            .update(id, range, body)
            .setValueInputOption("RAW")
            .execute()
    }

    suspend fun updateMinStock(product: Product, minStock: Int) = withContext(Dispatchers.IO) {
        val service = getService()
        val id = getSpreadsheetId() ?: return@withContext
        val sheetName = getSheetName()

        val range = "$sheetName!G${product.sheetRow}"
        val body = ValueRange().setValues(listOf(listOf(minStock.toString())))
        service.spreadsheets().values()
            .update(id, range, body)
            .setValueInputOption("RAW")
            .execute()
    }

    suspend fun updateStock(product: Product, newStock: Int) = withContext(Dispatchers.IO) {
        val service = getService()
        val id = getSpreadsheetId() ?: return@withContext
        val sheetName = getSheetName()

        val range = "$sheetName!D${product.sheetRow}"
        val body = ValueRange().setValues(listOf(listOf(newStock.toString())))
        service.spreadsheets().values()
            .update(id, range, body)
            .setValueInputOption("RAW")
            .execute()
    }

    suspend fun ensureHeaders() = withContext(Dispatchers.IO) {
        val service = getService()
        val id = getSpreadsheetId() ?: return@withContext
        val sheetName = getSheetName()

        // Check if Barcode and Min Stock headers exist
        val range = "$sheetName!F${HEADER_ROW}:G${HEADER_ROW}"
        val response = service.spreadsheets().values()
            .get(id, range)
            .execute()

        val values = response.getValues()
        val needsBarcode = values == null || values.isEmpty() ||
                values[0].getOrNull(0)?.toString()?.trim() != "Barcode"

        if (needsBarcode) {
            val body = ValueRange().setValues(listOf(listOf("Barcode", "Min Stock")))
            service.spreadsheets().values()
                .update(id, range, body)
                .setValueInputOption("RAW")
                .execute()
        }
    }

    fun clearAuth() {
        sheetsService = null
    }
}
