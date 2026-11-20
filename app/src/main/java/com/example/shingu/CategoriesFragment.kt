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
import com.example.shingu.model.CategoryModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class CategoriesFragment : Fragment() {

    private lateinit var db: FirebaseFirestore
    private lateinit var recyclerView: RecyclerView
    private var favoritesListener: ListenerRegistration? = null
    private var productAdapter: ProductSliderAdapter? = null
    private var favoriteIds: Set<String> = emptySet()

    // Seçili kategori; ürün sayfasından geri dönülünce aynı ürün listesi yeniden açılır
    private var selectedCategory: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_categories, container, false)
        db = FirebaseFirestore.getInstance()

        recyclerView = view.findViewById(R.id.recyclerCategoryView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val category = selectedCategory
        if (category != null) loadProductsForCategory(category) else loadCategories()

        // Favoriler değişince kartlardaki kalpler güncellenir
        favoritesListener = FavoritesRepository.listenFavoriteIds { ids ->
            favoriteIds = ids
            productAdapter?.favoriteIds = ids
        }

        // Hava durumu gelince ürün kartlarındaki indirimli fiyatlar yenilenir
        WeatherRepository.getCurrent {
            if (isAdded) recyclerView.adapter?.notifyDataSetChanged()
        }

        return view
    }

    private fun loadCategories() {
        // Artık ürünlerden değil, doğrudan 'categories' koleksiyonundan çekiyoruz
        db.collection("categories").get().addOnSuccessListener { result ->
            val categoryList = mutableListOf<CategoryModel>() // Kategori veri modeli (aşağıda tanımı var)

            for (doc in result.documents) {
                val name = doc.getString("name") ?: ""
                val imageUrl = doc.getString("imageUrl") ?: ""

                if (name.isNotEmpty()) {
                    categoryList.add(CategoryModel(name, imageUrl))
                }
            }

            // Kategori adapter'ına hem ismi hem görseli gönderiyoruz
            val adapter = CategoryAdapter(categoryList) { categoryName ->
                loadProductsForCategory(categoryName)
            }
            recyclerView.adapter = adapter
        }
    }

    private fun loadProductsForCategory(categoryName: String) {
        selectedCategory = categoryName
        // Ürünlerde kategoriler liste (Array/List) olarak tutulduğu için 'arrayContains' kullanıyoruz
        db.collection("products")
            .whereArrayContains("categories", categoryName)
            .get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                // Satıştan kaldırılan ürünler kullanıcılara gösterilmez (admin hepsini görür)
                val products = result.documents
                    .map { Product.fromDocument(it) }
                    .filter { UserSession.isAdmin || it.onSale }

                // Admin'in sepeti olmadığı için "Sepete Ekle" butonu sadece kullanıcılara gösterilir
                val onAddToCart: ((Product) -> Unit)? = if (UserSession.isAdmin) null else { product ->
                    CartRepository.addToCart(product,
                        onSuccess = { if (isAdded) Toast.makeText(requireContext(), "${product.name} sepete eklendi", Toast.LENGTH_SHORT).show() },
                        onFailure = { e -> if (isAdded) Toast.makeText(requireContext(), "Hata: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
                    )
                }
                val adapter = ProductSliderAdapter(
                    products,
                    onAddToCart = onAddToCart,
                    onProductClick = { product -> openProduct(product) },
                    onFavoriteToggle = { product, favorite -> FavoritesRepository.setFavorite(product.id, favorite) }
                )
                adapter.favoriteIds = favoriteIds
                productAdapter = adapter
                recyclerView.adapter = adapter
            }
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
        productAdapter = null
    }
}
