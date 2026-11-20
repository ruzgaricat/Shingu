package com.example.shingu

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.ListenerRegistration

// Admin için sepet sekmesinin yerine açılan sipariş yönetim ekranı
class AdminOrdersFragment : Fragment() {

    private var ordersListener: ListenerRegistration? = null
    private lateinit var adapter: OrderAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var chipActive: Chip
    private lateinit var chipDelivered: Chip
    private var allOrders: List<Order> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_orders, container, false)

        tvEmpty = view.findViewById(R.id.tvOrdersEmpty)
        adapter = OrderAdapter(emptyList()) { order -> showStatusDialog(order) }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerOrders)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Teslim edilen siparişler silinmez, ayrı sekmede tutulur; varsayılan olarak sadece aktifler görünür
        chipActive = view.findViewById(R.id.chipActiveOrders)
        chipDelivered = view.findViewById(R.id.chipDeliveredOrders)
        view.findViewById<ChipGroup>(R.id.chipGroupOrderFilter).apply {
            visibility = View.VISIBLE
            setOnCheckedStateChangeListener { _, _ -> showFilteredOrders() }
        }

        ordersListener = OrderRepository.listenAllOrders(
            onChange = { orders ->
                allOrders = orders
                showFilteredOrders()
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Siparişler yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    private fun showFilteredOrders() {
        val (delivered, active) = allOrders.partition { it.status == OrderStatus.DELIVERED }
        chipActive.text = "Aktif (${active.size})"
        chipDelivered.text = "Teslim Edilen (${delivered.size})"

        val showDelivered = chipDelivered.isChecked
        val orders = if (showDelivered) delivered else active
        adapter.submitList(orders)
        tvEmpty.text = if (showDelivered) "Teslim edilen sipariş yok" else "Aktif sipariş yok"
        tvEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStatusDialog(order: Order) {
        val statuses = OrderStatus.entries
        AlertDialog.Builder(requireContext())
            .setTitle("Sipariş Durumu")
            .setSingleChoiceItems(statuses.map { it.label }.toTypedArray(), statuses.indexOf(order.status)) { dialog, which ->
                dialog.dismiss()
                val newStatus = statuses[which]
                if (newStatus == order.status) return@setSingleChoiceItems
                OrderRepository.updateStatus(order.id, newStatus,
                    onSuccess = {
                        if (isAdded) Toast.makeText(requireContext(), "Durum: ${newStatus.label}", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        if (isAdded) Toast.makeText(requireContext(), "Güncellenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ordersListener?.remove()
        ordersListener = null
    }
}
