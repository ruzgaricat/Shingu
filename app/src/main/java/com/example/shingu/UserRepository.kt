package com.example.shingu

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot

// Hesap durumları: Firestore'da 'code' saklanır, ekranda 'label' gösterilir
enum class AccountStatus(val code: String, val label: String) {
    ACTIVE("active", "Aktif"),
    SUSPENDED("suspended", "Donduruldu"),
    DELETED("deleted", "Silindi");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: ACTIVE
    }
}

// Admin kullanıcı listesi için kullanıcı bilgisi
data class UserInfo(
    val uid: String,
    val fullName: String,
    val email: String,
    val role: String,
    val status: AccountStatus
) {
    val isAdmin: Boolean get() = role == "admin"
}

// Admin kullanıcı yönetimi (listeleme, bilgi güncelleme, dondurma, silme)
object UserRepository {

    private val db get() = FirebaseFirestore.getInstance()

    // Silinmiş olanlar dahil tüm kullanıcıları ada göre sıralı, canlı dinler
    fun listenAllUsers(onChange: (List<UserInfo>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration {
        return db.collection("users").addSnapshotListener { snapshot, error ->
            if (error != null) {
                onFailure(error)
                return@addSnapshotListener
            }
            val users = snapshot?.documents?.map { doc ->
                UserInfo(
                    uid = doc.id,
                    fullName = doc.getString("fullName") ?: "",
                    email = doc.getString("email") ?: "",
                    role = doc.getString("role") ?: "user",
                    status = AccountStatus.fromCode(doc.getString("status"))
                )
            } ?: emptyList()
            onChange(users.sortedBy { it.fullName.lowercase() })
        }
    }

    // Admin kullanıcının adını ve rolünü güncelleyebilir.
    // E-posta, Firebase Auth hesabına bağlı olduğu için sadece kullanıcının kendisi değiştirebilir.
    fun updateUser(uid: String, fullName: String, role: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.collection("users").document(uid)
            .update(mapOf("fullName" to fullName, "role" to role))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Dondurma/aktifleştirme: dondurulan kullanıcı giriş yapamaz, açık oturumu da kapatılır
    fun setStatus(uid: String, status: AccountStatus, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.collection("users").document(uid)
            .update(mapOf("status" to status.code, "statusUpdatedAt" to System.currentTimeMillis()))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Hesap silme: kullanıcı "deleted" olarak işaretlenir ve kişisel verileri (sepet, favoriler,
    // adresler, yorumlar) silinir. Siparişler kayıt amacıyla korunur.
    // Not: Firebase Auth giriş hesabı uygulama içinden silinemez (sunucu yetkisi gerekir);
    // kalıcı silme için firebase-scripts klasöründeki "npm run purge-deleted" kullanılır.
    fun deleteUser(uid: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val userRef = db.collection("users").document(uid)
        val subcollections = listOf("cart", "favorites", "addresses", "reviews").map { userRef.collection(it).get() }

        Tasks.whenAllSuccess<QuerySnapshot>(subcollections)
            .continueWithTask { task ->
                val batch = db.batch()
                task.result.forEachIndexed { index, snapshot ->
                    for (doc in snapshot.documents) {
                        batch.delete(doc.reference)
                        // "reviews" kopyasının asıl yorumu ürünün altında durur; onu da sil
                        if (index == 3) {
                            batch.delete(db.collection("products").document(doc.id).collection("reviews").document(uid))
                        }
                    }
                }
                batch.update(
                    userRef, mapOf(
                        "status" to AccountStatus.DELETED.code,
                        "statusUpdatedAt" to System.currentTimeMillis()
                    )
                )
                batch.commit()
            }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
