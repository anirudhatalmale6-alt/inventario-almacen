package com.fresenius.inventario.ui.history

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.fresenius.inventario.data.local.ScanHistoryManager
import com.fresenius.inventario.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: ScanHistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = ScanHistoryManager(this)
        adapter = HistoryAdapter()

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerHistory.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { confirmClear() }

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        val entries = historyManager.getAll()
        adapter.submitList(entries)

        if (entries.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
            binding.tvHistoryCount.text = "Sin registros"
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            binding.tvHistoryCount.text = "Total: ${entries.size} registros"
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Borrar historial")
            .setMessage("Se borraran todos los registros del historial. Esta accion no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                historyManager.clear()
                loadHistory()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
