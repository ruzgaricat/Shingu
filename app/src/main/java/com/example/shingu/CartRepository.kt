package com.example.shingu

import com.example.shingu.api.WeatherDiscount
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

// Sepetteki tek bir satır (Firestore: users/{uid}/cart/{productId})
data class CartItem(
    val productId: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val quantity: Long = 1,
    val weatherTag: String? = null,
    val weatherDiscount: Int = 0,
    // Aşağıdaki iki alan Firestore'da saklanmaz; ürünün güncel halinden doldurulur (listenCartLive)
    val available: Boolean = true, // Ürün silindiyse veya satıştan kaldırıldıysa false
    val stock: Long? = null
) {
    // Hava durumu indirimi o an geçerliyse indirimli birim fiyat, değilse normal fiyat
    val unitPrice: Double get() = WeatherDiscount.effectivePrice(price, weatherTag, weatherDiscount)
    val lineTotal: Double get() = if (available) unitPrice * quantity else 0.0
    val hasWeatherDiscount: Boolean get() = WeatherDiscount.isActive(weatherTag, weatherDiscount)
    val exceedsStock: Boolean get() = stock != null && quantity > stock
}

// Sepet ve sipariş işlemlerini tek yerde toplayan yardımcı nesne
object CartRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun cartRef(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("cart")

    // Sepete 1 adet ekler. Ürünün güncel hali okunur: satışta değilse veya sepetteki adet stoğu
    // aşacaksa eklenmez (transaction ile, aynı anda iki eklemede de stok aşılmaz).
    fun addToCart(product: Product, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val uid = uid ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        if (product.id.isEmpty()) return onFailure(IllegalArgumentException("Ürün kimliği bulunamadı"))

        val productRef = db.collection("products").document(product.id)
        val cartDoc = cartRef(uid).document(product.id)
        db.runTransaction { tx ->
            val productSnap = tx.get(productRef)
            val cartSnap = tx.get(cartDoc)
            if (!productSnap.exists()) throw userError("Bu ürün artık satılmıyor.")
            val live = Product.fromDocument(productSnap)
            if (!live.onSale) throw userError("${live.name} şu an satışta değil.")

            val newQuantity = (cartSnap.getLong("quantity") ?: 0L) + 1
            if (live.stock != null && newQuantity > live.stock) {
                throw userError(
                    if (live.stock <= 0) "${live.name} tükendi."
                    else "${live.name} için stokta ${live.stock} adet var, sepetinizde zaten ${newQuantity - 1} adet bulunuyor."
                )
            }
            tx.set(
                cartDoc, mapOf(
                    "productId" to live.id,
                    "name" to live.name,
                    "price" to live.price,
                    "imageUrl" to (live.imageUrls.firstOrNull() ?: ""),
                    // İndirim sepette saklanmaz; etiket saklanır, fiyat o anki havaya göre hesaplanır
                    "weatherTag" to live.weatherTag,
                    "weatherDiscount" to live.weatherDiscount,
                    "quantity" to newQuantity
                )
            )
            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Sepeti ham haliyle (sepete eklendiği andaki bilgilerle) canlı dinler
    private fun listenCart(onChange: (List<CartItem>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration? {
        val uid = uid ?: return null
        return cartRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onFailure(error)
                return@addSnapshotListener
            }
            onChange(snapshot?.documents?.mapNotNull { it.toObject(CartItem::class.java) } ?: emptyList())
        }
    }

    // Sepeti, ürünlerin GÜNCEL fiyat/ad/görsel/stok bilgileriyle birleştirerek dinler.
    // Admin fiyatı değiştirirse sepet yeni fiyatı gösterir; silinen/satıştan kalkan ürünler "available=false" olur.
    fun listenCartLive(onChange: (List<CartItem>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration? {
        var requestNo = 0 // Geç gelen eski bir yükleme, yenisinin üzerine yazmasın
        return listenCart(onFailure = onFailure, onChange = { items ->
            val current = ++requestNo
            Product.loadByIds(items.map { it.productId }) { products ->
                if (current != requestNo) return@loadByIds
                val byId = products.associateBy { it.id }
                onChange(items.map { item ->
                    val live = byId[item.productId]
                    if (live == null) {
                        item.copy(available = false)
                    } else {
                        item.copy(
                            name = live.name,
                            price = live.price,
                            imageUrl = live.imageUrls.firstOrNull() ?: item.imageUrl,
                            weatherTag = live.weatherTag,
                            weatherDiscount = live.weatherDiscount,
                            available = live.onSale,
                            stock = live.stock
                        )
                    }
                })
            }
        })
    }

    // Adedi değiştirir; 1'in altına düşerse ürün sepetten çıkarılır. Artırırken stok kontrol edilir.
    fun changeQuantity(item: CartItem, delta: Long, onFailure: (Exception) -> Unit = {}) {
        val uid = uid ?: return
        val newQuantity = item.quantity + delta
        val doc = cartRef(uid).document(item.productId)
        when {
            newQuantity < 1 -> doc.delete()
            delta > 0 && item.stock != null && newQuantity > item.stock ->
                onFailure(userError("${item.name} için stokta sadece ${item.stock} adet var."))
            else -> doc.update("quantity", newQuantity)
        }
    }

    fun removeFromCart(item: CartItem) {
        val uid = uid ?: return
        cartRef(uid).document(item.productId).delete()
    }

    // Siparişi tek bir transaction ile verir:
    // 1) Her ürünün ve (varsa) kuponun güncel halini okur: satışta mı, stok yeterli mi, kupon geçerli mi
    // 2) Tutarı yeniden hesaplar (ara toplam, kupon, kargo), stokları düşer, siparişi yazar, sepeti temizler
    // Herhangi bir koşul sağlanmazsa hiçbir şey yazılmaz.
    // payment: ödeme özeti (yöntem, kart türü, son 4 hane, durum); kart numarasının tamamı ve CVV asla gelmez
    fun placeOrder(
        items: List<CartItem>,
        address: Address,
        payment: Map<String, Any>,
        couponCode: String?,
        agreementsAcceptedAt: Long,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        if (items.isEmpty()) return onFailure(IllegalStateException("Sepet boş"))

        db.runTransaction { tx ->
            // Transaction'da önce tüm okumalar yapılmalı
            val productRefs = items.map { db.collection("products").document(it.productId) }
            val snapshots = productRefs.map { tx.get(it) }
            val couponSnap = couponCode?.let { tx.get(db.collection("coupons").document(it)) }

            val orderItems = mutableListOf<Map<String, Any>>()
            val itemQuantities = mutableMapOf<String, Long>() // İptalde stok iadesi için (güvenlik kuralı da kullanır)
            var subtotal = 0.0
            items.forEachIndexed { i, item ->
                val snapshot = snapshots[i]
                if (!snapshot.exists()) throw userError("${item.name} artık satılmıyor, lütfen sepetten çıkarın.")
                val product = Product.fromDocument(snapshot)
                if (!product.onSale) throw userError("${product.name} şu an satışta değil.")
                if (product.stock != null && product.stock < item.quantity) {
                    throw userError("${product.name} için stokta sadece ${product.stock} adet var.")
                }

                // Fiyat ve indirim sepetteki kopyadan değil, ürünün güncel halinden hesaplanır
                val unitPrice = WeatherDiscount.effectivePrice(product.price, product.weatherTag, product.weatherDiscount)
                val discountApplied = WeatherDiscount.isActive(product.weatherTag, product.weatherDiscount)
                orderItems += mapOf(
                    "productId" to product.id,
                    "name" to product.name,
                    "imageUrl" to (product.imageUrls.firstOrNull() ?: ""),
                    "price" to unitPrice, // Ödenen birim fiyat (indirim dahil)
                    "originalPrice" to product.price,
                    "weatherDiscount" to if (discountApplied) product.weatherDiscount else 0,
                    "quantity" to item.quantity
                )
                itemQuantities[product.id] = item.quantity
                subtotal += unitPrice * item.quantity
            }

            // Kupon sepette uygulanmış olsa bile burada tekrar doğrulanır (bu arada süresi dolmuş olabilir)
            val coupon = couponSnap?.let {
                if (!it.exists()) throw userError("Kupon bulunamadı: $couponCode")
                Coupon.fromDocument(it).also { c -> c.validationError(subtotal)?.let { msg -> throw userError(msg) } }
            }
            val pricing = OrderPricing.calculate(subtotal, coupon)

            // Yazma işlemleri: stok düşümü (stok girilmemiş ürünlerde yapılmaz), sipariş, sepet temizliği
            items.forEachIndexed { i, item ->
                val stock = snapshots[i].getLong("stock") ?: return@forEachIndexed
                tx.update(productRefs[i], "stock", stock - item.quantity)
            }
            val now = System.currentTimeMillis()
            tx.set(
                db.collection("orders").document(), hashMapOf(
                    "userId" to user.uid,
                    "userEmail" to (user.email ?: ""),
                    "items" to orderItems,
                    "itemQuantities" to itemQuantities,
                    "subtotal" to pricing.subtotal,
                    "couponCode" to pricing.couponCode,
                    "couponDiscount" to pricing.couponDiscount,
                    "shippingFee" to pricing.shippingFee,
                    "totalPrice" to pricing.total,
                    "address" to address.toMap(), // Teslimat adresinin o anki kopyası
                    "payment" to payment,
                    "agreementsAcceptedAt" to agreementsAcceptedAt, // Mesafeli satış sözleşmesi onayı
                    "status" to OrderStatus.PENDING.code,
                    "statusHistory" to listOf(mapOf("status" to OrderStatus.PENDING.code, "at" to now)),
                    "createdAt" to now
                )
            )
            items.forEach { tx.delete(cartRef(user.uid).document(it.productId)) }
            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Sepetteki kupon kodunu doğrular: varsa ve bu sepet tutarı için geçerliyse kuponu döndürür
    fun validateCoupon(code: String, subtotal: Double): Task<Coupon> =
        CouponRepository.getCoupon(code).continueWith { task ->
            val coupon = task.result ?: throw userError("\"${Coupon.normalize(code)}\" adında bir kupon bulunamadı.")
            coupon.validationError(subtotal)?.let { throw userError(it) }
            coupon
        }

    // Transaction'ı yeniden denemeden durduran, kullanıcıya gösterilecek hata
    fun userError(message: String) =
        FirebaseFirestoreException(message, FirebaseFirestoreException.Code.FAILED_PRECONDITION)
}
