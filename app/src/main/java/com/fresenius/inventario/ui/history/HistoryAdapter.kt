package com.fresenius.inventario.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fresenius.inventario.R
import com.fresenius.inventario.data.local.HistoryEntry
import com.fresenius.inventario.databinding.ItemScanHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<HistoryEntry> = emptyList()

    fun submitList(list: List<HistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemScanHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val dateTimeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        fun bind(entry: HistoryEntry) {
            binding.tvHistoryPartNo.text = entry.partNo
            binding.tvHistoryDesc.text = entry.description

            val isToday = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(entry.timestamp)) ==
                SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            binding.tvHistoryTime.text = if (isToday) {
                timeFormat.format(Date(entry.timestamp))
            } else {
                dateTimeFormat.format(Date(entry.timestamp))
            }

            val sign = if (entry.type == "SALIDA") "-" else "+"
            val color = if (entry.type == "SALIDA") R.color.stock_low else R.color.stock_ok
            binding.tvHistoryQty.text = "$sign${entry.quantity}"
            binding.tvHistoryQty.setTextColor(
                ContextCompat.getColor(binding.root.context, color)
            )

            val typeLabel = when (entry.type) {
                "ENTRADA" -> "Entrada"
                "SALIDA" -> "Salida"
                "MANUAL" -> "Manual"
                else -> entry.type
            }
            binding.tvHistoryType.text = typeLabel
        }
    }
}
