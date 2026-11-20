package com.example.shingu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.shingu.api.WeatherRepository
import com.google.firebase.firestore.ListenerRegistration

// Kullanıcının favori ürünleri; kalbe dokununca favoriden çıkar, karta dokununca ürün sayfası açılır
class FavoritesFragment : Fragment() {

    private var favoritesListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorites, container, false)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewFavorites)
        val layoutEmpty = view.findViewById<View>(R.id.layoutEmptyFavorites)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Favori listesi değiştikçe (kalp kaldırılınca vb.) ürünler yeniden yüklenir
        favoritesListener = FavoritesRepository.listenFavoriteIds { ids ->
            Product.loadByIds(ids) { products ->
                if (!isAdded) return@loadByIds
                // Satıştan kaldırılan ürünler kullanıcıya gösterilmez
                val visible = products.filter { UserSession.isAdmin || it.onSale }
                val adapter = ProductSliderAdapter(
                    visible,
                    onAddToCart = if (UserSession.isAdmin) null else { product -> addToCart(product) },
                    onProductClick = { product -> openProduct(product) },
                    onFavoriteToggle = { product, favorite -> FavoritesRepository.setFavorite(product.id, favorite) }
                )
                adapter.favoriteIds = ids
                recyclerView.adapter = adapter

                recyclerView.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
                layoutEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        if (favoritesListener == null) layoutEmpty.visibility = View.VISIBLE

        // Hava durumu gelince indirimli fiyatlar yenilenir
        WeatherRepository.getCurrent { if (isAdded) recyclerView.adapter?.notifyDataSetChanged() }

        return view
    }

    private fun addToCart(product: Product) {
        CartRepository.addToCart(product,
            onSuccess = { if (isAdded) Toast.makeText(requireContext(), "${product.name} sepete eklendi", Toast.LENGTH_SHORT).show() },
            onFailure = { e -> if (isAdded) Toast.makeText(requireContext(), "Hata: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun openProduct(product: Product) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ProductDetailFragment.newInstance(product.id))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        favoritesListener?.remove()
        favoritesListener = null
    }
}
