package com.example.shingu

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks
import com.example.shingu.api.WeatherCondition
import com.example.shingu.api.WeatherDiscount
import android.graphics.Paint
import java.util.Locale


data class Product(
    val id: String = "",
    val name: String,
    val price: Double,
    val imageUrls: List<String>,
    val weatherTag: String? = null,   // Örn: "rainy" → yağmurlu havada indirimli
    val weatherDiscount: Int = 0,     // Yüzde indirim oranı
    val description: String = "",
    val stock: Long? = null,          // null = stok girilmemiş (eski ürünler), sınırsız sayılır
    val onSale: Boolean = true,       // false ise kullanıcılara gösterilmez
    val categories: List<String> = emptyList(),
    val createdAt: Long = 0L
) {
    // Hava durumu indirimi dahil satış fiyatı (sıralama ve fiyat filtresi için)
    val effectivePrice: Double get() = WeatherDiscount.effectivePrice(price, weatherTag, weatherDiscount)

    val isOutOfStock: Boolean get() = stock != null && stock <= 0

    companion object {
        // Firestore 'products' belgesini Product nesnesine çevirir
        fun fromDocument(doc: DocumentSnapshot) = Product(
            id = doc.id,
            name = doc.getString("name") ?: "",
            price = doc.getDouble("price") ?: 0.0,
            imageUrls = readImageUrls(doc),
            weatherTag = doc.getString("weatherTag"),
            weatherDiscount = doc.getLong("weatherDiscount")?.toInt() ?: 0,
            description = doc.getString("description") ?: "",
            stock = doc.getLong("stock"),
            onSale = doc.getBoolean("onSale") ?: true,
            categories = (doc.get("categories") as? List<*>).orEmpty().filterIsInstance<String>(),
            createdAt = doc.getLong("createdAt") ?: 0L
        )

        // Çoklu görsel: "imageUrls" listesi; eski ürünlerde sadece tek "imageUrl" alanı vardır
        private fun readImageUrls(doc: DocumentSnapshot): List<String> {
            @Suppress("UNCHECKED_CAST")
            val list = (doc.get("imageUrls") as? List<String>).orEmpty().filter { it.isNotBlank() }
            return list.ifEmpty { listOfNotNull(doc.getString("imageUrl")?.takeIf { it.isNotBlank() }) }
        }

        // Verilen kimliklerdeki ürünleri getirir; silinmiş ürünler atlanır
        fun loadByIds(ids: Collection<String>, onResult: (List<Product>) -> Unit) {
            if (ids.isEmpty()) return onResult(emptyList())
            val db = FirebaseFirestore.getInstance()
            val tasks = ids.map { db.collection("products").document(it).get() }
            Tasks.whenAllComplete(tasks).addOnSuccessListener {
                val products = tasks
                    .filter { it.isSuccessful && it.result.exists() }
                    .map { fromDocument(it.result) }
                onResult(products)
            }
        }
    }
}

// Ürün fiyatını yazar: hava durumu etiketi şu anki havaya uyuyorsa eski fiyat üstü çizili,
// yeni fiyat kırmızı ve indirim rozeti görünür. Ürün kartı ve ürün detay ekranı ortak kullanır.
fun bindProductPrice(product: Product, tvPrice: TextView, tvOldPrice: TextView, tvWeatherBadge: TextView) {
    val locale = Locale("tr", "TR")
    val condition = WeatherCondition.fromCode(product.weatherTag)

    if (WeatherDiscount.isActive(product.weatherTag, product.weatherDiscount) && condition != null) {
        val discounted = WeatherDiscount.effectivePrice(product.price, product.weatherTag, product.weatherDiscount)
        tvPrice.text = String.format(locale, "%.2f TL", discounted)
        tvOldPrice.text = String.format(locale, "%.2f TL", product.price)
        tvOldPrice.paintFlags = tvOldPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        tvOldPrice.visibility = View.VISIBLE
        tvWeatherBadge.text = "${condition.emoji} ${condition.label} hava indirimi %${product.weatherDiscount}"
        tvWeatherBadge.visibility = View.VISIBLE
    } else {
        tvPrice.text = String.format(locale, "%.2f TL", product.price)
        tvOldPrice.visibility = View.GONE
        tvWeatherBadge.visibility = View.GONE
    }
}

class ProductSliderAdapter(
    private var productList: List<Product>,
    // Sepete ekle butonuna basıldığında çalışır; null ise buton gizlenir
    private val onAddToCart: ((Product) -> Unit)? = null,
    // Karta dokunulunca (ürün sayfasını açmak için)
    private val onProductClick: ((Product) -> Unit)? = null,
    // Kalbe dokunulunca; ikinci parametre yeni durum (true = favoriye eklendi). null ise kalp gizlenir
    private val onFavoriteToggle: ((Product, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<ProductSliderAdapter.ProductViewHolder>() {

    // Kullanıcının favori ürün kimlikleri; değişince kalpler yeniden çizilir
    var favoriteIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // Ürün puanları (ürün kimliği -> ortalama ve değerlendirme sayısı)
    var ratings: Map<String, RatingSummary> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivProduct: ImageView = view.findViewById(R.id.ivItemProductImage)
        val tvName: TextView = view.findViewById(R.id.tvItemProductName)
        val tvPrice: TextView = view.findViewById(R.id.tvItemProductPrice)
        val tvOldPrice: TextView = view.findViewById(R.id.tvItemProductOldPrice)
        val tvWeatherBadge: TextView = view.findViewById(R.id.tvItemWeatherBadge)
        val btnAddToCart: Button = view.findViewById(R.id.btnItemAddToCart)
        val btnFavorite: ImageButton = view.findViewById(R.id.btnItemFavorite)
        val tvRating: TextView = view.findViewById(R.id.tvItemRating)

        var handler: Handler? = null
        var runnable: Runnable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_slider, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = productList[position]
        holder.tvName.text = product.name
        bindProductPrice(product, holder.tvPrice, holder.tvOldPrice, holder.tvWeatherBadge)

        holder.itemView.setOnClickListener { onProductClick?.invoke(product) }

        val rating = ratings[product.id]
        holder.tvRating.visibility = if (rating != null) View.VISIBLE else View.GONE
        rating?.let { holder.tvRating.text = String.format(Locale("tr", "TR"), "★ %.1f (%d)", it.average, it.count) }

        if (onFavoriteToggle != null) {
            val isFavorite = product.id in favoriteIds
            holder.btnFavorite.visibility = View.VISIBLE
            holder.btnFavorite.setImageResource(
                if (isFavorite) R.drawable.baseline_favorite_24 else R.drawable.ic_favorite_border_24
            )
            holder.btnFavorite.setColorFilter(Color.parseColor(if (isFavorite) "#FF4F8B" else "#4A3550"))
            holder.btnFavorite.setOnClickListener { onFavoriteToggle.invoke(product, !isFavorite) }
        } else {
            holder.btnFavorite.visibility = View.GONE
        }

        if (onAddToCart != null) {
            holder.btnAddToCart.visibility = View.VISIBLE
            // Stok bittiyse buton pasif ve "Tükendi" yazar
            holder.btnAddToCart.isEnabled = !product.isOutOfStock
            holder.btnAddToCart.text = if (product.isOutOfStock) "Tükendi" else "Sepete Ekle"
            holder.btnAddToCart.setOnClickListener { onAddToCart.invoke(product) }
        } else {
            holder.btnAddToCart.visibility = View.GONE
        }

        if (product.imageUrls.isEmpty()) {
            // Görseli kaldırılmış ürün: geri dönüştürülen karttaki eski görsel kalmasın
            Glide.with(holder.itemView.context).load(R.drawable.ic_placeholder_sakura).into(holder.ivProduct)
        } else {
            var currentIndex = 0
            // İlk görseli yükle
            Glide.with(holder.itemView.context).load(product.imageUrls[currentIndex]).into(holder.ivProduct)

            // Eğer birden fazla görsel varsa 3 saniyede bir değiştir
            if (product.imageUrls.size > 1) {
                holder.handler = Handler(Looper.getMainLooper())
                holder.runnable = object : Runnable {
                    override fun run() {
                        currentIndex = (currentIndex + 1) % product.imageUrls.size
                        Glide.with(holder.itemView.context).load(product.imageUrls[currentIndex]).into(holder.ivProduct)
                        holder.handler?.postDelayed(this, 3000)
                    }
                }
                holder.handler?.postDelayed(holder.runnable!!, 3000)
            }
        }
    }

    override fun onViewRecycled(holder: ProductViewHolder) {
        super.onViewRecycled(holder)
        holder.runnable?.let { holder.handler?.removeCallbacks(it) }
    }

    // Arama/filtre sonucu değişince liste yenilenir
    fun submitList(products: List<Product>) {
        productList = products
        notifyDataSetChanged()
    }

    override fun getItemCount() = productList.size
}