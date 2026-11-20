package com.example.shingu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

// Kullanıcının kendi siparişlerini ve güncel durumlarını gösteren ekran
class MyOrdersFragment : Fragment() {

    private var ordersListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_orders, container, false)

        view.findViewById<TextView>(R.id.tvOrdersTitle).text = "Siparişlerim"
        val tvEmpty = view.findViewById<TextView>(R.id.tvOrdersEmpty)
        tvEmpty.text = "Henüz siparişiniz yok"

        // Durum değiştirme callback'i verilmediği için buton gizli kalır; karta dokununca sipariş detayı açılır
        val adapter = OrderAdapter(emptyList(), onOrderClick = { order ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, OrderDetailFragment.newInstance(order.id))
                .addToBackStack(null)
                .commit()
        })
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerOrders)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            tvEmpty.visibility = View.VISIBLE
            return view
        }

        // Admin durumu değiştirdiğinde liste anında güncellenir
        ordersListener = OrderRepository.listenUserOrders(uid,
            onChange = { orders ->
                adapter.submitList(orders)
                tvEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Siparişler yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ordersListener?.remove()
        ordersListener = null
    }
}
