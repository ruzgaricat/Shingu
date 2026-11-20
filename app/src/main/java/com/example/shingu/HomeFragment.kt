package com.example.shingu

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.shingu.api.WeatherCondition
import com.example.shingu.api.WeatherRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import kotlin.math.ceil
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

    // Ürün listesi: tüm ürünler bir kez yüklenir; arama/filtre/sıralama cihazda uygulanır
    // (Firestore metin içinde arama desteklemediği için)
    private var allProducts: List<Product> = emptyList()
    private var ratings: Map<String, RatingSummary> = emptyMap()
    private var searchQuery = ""
    private var sortOption = SortOption.NAME

    private enum class SortOption(val label: String) {
        NAME("Önerilen (A-Z)"),
        PRICE_ASC("Fiyat: Düşükten yükseğe"),
        PRICE_DESC("Fiyat: Yüksekten düşüğe"),
        RATING("En yüksek puan"),
        NEWEST("En yeni")
    }

    private lateinit var tvProductsTitle: TextView
    private lateinit var tvResultCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvPriceRange: TextView
    private lateinit var sliderPrice: RangeSlider
    private lateinit var chipInStock: Chip
    private lateinit var chipDiscounted: Chip
    private lateinit var chipRated: Chip
    private lateinit var layoutBrowseSections: View

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
        setupProductBrowser(view)

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

    // ---------- Tüm ürünler: arama, sıralama, filtre ----------
    private fun setupProductBrowser(view: View) {
        tvProductsTitle = view.findViewById(R.id.tvProductsTitle)
        tvResultCount = view.findViewById(R.id.tvResultCount)
        tvEmpty = view.findViewById(R.id.tvHomeProductsEmpty)
        tvPriceRange = view.findViewById(R.id.tvPriceRange)
        sliderPrice = view.findViewById(R.id.sliderPrice)
        chipInStock = view.findViewById(R.id.chipFilterInStock)
        chipDiscounted = view.findViewById(R.id.chipFilterDiscounted)
        chipRated = view.findViewById(R.id.chipFilterRated)
        layoutBrowseSections = view.findViewById(R.id.layoutBrowseSections)

        // Ürün listesi: adaptör bir kez oluşturulur, filtre değiştikçe sadece liste yenilenir
        val adapter = ProductSliderAdapter(
            emptyList(),
            onAddToCart = if (UserSession.isAdmin) null else { product -> addToCart(product) },
            onProductClick = { product -> openProduct(product) },
            onFavoriteToggle = { product, favorite -> FavoritesRepository.setFavorite(product.id, favorite) }
        )
        productAdapter = adapter
        view.findViewById<RecyclerView>(R.id.rvHomeProducts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        // Arama: yazdıkça sonuçlar süzülür; arama varken kampanya ve kategori bölümleri gizlenir
        view.findViewById<TextInputEditText>(R.id.etSearch).doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyFilters()
        }

        // Sıralama seçenekleri
        val actvSort = view.findViewById<AutoCompleteTextView>(R.id.actvSort)
        actvSort.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, SortOption.entries.map { it.label }))
        actvSort.setText(sortOption.label, false)
        actvSort.setOnItemClickListener { _, _, position, _ ->
            sortOption = SortOption.entries[position]
            applyFilters()
        }

        listOf(chipInStock, chipDiscounted, chipRated).forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ -> applyFilters() }
        }
        sliderPrice.addOnChangeListener { _, _, _ -> applyFilters() }

        loadProducts()

        // Puanlar: kartlarda gösterilir, "en yüksek puan" sıralaması ve 4★ filtresi için kullanılır
        ReviewRepository.loadAllRatings { result ->
            if (!isAdded) return@loadAllRatings
            ratings = result
            adapter.ratings = result
            applyFilters()
        }

        // Favoriler değişince kartlardaki kalpler güncellenir
        favoritesListener = FavoritesRepository.listenFavoriteIds { ids ->
            favoriteIds = ids
            productAdapter?.favoriteIds = ids
        }

        // Hava durumu gelince indirimli fiyatlar (ve fiyat sıralaması) yenilenir
        WeatherRepository.getCurrent { if (isAdded) applyFilters() }
    }

    private fun loadProducts() {
        db.collection("products").get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                // Satıştan kaldırılan ürünler kullanıcılara gösterilmez (admin hepsini görür)
                allProducts = result.documents
                    .map { Product.fromDocument(it) }
                    .filter { UserSession.isAdmin || it.onSale }
                setupPriceSlider()
                applyFilters()
            }
            .addOnFailureListener {
                if (isAdded) tvEmpty.text = "Ürünler yüklenemedi"
            }
    }

    // Fiyat kaydırıcısı ürünlerin en düşük ve en yüksek fiyatına göre ayarlanır
    private fun setupPriceSlider() {
        val maxPrice = allProducts.maxOfOrNull { it.price }?.let { ceil(it).toFloat() } ?: 0f
        if (maxPrice <= 0f) {
            sliderPrice.visibility = View.GONE
            tvPriceRange.visibility = View.GONE
            return
        }
        sliderPrice.valueFrom = 0f
        sliderPrice.valueTo = maxPrice
        sliderPrice.values = listOf(0f, maxPrice)
        sliderPrice.setLabelFormatter { value -> "${value.toInt()} TL" }
    }

    // Arama + filtreler + sıralama uygulanır ve liste güncellenir
    private fun applyFilters() {
        if (!isAdded) return
        val locale = Locale("tr", "TR")
        val query = searchQuery.lowercase(locale)
        val (minPrice, maxPrice) = sliderPrice.values.let { it[0].toDouble() to it[1].toDouble() }
        val priceFilterActive = sliderPrice.visibility == View.VISIBLE && (minPrice > 0.0 || maxPrice < sliderPrice.valueTo)

        val filtered = allProducts.filter { p ->
            (query.isEmpty() || p.name.lowercase(locale).contains(query) ||
                p.description.lowercase(locale).contains(query) ||
                p.categories.any { it.lowercase(locale).contains(query) }) &&
                (!chipInStock.isChecked || !p.isOutOfStock) &&
                (!chipDiscounted.isChecked || p.effectivePrice < p.price) &&
                (!chipRated.isChecked || (ratings[p.id]?.average ?: 0.0) >= 4.0) &&
                (!priceFilterActive || p.effectivePrice in minPrice..maxPrice)
        }
        val sorted = when (sortOption) {
            SortOption.NAME -> filtered.sortedBy { it.name.lowercase(locale) }
            SortOption.PRICE_ASC -> filtered.sortedBy { it.effectivePrice }
            SortOption.PRICE_DESC -> filtered.sortedByDescending { it.effectivePrice }
            SortOption.RATING -> filtered.sortedWith(
                compareByDescending<Product> { ratings[it.id]?.average ?: 0.0 }.thenByDescending { ratings[it.id]?.count ?: 0 }
            )
            SortOption.NEWEST -> filtered.sortedByDescending { it.createdAt }
        }

        productAdapter?.submitList(sorted)
        val searching = query.isNotEmpty()
        layoutBrowseSections.visibility = if (searching) View.GONE else View.VISIBLE
        tvProductsTitle.text = if (searching) "\"$searchQuery\" için sonuçlar" else "Tüm Ürünler"
        tvResultCount.text = "${sorted.size} ürün"
        if (priceFilterActive || sliderPrice.visibility == View.VISIBLE) {
            tvPriceRange.text = "Fiyat aralığı: ${minPrice.toInt()} – ${maxPrice.toInt()} TL"
        }
        tvEmpty.text = if (allProducts.isEmpty()) "Henüz ürün yok" else "Aramanıza uygun ürün bulunamadı"
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
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
