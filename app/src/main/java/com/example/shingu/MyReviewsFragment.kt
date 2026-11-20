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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

// Profil > Ürün Yorumlarım: kullanıcının yazdığı tüm yorumlar; dokununca ürün sayfası açılır, çöp kutusuyla silinir
class MyReviewsFragment : Fragment() {

    private var reviewsListener: ListenerRegistration? = null
    private var productNames: Map<String, String> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Sipariş listesiyle aynı sade liste tasarımı kullanılır
        val view = inflater.inflate(R.layout.fragment_orders, container, false)
        view.findViewById<TextView>(R.id.tvOrdersTitle).text = "Ürün Yorumlarım"
        val tvEmpty = view.findViewById<TextView>(R.id.tvOrdersEmpty)
        tvEmpty.text = "Henüz yorum yazmadınız"

        val adapter = ReviewAdapter(
            currentUid = FirebaseAuth.getInstance().currentUser?.uid,
            canDeleteAll = false,
            onDelete = { review -> confirmDelete(review) },
            titleOf = { review -> productNames[review.productId] ?: "Silinmiş ürün" },
            onClick = { review -> if (review.productId in productNames) openProduct(review.productId) }
        )
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerOrders)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            tvEmpty.visibility = View.VISIBLE
            return view
        }

        reviewsListener = ReviewRepository.listenUserReviews(uid,
            onChange = { reviews ->
                // Ürün adları ürünlerin güncel halinden okunur
                Product.loadByIds(reviews.map { it.productId }) { products ->
                    if (!isAdded) return@loadByIds
                    productNames = products.associate { it.id to it.name }
                    adapter.submitList(reviews)
                    tvEmpty.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE
                }
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Yorumlar yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    private fun confirmDelete(review: Review) {
        AlertDialog.Builder(requireContext())
            .setTitle("Yorumu Sil")
            .setMessage("Bu yorum kalıcı olarak silinecek. Emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                ReviewRepository.deleteReview(review.productId, review.userId,
                    onSuccess = { if (isAdded) Toast.makeText(requireContext(), "Yorum silindi", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> if (isAdded) Toast.makeText(requireContext(), "Silinemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun openProduct(productId: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ProductDetailFragment.newInstance(productId))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reviewsListener?.remove()
        reviewsListener = null
    }
}
