package com.example.shingu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.shingu.api.WeatherCondition
import com.example.shingu.api.WeatherRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlin.math.roundToInt

// Ana sayfa: hava durumu şeridi, kampanya slaytı, kategori kısayolları ve tüm ürünler
class HomeFragment : Fragment() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var tvWeatherStrip: TextView
    private lateinit var vpCampaigns: ViewPager2
    private lateinit var layoutIndicators: LinearLayout
    private lateinit var campaignAdapter: CampaignBannerAdapter

    private var campaignsListener: ListenerRegistration? = null
    private var favoritesListener: ListenerRegistration? = null
    private var productAdapter: ProductSliderAdapter? = null
    private var favoriteIds: Set<String> = emptySet()

    // Slayt 4 saniyede bir kendiliğinden sonraki kampanyaya geçer
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val count = campaignAdapter.itemCount
            if (count > 1) vpCampaigns.currentItem = (vpCampaigns.currentItem + 1) % count
            autoScrollHandler.postDelayed(this, AUTO_SCROLL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        tvWeatherStrip = view.findViewById(R.id.tvWeatherStrip)
        loadWeather()

        setupCampaigns(view)
        loadCategoryShortcuts(view.findViewById(R.id.chipGroupHomeCategories))
        loadAllProducts(view.findViewById(R.id.rvHomeProducts), view.findViewById(R.id.tvHomeProductsEmpty))

        return view
    }

    // ---------- Kampanya slaytı ----------
    private fun setupCampaigns(view: View) {
        val layoutCampaigns = view.findViewById<View>(R.id.layoutCampaigns)
        vpCampaigns = view.findViewById(R.id.vpCampaigns)
        layoutIndicators = view.findViewById(R.id.layoutIndicators)

        // Banner'a dokununca kampanyayla aynı adlı kategori açılır
        campaignAdapter = CampaignBannerAdapter { campaign -> openCategory(campaign.categoryName) }
        vpCampaigns.adapter = campaignAdapter
        vpCampaigns.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
                // Kullanıcı elle kaydırdıysa otomatik geçiş süresi baştan başlar
                restartAutoScroll()
            }
        })

        campaignsListener = CampaignRepository.listenCampaigns(
            onChange = { campaigns ->
                if (!isAdded) return@listenCampaigns
                campaignAdapter.submitList(campaigns)
                layoutCampaigns.visibility = if (campaigns.isEmpty()) View.GONE else View.VISIBLE
                createIndicators(campaigns.size)
                updateIndicators(vpCampaigns.currentItem.coerceAtMost((campaigns.size - 1).coerceAtLeast(0)))
            },
            onFailure = { /* Kampanya yüklenemezse slayt gizli kalır */ }
        )
    }

    private fun createIndicators(count: Int) {
        layoutIndicators.removeAllViews()
        if (count <= 1) return
        repeat(count) {
            layoutIndicators.addView(View(requireContext()))
        }
    }

    private fun updateIndicators(selected: Int) {
        val density = resources.displayMetrics.density
        for (i in 0 until layoutIndicators.childCount) {
            val isActive = i == selected
            layoutIndicators.getChildAt(i).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ((if (isActive) 28 else 14) * density).toInt(),
                    (4 * density).toInt()
                ).apply { marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt() }
                setBackgroundResource(if (isActive) R.drawable.bg_indicator_active else R.drawable.bg_indicator_inactive)
            }
        }
    }

    private fun restartAutoScroll() {
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        autoScrollHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_MS)
    }

    // ---------- Kategori kısayolları ----------
    private fun loadCategoryShortcuts(chipGroup: ChipGroup) {
        db.collection("categories").orderBy("name").get().addOnSuccessListener { result ->
            if (!isAdded) return@addOnSuccessListener
            chipGroup.removeAllViews()
            for (doc in result.documents) {
                val name = doc.getString("name") ?: continue
                chipGroup.addView(Chip(requireContext()).apply {
                    text = name
                    setOnClickListener { openCategory(name) }
                })
            }
        }
    }

    // ---------- Tüm ürünler ----------
    private fun loadAllProducts(recyclerView: RecyclerView, tvEmpty: TextView) {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        db.collection("products").orderBy("name", Query.Direction.ASCENDING).get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                // Satıştan kaldırılan ürünler kullanıcılara gösterilmez (admin hepsini görür)
                val products = result.documents
                    .map { Product.fromDocument(it) }
                    .filter { UserSession.isAdmin || it.onSale }

                val adapter = ProductSliderAdapter(
                    products,
                    onAddToCart = if (UserSession.isAdmin) null else { product -> addToCart(product) },
                    onProductClick = { product -> openProduct(product) },
                    onFavoriteToggle = { product, favorite -> FavoritesRepository.setFavorite(product.id, favorite) }
                )
                adapter.favoriteIds = favoriteIds
                productAdapter = adapter
                recyclerView.adapter = adapter

                tvEmpty.text = "Henüz ürün yok"
                tvEmpty.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                if (isAdded) tvEmpty.text = "Ürünler yüklenemedi"
            }

        // Favoriler değişince kartlardaki kalpler güncellenir
        favoritesListener = FavoritesRepository.listenFavoriteIds { ids ->
            favoriteIds = ids
            productAdapter?.favoriteIds = ids
        }

        // Hava durumu gelince indirimli fiyatlar yenilenir
        WeatherRepository.getCurrent { if (isAdded) recyclerView.adapter?.notifyDataSetChanged() }
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

    private fun openCategory(categoryName: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, CategoriesFragment.newInstance(categoryName))
            .addToBackStack(null)
            .commit()
    }

    // ---------- Hava durumu şeridi ----------
    // Web API 2: Anlık hava durumu Open-Meteo API'den (WeatherRepository üzerinden) gelir
    private fun loadWeather() {
        WeatherRepository.getCurrent { current ->
            if (!isAdded) return@getCurrent
            if (current == null) {
                tvWeatherStrip.text = "Hava durumu alınamadı"
                return@getCurrent
            }
            // Örn: "☔ Kocaeli · 10°C · Yağmurlu"
            val condition = WeatherCondition.fromWmoCode(current.weather_code)
            tvWeatherStrip.text = "${condition.emoji}  Kocaeli · ${current.temperature_2m.roundToInt()}°C · ${condition.label}"
            showDiscountIfAny(condition)
        }
    }

    // Bu havaya etiketlenmiş ürün varsa şeridin sonuna kısa bir indirim notu eklenir
    private fun showDiscountIfAny(condition: WeatherCondition) {
        db.collection("products")
            .whereEqualTo("weatherTag", condition.code)
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                if (!isAdded || result.isEmpty) return@addOnSuccessListener
                tvWeatherStrip.append(" · Hava indirimi aktif")
            }
    }

    override fun onResume() {
        super.onResume()
        restartAutoScroll()
    }

    override fun onPause() {
        super.onPause()
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        campaignsListener?.remove()
        favoritesListener?.remove()
        productAdapter = null
    }

    companion object {
        private const val AUTO_SCROLL_MS = 4000L
    }
}
