package com.example.shingu

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sipariş durumunun rengi (liste ve detay ekranı ortak kullanır)
fun OrderStatus.color(): Int = Color.parseColor(
    when (this) {
        OrderStatus.PENDING -> "#F08A24"
        OrderStatus.PROCESSING -> "#3D9BD6"
        OrderStatus.SHIPPED -> "#8B69D9"
        OrderStatus.DELIVERED -> "#2FAE80"
        OrderStatus.CANCELLED -> "#9A88A3"
    }
)

// Hem admin sipariş yönetiminde hem kullanıcının Siparişlerim ekranında kullanılır.
// onChangeStatus null ise (kullanıcı görünümü) durum değiştirme butonu gizlenir.
// Karta dokununca sipariş detay ekranı açılır (onOrderClick).
class OrderAdapter(
    private var orders: List<Order>,
    private val onOrderClick: ((Order) -> Unit)? = null,
    private val onChangeStatus: ((Order) -> Unit)? = null
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    private val locale = Locale("tr", "TR")
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", locale)

    class OrderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCustomer: TextView = view.findViewById(R.id.tvOrderCustomer)
        val tvDate: TextView = view.findViewById(R.id.tvOrderDate)
        val tvItems: TextView = view.findViewById(R.id.tvOrderItems)
        val tvTotal: TextView = view.findViewById(R.id.tvOrderTotal)
        val tvStatus: TextView = view.findViewById(R.id.tvOrderStatus)
        val btnChangeStatus: Button = view.findViewById(R.id.btnOrderChangeStatus)
    }

    fun submitList(newOrders: List<Order>) {
        orders = newOrders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = orders[position]
        // Admin müşteriyi, kullanıcı ise sipariş numarasını görür
        holder.tvCustomer.text = if (onChangeStatus != null) {
            order.userEmail.ifEmpty { "Bilinmeyen kullanıcı" }
        } else {
            "Sipariş #${order.id.take(6).uppercase(locale)}"
        }
        holder.tvDate.text = dateFormat.format(Date(order.createdAt)) +
            (order.address?.let { "\n📍 $it" } ?: "") +
            (order.payment?.let { "\n💳 $it" } ?: "")
        holder.tvItems.text = order.items.joinToString("\n") { item ->
            val discount = (item["weatherDiscount"] as? Number)?.toInt() ?: 0
            "• ${item["name"]} x${item["quantity"]}" + if (discount > 0) "  (hava indirimi %$discount)" else ""
        }
        holder.tvTotal.text = String.format(locale, "Toplam: %.2f TL", order.totalPrice)

        holder.tvStatus.text = order.status.label
        holder.tvStatus.setTextColor(order.status.color())
        holder.itemView.setOnClickListener { onOrderClick?.invoke(order) }

        // İptal edilmiş siparişin durumu değiştirilemez
        if (onChangeStatus != null && order.status != OrderStatus.CANCELLED) {
            holder.btnChangeStatus.visibility = View.VISIBLE
            holder.btnChangeStatus.setOnClickListener { onChangeStatus.invoke(order) }
        } else {
            holder.btnChangeStatus.visibility = View.GONE
        }
    }

    override fun getItemCount() = orders.size
}
