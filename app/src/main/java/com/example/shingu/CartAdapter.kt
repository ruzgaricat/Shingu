package com.example.shingu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.Locale

class CartAdapter(
    private var items: List<CartItem>,
    private val onIncrease: (CartItem) -> Unit,
    private val onDecrease: (CartItem) -> Unit,
    private val onRemove: (CartItem) -> Unit
) : RecyclerView.Adapter<CartAdapter.CartViewHolder>() {

    class CartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivCartItemImage)
        val tvName: TextView = view.findViewById(R.id.tvCartItemName)
        val tvPrice: TextView = view.findViewById(R.id.tvCartItemPrice)
        val tvQuantity: TextView = view.findViewById(R.id.tvCartItemQuantity)
        val btnIncrease: ImageButton = view.findViewById(R.id.btnCartIncrease)
        val btnDecrease: ImageButton = view.findViewById(R.id.btnCartDecrease)
        val btnRemove: ImageButton = view.findViewById(R.id.btnCartRemove)
    }

    fun submitList(newItems: List<CartItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CartViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cart, parent, false)
        return CartViewHolder(view)
    }

    override fun onBindViewHolder(holder: CartViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        // Fiyat ürünün güncel halinden gelir; satıştan kalkan veya stoğu aşan ürünler uyarıyla gösterilir
        holder.tvPrice.text = when {
            !item.available -> "Satışta değil, lütfen sepetten çıkarın"
            item.exceedsStock -> "Stokta sadece ${item.stock} adet var"
            else -> String.format(Locale("tr", "TR"), "%.2f TL", item.lineTotal) +
                if (item.hasWeatherDiscount) "  (hava indirimi %${item.weatherDiscount})" else ""
        }
        holder.btnIncrease.isEnabled = item.available
        holder.tvQuantity.text = item.quantity.toString()

        Glide.with(holder.itemView.context)
            .load(item.imageUrl)
            .placeholder(R.drawable.ic_placeholder_sakura)
            .into(holder.ivImage)

        holder.btnIncrease.setOnClickListener { onIncrease(item) }
        holder.btnDecrease.setOnClickListener { onDecrease(item) }
        holder.btnRemove.setOnClickListener { onRemove(item) }
    }

    override fun getItemCount() = items.size
}
