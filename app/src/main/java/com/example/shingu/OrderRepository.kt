package com.example.shingu

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Sipariş durumları: Firestore'da 'code' saklanır, ekranda 'label' gösterilir
enum class OrderStatus(val code: String, val label: String) {
    PENDING("pending", "Beklemede"),
    PROCESSING("processing", "İşleme alındı"),
    SHIPPED("shipped", "Kargoya verildi"),
    DELIVERED("delivered", "Teslim edildi"),
    CANCELLED("cancelled", "İptal edildi");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: PENDING

        // Admin'in "Durumu Değiştir" penceresinde seçebileceği durumlar (iptal ayrı butonla, stok iadesiyle yapılır)
        val manualStatuses = listOf(PENDING, PROCESSING, SHIPPED, DELIVERED)
    }
}

// Durum geçmişindeki tek bir adım (sipariş detayındaki zaman çizelgesi için)
data class StatusChange(val status: OrderStatus, val at: Long)

// Firestore 'orders' koleksiyonundaki bir sipariş
data class Order(
    val id: String,
    val userId: String,
    val userEmail: String,
    val items: List<Map<String, Any>>,
    val totalPrice: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val address: String? = null,     // Teslimat adresi (adres özelliğinden önceki siparişlerde yok)
    val payment: String? = null,     // Örn: "Visa •••• 4242 (test)"; ödeme özelliğinden önceki siparişlerde yok
    val subtotal: Double? = null,    // Kupon/kargo özelliğinden önceki siparişlerde yok
    val couponCode: String? = null,
    val couponDiscount: Double = 0.0,
    val shippingFee: Double = 0.0,
    val statusHistory: List<StatusChange> = emptyList(),
    val cancelledBy: String? = null  // "user" veya "admin"
) {
    // Kullanıcı sadece henüz işleme alınmamış siparişini iptal edebilir; admin teslim edilmemiş her siparişi
    val canUserCancel: Boolean get() = status == OrderStatus.PENDING
    val canAdminCancel: Boolean get() = status != OrderStatus.DELIVERED && status != OrderStatus.CANCELLED
}

object OrderRepository {

    private val db get() = FirebaseFirestore.getInstance()

    // Tüm siparişleri en yeniden eskiye canlı dinler (admin ekranı için)
    fun listenAllOrders(onChange: (List<Order>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration {
        return db.collection("orders")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                onChange(snapshot?.documents?.map(::toOrder) ?: emptyList())
            }
    }

    // Sadece verilen kullanıcının siparişlerini canlı dinler (Siparişlerim ekranı için).
    // whereEqualTo + orderBy birleşik index gerektirdiği için sıralama cihazda yapılır.
    fun listenUserOrders(userId: String, onChange: (List<Order>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration {
        return db.collection("orders")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                val orders = snapshot?.documents?.map(::toOrder) ?: emptyList()
                onChange(orders.sortedByDescending { it.createdAt })
            }
    }

    // Tek bir siparişi canlı dinler (sipariş detay ekranı)
    fun listenOrder(orderId: String, onChange: (Order?) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration =
        db.collection("orders").document(orderId).addSnapshotListener { doc, error ->
            if (error != null) {
                onFailure(error)
                return@addSnapshotListener
            }
            onChange(doc?.takeIf { it.exists() }?.let(::toOrder))
        }

    @Suppress("UNCHECKED_CAST")
    private fun toOrder(doc: DocumentSnapshot) = Order(
        id = doc.id,
        userId = doc.getString("userId") ?: "",
        userEmail = doc.getString("userEmail") ?: "",
        items = doc.get("items") as? List<Map<String, Any>> ?: emptyList(),
        totalPrice = doc.getDouble("totalPrice") ?: 0.0,
        status = OrderStatus.fromCode(doc.getString("status")),
        createdAt = doc.getLong("createdAt") ?: 0L,
        address = (doc.get("address") as? Map<*, *>)?.let { a ->
            val detail = (a["detail"] as? String).orEmpty()
            val full = (a["fullAddress"] as? String).orEmpty()
            "${a["title"]}: " + if (detail.isBlank()) full else "$full - $detail"
        },
        payment = (doc.get("payment") as? Map<*, *>)?.let { p ->
            "${p["cardBrand"]} •••• ${p["cardLast4"]}" + if (p["testMode"] == true) " (test)" else ""
        },
        subtotal = doc.getDouble("subtotal"),
        couponCode = doc.getString("couponCode"),
        couponDiscount = doc.getDouble("couponDiscount") ?: 0.0,
        shippingFee = doc.getDouble("shippingFee") ?: 0.0,
        statusHistory = (doc.get("statusHistory") as? List<Map<String, Any>>).orEmpty().map {
            StatusChange(OrderStatus.fromCode(it["status"] as? String), (it["at"] as? Number)?.toLong() ?: 0L)
        },
        cancelledBy = doc.getString("cancelledBy")
    )

    // Admin durum değişikliği; değişiklik durum geçmişine de eklenir
    fun updateStatus(orderId: String, status: OrderStatus, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val now = System.currentTimeMillis()
        db.collection("orders").document(orderId)
            .update(
                mapOf(
                    "status" to status.code,
                    "statusUpdatedAt" to now,
                    "statusHistory" to FieldValue.arrayUnion(mapOf("status" to status.code, "at" to now))
                )
            )
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Siparişi iptal eder ve ürünleri stoğa geri ekler (tek transaction).
    // Kullanıcı sadece "Beklemede" olan kendi siparişini, admin teslim edilmemiş her siparişi iptal edebilir.
    // Ürüne yazılan "lastRestockOrderId", güvenlik kuralının stok iadesinin gerçekten bu iptalden geldiğini
    // doğrulaması içindir (aynı sipariş için iki kez stok iadesi yapılamaz).
    fun cancelOrder(orderId: String, byAdmin: Boolean, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val orderRef = db.collection("orders").document(orderId)
        db.runTransaction { tx ->
            val orderSnap = tx.get(orderRef)
            if (!orderSnap.exists()) throw CartRepository.userError("Sipariş bulunamadı.")
            val order = toOrder(orderSnap)
            if (byAdmin && !order.canAdminCancel) throw CartRepository.userError("Bu sipariş iptal edilemez (${order.status.label}).")
            if (!byAdmin && !order.canUserCancel) {
                throw CartRepository.userError("Sipariş \"${order.status.label}\" durumunda olduğu için iptal edilemez.")
            }

            // Stok iadesi için iptal edilecek adetler. Eski siparişlerde "itemQuantities" alanı yoksa admin
            // ürün listesinden hesaplar; kullanıcının güvenlik kuralı bu alanı şart koştuğu için iade yapılmaz.
            @Suppress("UNCHECKED_CAST")
            val quantities: Map<String, Long> = (orderSnap.get("itemQuantities") as? Map<String, Number>)
                ?.mapValues { it.value.toLong() }
                ?: if (byAdmin) order.items.associate { (it["productId"] as String) to ((it["quantity"] as Number).toLong()) } else emptyMap()

            val productRefs = quantities.keys.map { db.collection("products").document(it) }
            val productSnaps = productRefs.map { tx.get(it) }

            // Yazmalar: stok girilmiş ve hâlâ var olan ürünlere stok iadesi, ardından sipariş durumu
            productSnaps.forEachIndexed { i, snap ->
                if (!snap.exists()) return@forEachIndexed
                val stock = snap.getLong("stock") ?: return@forEachIndexed
                tx.update(
                    productRefs[i], mapOf(
                        "stock" to stock + (quantities[snap.id] ?: 0L),
                        "lastRestockOrderId" to orderId
                    )
                )
            }
            val now = System.currentTimeMillis()
            tx.update(
                orderRef, mapOf(
                    "status" to OrderStatus.CANCELLED.code,
                    "statusUpdatedAt" to now,
                    "cancelledBy" to if (byAdmin) "admin" else "user",
                    "statusHistory" to FieldValue.arrayUnion(mapOf("status" to OrderStatus.CANCELLED.code, "at" to now))
                )
            )
            null
        }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
