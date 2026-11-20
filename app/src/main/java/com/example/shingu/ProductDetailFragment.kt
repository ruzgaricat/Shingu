package com.example.shingu

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.shingu.api.WeatherRepository
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ürün sayfası: görsel, fiyat (hava indirimi dahil), açıklama, stok, favori, sepete ekleme ve yorumlar
class ProductDetailFragment : Fragment() {

    private val productId: String get() = requireArguments().getString(ARG_PRODUCT_ID)!!
    private val currentUid get() = FirebaseAuth.getInstance().currentUser?.uid

    private var productListener: ListenerRegistration? = null
    private var reviewsListener: ListenerRegistration? = null
    private var favoritesListener: ListenerRegistration? = null

    private var product: Product? = null
    private var isFavorite = false

    private lateinit var ivImage: ImageView
    private lateinit var btnFavorite: ImageButton
    private lateinit var tvName: TextView
    private lateinit var tvRatingSummary: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvOldPrice: TextView
    private lateinit var tvWeatherBadge: TextView
    private lateinit var tvStock: TextView
    private lateinit var tvDescription: TextView
    private lateinit var btnAddToCart: Button

    private lateinit var tvReviewFormTitle: TextView
    private lateinit var ratingInput: RatingBar
    private lateinit var etComment: TextInputEditText
    private lateinit var btnSubmitReview: Button
    private lateinit var tvReviewsEmpty: TextView
    private lateinit var reviewAdapter: ReviewAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_product_detail, container, false)

        ivImage = view.findViewById(R.id.ivDetailImage)
        btnFavorite = view.findViewById(R.id.btnDetailFavorite)
        tvName = view.findViewById(R.id.tvDetailName)
        tvRatingSummary = view.findViewById(R.id.tvDetailRatingSummary)
        tvPrice = view.findViewById(R.id.tvDetailPrice)
        tvOldPrice = view.findViewById(R.id.tvDetailOldPrice)
        tvWeatherBadge = view.findViewById(R.id.tvDetailWeatherBadge)
        tvStock = view.findViewById(R.id.tvDetailStock)
        tvDescription = view.findViewById(R.id.tvDetailDescription)
        btnAddToCart = view.findViewById(R.id.btnDetailAddToCart)

        tvReviewFormTitle = view.findViewById(R.id.tvReviewFormTitle)
        ratingInput = view.findViewById(R.id.ratingBarInput)
        etComment = view.findViewById(R.id.etReviewComment)
        btnSubmitReview = view.findViewById(R.id.btnSubmitReview)
        tvReviewsEmpty = view.findViewById(R.id.tvReviewsEmpty)

        // Admin alışveriş yapmaz ve yorum yazmaz; bunun yerine ürünü düzenleyebilir
        if (UserSession.isAdmin) {
            view.findViewById<View>(R.id.cardReviewForm).visibility = View.GONE
            view.findViewById<Button>(R.id.btnDetailEdit).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, AdminAddProductFragment.newInstance(productId))
                        .addToBackStack(null)
                        .commit()
                }
            }
        } else {
            btnAddToCart.visibility = View.VISIBLE
            btnAddToCart.setOnClickListener { addToCart() }
        }

        btnFavorite.setOnClickListener { FavoritesRepository.setFavorite(productId, !isFavorite) }
        btnSubmitReview.setOnClickListener { submitReview() }

        reviewAdapter = ReviewAdapter(
            currentUid = currentUid,
            canDeleteAll = UserSession.isAdmin,
            onDelete = { review -> confirmDeleteReview(review) }
        )
        view.findViewById<RecyclerView>(R.id.recyclerReviews).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = reviewAdapter
        }

        listenProduct()
        listenReviews()
        favoritesListener = FavoritesRepository.listenFavoriteIds { ids ->
            if (!isAdded) return@listenFavoriteIds
            isFavorite = productId in ids
            btnFavorite.setImageResource(if (isFavorite) R.drawable.baseline_favorite_24 else R.drawable.ic_favorite_border_24)
            btnFavorite.setColorFilter(Color.parseColor(if (isFavorite) "#E53935" else "#333333"))
        }

        // Hava durumu gelince indirimli fiyat yeniden hesaplanır
        WeatherRepository.getCurrent { if (isAdded) product?.let { showProduct(it) } }

        return view
    }

    // Ürün canlı dinlenir; admin fiyatı/stoğu değiştirirse sayfa kendiliğinden güncellenir
    private fun listenProduct() {
        productListener = FirebaseFirestore.getInstance().collection("products").document(productId)
            .addSnapshotListener { doc, _ ->
                if (!isAdded) return@addSnapshotListener
                if (doc == null || !doc.exists()) {
                    Toast.makeText(requireContext(), "Bu ürün artık mevcut değil", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                    return@addSnapshotListener
                }
                val loaded = Product.fromDocument(doc)
                product = loaded
                showProduct(loaded)
            }
    }

    private fun showProduct(product: Product) {
        tvName.text = product.name
        tvDescription.text = product.description.ifEmpty { "Bu ürün için açıklama girilmemiş." }
        bindProductPrice(product, tvPrice, tvOldPrice, tvWeatherBadge)

        Glide.with(this)
            .load(product.imageUrls.firstOrNull() ?: R.drawable.ic_launcher_foreground)
            .placeholder(R.drawable.ic_launcher_foreground)
            .into(ivImage)

        val (stockText, stockColor) = when {
            !product.onSale -> "Satışta değil" to "#757575"
            product.isOutOfStock -> "Tükendi" to "#E53935"
            product.stock != null && product.stock <= 5 -> "Son ${product.stock} ürün!" to "#F57C00"
            else -> "Stokta var" to "#388E3C"
        }
        tvStock.text = stockText
        tvStock.setTextColor(Color.parseColor(stockColor))

        val canBuy = product.onSale && !product.isOutOfStock
        btnAddToCart.isEnabled = canBuy
        btnAddToCart.text = if (canBuy) "Sepete Ekle" else stockText
    }

    private fun addToCart() {
        val product = product ?: return
        CartRepository.addToCart(product,
            onSuccess = { if (isAdded) Toast.makeText(requireContext(), "${product.name} sepete eklendi", Toast.LENGTH_SHORT).show() },
            onFailure = { e -> if (isAdded) Toast.makeText(requireContext(), "Hata: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
        )
    }

    private fun listenReviews() {
        reviewsListener = ReviewRepository.listenReviews(productId,
            onChange = { reviews ->
                if (!isAdded) return@listenReviews
                reviewAdapter.submitList(reviews)
                tvReviewsEmpty.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE

                // Ortalama puan özeti, örn: "★ 4,3 (12 değerlendirme)"
                tvRatingSummary.text = if (reviews.isEmpty()) {
                    "Henüz değerlendirme yok"
                } else {
                    String.format(Locale("tr", "TR"), "★ %.1f (%d değerlendirme)", reviews.map { it.rating }.average(), reviews.size)
                }

                // Kullanıcının zaten yorumu varsa form "güncelle" moduna geçer
                val ownReview = reviews.firstOrNull { it.userId == currentUid }
                tvReviewFormTitle.text = if (ownReview != null) "Yorumunuzu Güncelleyin" else "Yorum Yaz"
                btnSubmitReview.text = if (ownReview != null) "Yorumu Güncelle" else "Yorumu Gönder"
                if (ownReview != null && etComment.text.isNullOrEmpty() && ratingInput.rating == 0f) {
                    ratingInput.rating = ownReview.rating.toFloat()
                    etComment.setText(ownReview.comment)
                }
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Yorumlar yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun submitReview() {
        val rating = ratingInput.rating.toInt()
        val comment = etComment.text.toString().trim()
        if (rating == 0) {
            Toast.makeText(requireContext(), "Lütfen 1-5 arası puan verin", Toast.LENGTH_SHORT).show()
            return
        }
        if (comment.length < 3) {
            etComment.error = "Yorum en az 3 karakter olmalı"
            return
        }

        btnSubmitReview.isEnabled = false
        ReviewRepository.saveReview(productId, rating, comment,
            onSuccess = {
                if (!isAdded) return@saveReview
                btnSubmitReview.isEnabled = true
                Toast.makeText(requireContext(), "Yorumunuz kaydedildi", Toast.LENGTH_SHORT).show()
            },
            onFailure = { e ->
                if (!isAdded) return@saveReview
                btnSubmitReview.isEnabled = true
                Toast.makeText(requireContext(), "Yorum kaydedilemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun confirmDeleteReview(review: Review) {
        AlertDialog.Builder(requireContext())
            .setTitle("Yorumu Sil")
            .setMessage("Bu yorum kalıcı olarak silinecek. Emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                ReviewRepository.deleteReview(productId, review.userId,
                    onSuccess = {
                        if (!isAdded) return@deleteReview
                        // Kendi yorumunu sildiyse form temizlenir
                        if (review.userId == currentUid) {
                            ratingInput.rating = 0f
                            etComment.text?.clear()
                        }
                        Toast.makeText(requireContext(), "Yorum silindi", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        if (isAdded) Toast.makeText(requireContext(), "Silinemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        productListener?.remove()
        reviewsListener?.remove()
        favoritesListener?.remove()
    }

    companion object {
        private const val ARG_PRODUCT_ID = "productId"

        fun newInstance(productId: String) = ProductDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_PRODUCT_ID, productId) }
        }
    }
}

class ReviewAdapter(
    private val currentUid: String?,
    private val canDeleteAll: Boolean, // Admin tüm yorumları silebilir (moderasyon)
    private val onDelete: (Review) -> Unit,
    // Satır başlığı; ürün sayfasında yazan kişi, "Ürün Yorumlarım"da ürün adı gösterilir
    private val titleOf: ((Review) -> String)? = null,
    private val onClick: ((Review) -> Unit)? = null
) : RecyclerView.Adapter<ReviewAdapter.ViewHolder>() {

    private var reviews: List<Review> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUser: TextView = view.findViewById(R.id.tvReviewUser)
        val ratingBar: RatingBar = view.findViewById(R.id.ratingBarReview)
        val tvDate: TextView = view.findViewById(R.id.tvReviewDate)
        val tvComment: TextView = view.findViewById(R.id.tvReviewComment)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteReview)
    }

    fun submitList(newReviews: List<Review>) {
        reviews = newReviews
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_review, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val review = reviews[position]
        val isOwn = review.userId == currentUid
        holder.tvUser.text = titleOf?.invoke(review)
            ?: if (isOwn) "${review.userName} (Siz)" else review.userName
        holder.itemView.setOnClickListener { onClick?.invoke(review) }
        holder.ratingBar.rating = review.rating.toFloat()
        holder.tvDate.text = dateFormat.format(Date(review.createdAt))
        holder.tvComment.text = review.comment

        holder.btnDelete.visibility = if (isOwn || canDeleteAll) View.VISIBLE else View.GONE
        holder.btnDelete.setOnClickListener { onDelete(review) }
    }

    override fun getItemCount() = reviews.size
}
