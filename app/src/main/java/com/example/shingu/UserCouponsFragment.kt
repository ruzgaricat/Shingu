package com.example.shingu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration

// Profil > Kuponlarım: kullanılabilir (aktif, süresi dolmamış) kuponlar. Dokununca kod panoya kopyalanır.
class UserCouponsFragment : Fragment() {

    private var couponsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        view.findViewById<TextView>(R.id.tvAdminListTitle).text = "Kuponlarım"
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Şu an kullanılabilir kupon yok (´・ω・`)"
        view.findViewById<Button>(R.id.btnNewProduct).visibility = View.GONE

        val adapter = CouponAdapter(showAdminInfo = false) { coupon -> copyCode(coupon) }
        view.findViewById<RecyclerView>(R.id.recyclerAdminProducts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        couponsListener = CouponRepository.listenCoupons(onlyActive = true,
            onChange = { coupons ->
                if (!isAdded) return@listenCoupons
                adapter.submitList(coupons)
                tvEmpty.visibility = if (coupons.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Kuponlar yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
        return view
    }

    private fun copyCode(coupon: Coupon) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Kupon kodu", coupon.code))
        Toast.makeText(requireContext(), "${coupon.code} kopyalandı. Sepette \"Kupon kodu\" alanına yapıştırabilirsiniz.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        couponsListener?.remove()
        couponsListener = null
    }
}
