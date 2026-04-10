package com.fresenius.inventario.ui.products

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fresenius.inventario.R
import com.fresenius.inventario.databinding.ItemProductBinding
import com.fresenius.inventario.model.Product

class ProductAdapter(
    private val onItemClick: (Product) -> Unit
) : ListAdapter<Product, ProductAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProductBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemProductBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.tvPartNo.text = product.partNo
            binding.tvDescription.text = product.description
            binding.tvStock.text = "Stock: ${product.inStock}"
            binding.tvGroup.text = product.itemGroup

            if (product.barcode.isNullOrEmpty()) {
                binding.ivBarcodeStatus.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.barcode_missing)
                )
            } else {
                binding.ivBarcodeStatus.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.barcode_linked)
                )
            }

            if (product.inStock < product.minStock) {
                binding.tvStock.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.stock_low)
                )
                binding.tvLowStockWarning.visibility = View.VISIBLE
            } else {
                binding.tvStock.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.stock_ok)
                )
                binding.tvLowStockWarning.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(product) }
        }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Product>() {
            override fun areItemsTheSame(old: Product, new: Product) = old.partNo == new.partNo
            override fun areContentsTheSame(old: Product, new: Product) = old == new
        }
    }
}
