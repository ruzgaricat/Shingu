package com.example.shingu

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import kotlin.math.min

// İndirim kuponu (Firestore: coupons/{KOD}). Belge kimliği kupon kodunun kendisidir (büyük harf).
data class Coupon(
    val code: String,
    val description: String,
    val type: String,           // "percent" (yüzde) veya "amount" (sabit TL)
    val value: Double,
    val minCartTotal: Double,   // Kuponun geçerli olması için en az sepet tutarı
    val active: Boolean,
    val expiresAt: Long?        // null ise süresiz
) {
    val isPercent: Boolean get() = type == TYPE_PERCENT

    // Ekranda gösterim: "%10 indirim" veya "50,00 TL indirim"
    val label: String
        get() = if (isPercent) "%${value.toInt()} indirim" else "${OrderPricing.format(value)} indirim"

    // Sepet tutarına göre indirim miktarı (sepet tutarını aşamaz)
    fun discountFor(subtotal: Double): Double =
        if (isPercent) subtotal * value / 100.0 else min(value, subtotal)

    // Kupon bu sepete uygulanabilir mi? Uygulanamıyorsa kullanıcıya gösterilecek sebep döner.
    fun validationError(subtotal: Double, now: Long = System.currentTimeMillis()): String? = when {
        !active -> "Bu kupon artık geçerli değil."
        expiresAt != null && now > expiresAt -> "Bu kuponun süresi dolmuş."
        subtotal < minCartTotal -> "Bu kupon en az ${OrderPricing.format(minCartTotal)} tutarındaki sepetlerde geçerli."
        else -> null
    }

    fun toMap() = mapOf(
        "code" to code,
        "description" to description,
        "type" to type,
        "value" to value,
        "minCartTotal" to minCartTotal,
        "active" to active,
        "expiresAt" to expiresAt
    )

    companion object {
        const val TYPE_PERCENT = "percent"
        const val TYPE_AMOUNT = "amount"

        fun normalize(code: String) = code.trim().uppercase(Locale("tr", "TR"))

        fun fromDocument(doc: DocumentSnapshot) = Coupon(
            code = doc.id,
            description = doc.getString("description") ?: "",
            type = doc.getString("type") ?: TYPE_PERCENT,
            value = doc.getDouble("value") ?: 0.0,
            minCartTotal = doc.getDouble("minCartTotal") ?: 0.0,
            active = doc.getBoolean("active") ?: false,
            expiresAt = doc.getLong("expiresAt")
        )
    }
}

object CouponRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val coupons get() = db.collection("coupons")

    // Tüm kuponlar (admin) veya sadece aktif olanlar (Kuponlarım ekranı)
    fun listenCoupons(onlyActive: Boolean, onChange: (List<Coupon>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration =
        coupons.addSnapshotListener { snapshot, error ->
            if (error != null) {
                onFailure(error)
                return@addSnapshotListener
            }
            val now = System.currentTimeMillis()
            val list = snapshot?.documents?.map(Coupon::fromDocument).orEmpty()
                .filter { !onlyActive || (it.active && (it.expiresAt == null || it.expiresAt > now)) }
                .sortedBy { it.code }
            onChange(list)
        }

    fun getCoupon(code: String): Task<Coupon?> =
        coupons.document(Coupon.normalize(code)).get()
            .continueWith { task -> task.result.takeIf { it.exists() }?.let(Coupon::fromDocument) }

    fun saveCoupon(coupon: Coupon): Task<Void> =
        coupons.document(coupon.code).set(coupon.toMap() + ("updatedAt" to System.currentTimeMillis()))

    fun deleteCoupon(code: String): Task<Void> = coupons.document(code).delete()
}
