package com.example.shingu

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Sipariş durumları: Firestore'da 'code' saklanır, ekranda 'label' gösterilir
enum class OrderStatus(val code: String, val label: String) {
    PENDING("pending", "Beklemede"),
    PROCESSING("processing", "İşleme alındı"),
    SHIPPED("shipped", "Kargoya verildi"),
    DELIVERED("delivered", "Teslim edildi");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

// Firestore 'orders' koleksiyonundaki bir sipariş
data class Order(
    val id: String,
    val userEmail: String,
    val items: List<Map<String, Any>>,
    val totalPrice: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val address: String? = null // Teslimat adresi (adres özelliğinden önceki siparişlerde yok)
)

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

    @Suppress("UNCHECKED_CAST")
    private fun toOrder(doc: DocumentSnapshot) = Order(
        id = doc.id,
        userEmail = doc.getString("userEmail") ?: "",
        items = doc.get("items") as? List<Map<String, Any>> ?: emptyList(),
        totalPrice = doc.getDouble("totalPrice") ?: 0.0,
        status = OrderStatus.fromCode(doc.getString("status")),
        createdAt = doc.getLong("createdAt") ?: 0L,
        address = (doc.get("address") as? Map<*, *>)?.let { a ->
            val detail = (a["detail"] as? String).orEmpty()
            val full = (a["fullAddress"] as? String).orEmpty()
            "${a["title"]}: " + if (detail.isBlank()) full else "$full - $detail"
        }
    )

    fun updateStatus(orderId: String, status: OrderStatus, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.collection("orders").document(orderId)
            .update(
                mapOf(
                    "status" to status.code,
                    "statusUpdatedAt" to System.currentTimeMillis()
                )
            )
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
