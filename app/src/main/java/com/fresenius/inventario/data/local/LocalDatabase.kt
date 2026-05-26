package com.fresenius.inventario.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fresenius.inventario.model.Product

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "inventario.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE products (
                partNo TEXT PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                itemGroup TEXT NOT NULL DEFAULT '',
                inStock INTEGER NOT NULL DEFAULT 0,
                minStock INTEGER NOT NULL DEFAULT 1,
                barcode TEXT,
                sheetRow INTEGER NOT NULL DEFAULT -1
            )
        """)
        db.execSQL("""
            CREATE TABLE pending_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                partNo TEXT NOT NULL,
                delta INTEGER NOT NULL,
                type TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun hasProducts(): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM products", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count > 0
    }

    fun saveProducts(products: List<Product>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("products", null, null)
            for (p in products) {
                val cv = ContentValues().apply {
                    put("partNo", p.partNo)
                    put("description", p.description)
                    put("itemGroup", p.itemGroup)
                    put("inStock", p.inStock)
                    put("minStock", p.minStock)
                    put("barcode", p.barcode)
                    put("sheetRow", p.sheetRow)
                }
                db.insert("products", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadProducts(): List<Product> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM products ORDER BY partNo", null)
        val products = mutableListOf<Product>()
        while (cursor.moveToNext()) {
            products.add(Product(
                partNo = cursor.getString(cursor.getColumnIndexOrThrow("partNo")),
                description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                itemGroup = cursor.getString(cursor.getColumnIndexOrThrow("itemGroup")),
                inStock = cursor.getInt(cursor.getColumnIndexOrThrow("inStock")),
                minStock = cursor.getInt(cursor.getColumnIndexOrThrow("minStock")),
                barcode = cursor.getString(cursor.getColumnIndexOrThrow("barcode")),
                sheetRow = cursor.getInt(cursor.getColumnIndexOrThrow("sheetRow"))
            ))
        }
        cursor.close()
        return products
    }

    fun updateStock(partNo: String, newStock: Int) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("inStock", newStock) }
        db.update("products", cv, "partNo = ?", arrayOf(partNo))
    }

    fun updateBarcode(partNo: String, barcode: String) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("barcode", barcode) }
        db.update("products", cv, "partNo = ?", arrayOf(partNo))
    }

    fun addPendingChange(partNo: String, delta: Int, type: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("partNo", partNo)
            put("delta", delta)
            put("type", type)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("pending_changes", null, cv)
    }

    fun getPendingCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM pending_changes", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    fun getPendingChangesGrouped(): Map<String, Int> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT partNo, SUM(delta) as totalDelta FROM pending_changes GROUP BY partNo",
            null
        )
        val changes = mutableMapOf<String, Int>()
        while (cursor.moveToNext()) {
            val partNo = cursor.getString(0)
            val totalDelta = cursor.getInt(1)
            changes[partNo] = totalDelta
        }
        cursor.close()
        return changes
    }

    fun clearPendingChanges() {
        val db = writableDatabase
        db.delete("pending_changes", null, null)
    }
}
