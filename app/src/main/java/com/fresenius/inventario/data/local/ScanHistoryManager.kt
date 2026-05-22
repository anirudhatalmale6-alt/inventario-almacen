package com.fresenius.inventario.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val partNo: String,
    val description: String,
    val quantity: Int,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ScanHistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
    private val entries = mutableListOf<HistoryEntry>()
    private val maxEntries = 500

    init {
        load()
    }

    fun addEntry(partNo: String, description: String, quantity: Int, type: String) {
        val entry = HistoryEntry(partNo, description, quantity, type)
        entries.add(0, entry)
        if (entries.size > maxEntries) {
            entries.subList(maxEntries, entries.size).clear()
        }
        save()
    }

    fun getAll(): List<HistoryEntry> = entries.toList()

    fun getRecent(count: Int): List<HistoryEntry> = entries.take(count)

    fun clear() {
        entries.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("partNo", e.partNo)
            obj.put("description", e.description)
            obj.put("quantity", e.quantity)
            obj.put("type", e.type)
            obj.put("timestamp", e.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun load() {
        val json = prefs.getString("entries", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(
                    HistoryEntry(
                        partNo = obj.getString("partNo"),
                        description = obj.optString("description", ""),
                        quantity = obj.getInt("quantity"),
                        type = obj.getString("type"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (_: Exception) {}
    }
}
