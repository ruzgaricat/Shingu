package com.example.shingu

import com.example.shingu.api.WeatherDiscount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

// Sepetteki tek bir satır (Firestore: users/{uid}/cart/{productId})
data class CartItem(
    val productId: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val quantity: Long = 1,
    val weatherTag: String? = null,
    val weatherDiscount: Int = 0
) {
    // Hava durumu indirimi o an geçerliyse indirimli birim fiyat, değilse normal fiyat
    val unitPrice: Double get() = WeatherDiscount.effectivePrice(price, weatherTag, weatherDiscount)
    val lineTotal: Double get() = unitPrice * quantity
    val hasWeatherDiscount: Boolean get() = WeatherDiscount.isActive(weatherTag, weatherDiscount)
}

// Sepet ve sipariş işlemlerini tek yerde toplayan yardımcı nesne
object CartRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun cartRef(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("cart")

    fun addToCart(product: Product, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val uid = uid ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        if (product.id.isEmpty()) return onFailure(IllegalArgumentException("Ürün kimliği bulunamadı"))

        // Ürün sepette yoksa oluşturulur, varsa adedi 1 artırılır
        val data = hashMapOf(
            "productId" to product.id,
            "name" to product.name,
            "price" to product.price,
            "imageUrl" to (product.imageUrls.firstOrNull() ?: ""),
            // İndirim sepette saklanmaz; etiket saklanır, fiyat o anki havaya göre hesaplanır
            "weatherTag" to product.weatherTag,
            "weatherDiscount" to product.weatherDiscount,
            "quantity" to FieldValue.increment(1)
        )
        cartRef(uid).document(product.id)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Sepeti canlı dinler; ekran kapanınca dönen kayıt remove() ile kapatılmalı
    fun listenCart(onChange: (List<CartItem>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration? {
        val uid = uid ?: return null
        return cartRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onFailure(error)
                return@addSnapshotListener
            }
            val items = snapshot?.documents?.mapNotNull { it.toObject(CartItem::class.java) } ?: emptyList()
            onChange(items)
        }
    }

    // Adedi değiştirir; 1'in altına düşerse ürün sepetten çıkarılır
    fun changeQuantity(item: CartItem, delta: Long) {
        val uid = uid ?: return
        val newQuantity = item.quantity + delta
        val doc = cartRef(uid).document(item.productId)
        if (newQuantity < 1) doc.delete() else doc.update("quantity", newQuantity)
    }

    fun removeFromCart(item: CartItem) {
        val uid = uid ?: return
        cartRef(uid).document(item.productId).delete()
    }

    // Siparişi tek bir transaction ile verir:
    // 1) Her ürünün güncel halini okur (satışta mı, stok yeterli mi, güncel fiyat/indirim)
    // 2) Stokları düşer, siparişi 'orders'a yazar ve sepeti temizler
    // Herhangi bir ürün uygun değilse hiçbir şey yazılmaz.
    // payment: ödeme özeti (yöntem, kart türü, son 4 hane, durum); kart numarasının tamamı ve CVV asla gelmez
    fun placeOrder(items: List<CartItem>, address: Address, payment: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        if (items.isEmpty()) return onFailure(IllegalStateException("Sepet boş"))

        db.runTransaction { tx ->
            val productRefs = items.map { db.collection("products").document(it.productId) }
            val snapshots = productRefs.map { tx.get(it) } // Transaction'da önce tüm okumalar yapılmalı

            val orderItems = mutableListOf<Map<String, Any>>()
            var total = 0.0
            items.forEachIndexed { i, item ->
                val snapshot = snapshots[i]
                if (!snapshot.exists()) throw orderError("${item.name} artık satılmıyor, lütfen sepetten çıkarın.")
                val product = Product.fromDocument(snapshot)
                if (!product.onSale) throw orderError("${product.name} şu an satışta değil.")
                if (product.stock != null && product.stock < item.quantity) {
                    throw orderError("${product.name} için stokta sadece ${product.stock} adet var.")
                }

                // Fiyat ve indirim sepetteki kopyadan değil, ürünün güncel halinden hesaplanır
                val unitPrice = WeatherDiscount.effectivePrice(product.price, product.weatherTag, product.weatherDiscount)
                val discountApplied = WeatherDiscount.isActive(product.weatherTag, product.weatherDiscount)
                orderItems += mapOf(
                    "productId" to product.id,
                    "name" to product.name,
                    "price" to unitPrice, // Ödenen birim fiyat (indirim dahil)
                    "originalPrice" to product.price,
                    "weatherDiscount" to if (discountApplied) product.weatherDiscount else 0,
                    "quantity" to item.quantity
                )
                total += unitPrice * item.quantity
            }

            // Yazma işlemleri: stok düşümü (stok girilmemiş ürünlerde yapılmaz), sipariş, sepet temizliği
            items.forEachIndexed { i, item ->
                val stock = snapshots[i].getLong("stock") ?: return@forEachIndexed
                tx.update(productRefs[i], "stock", stock - item.quantity)
            }
            tx.set(
                db.collection("orders").document(), hashMapOf(
                    "userId" to user.uid,
                    "userEmail" to (user.email ?: ""),
                    "items" to orderItems,
                    "totalPrice" to total,
                    "address" to address.toMap(), // Teslimat adresinin o anki kopyası
                    "payment" to payment,
                    "status" to OrderStatus.PENDING.code,
                    "createdAt" to System.currentTimeMillis()
                )
            )
            items.forEach { tx.delete(cartRef(user.uid).document(it.productId)) }
            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Transaction'ı yeniden denemeden durduran, kullanıcıya gösterilecek hata
    private fun orderError(message: String) =
        FirebaseFirestoreException(message, FirebaseFirestoreException.Code.FAILED_PRECONDITION)
}
