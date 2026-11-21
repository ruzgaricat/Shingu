package com.example.shingu

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
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
        deleteUserData(uid, removeProfile = false)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Kullanıcının KENDİ hesabını kalıcı olarak silmesi (Google Play ve KVKK "silinme hakkı" gereği):
    // 1) Güvenlik için yeniden doğrulama (e-posta/şifre hesaplarında şifre sorulur)
    // 2) Kişisel veriler (profil, sepet, favoriler, adresler, yorumlar) silinir
    // 3) Firebase Auth giriş hesabı silinir; aynı e-postayla yeniden kayıt olunabilir
    // Siparişler muhasebe/yasal kayıt amacıyla saklanır. Yönetici hesabı buradan silinemez.
    fun deleteOwnAccount(password: String?, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        if (UserSession.isAdmin) {
            return onFailure(IllegalStateException("Yönetici hesabı uygulama içinden silinemez."))
        }

        val reauth: Task<Void> = if (password != null) {
            user.reauthenticate(EmailAuthProvider.getCredential(user.email ?: "", password))
        } else {
            // Google ile giren hesapta şifre yoktur; Firebase hesap silmek için son birkaç dakika içinde
            // giriş yapılmış olmasını ister. Veriler silinmeden önce bu kontrol edilir (yarım silme olmasın).
            val lastSignIn = user.metadata?.lastSignInTimestamp ?: 0L
            if (System.currentTimeMillis() - lastSignIn > RECENT_LOGIN_MS) {
                return onFailure(IllegalStateException("Güvenlik nedeniyle önce çıkış yapıp tekrar giriş yapın, ardından hesabınızı silin."))
            }
            Tasks.forResult(null)
        }

        reauth
            .continueWithTask { task ->
                if (!task.isSuccessful) return@continueWithTask Tasks.forException<Void>(task.exception!!)
                deleteUserData(user.uid, removeProfile = true)
            }
            .continueWithTask { task ->
                if (!task.isSuccessful) return@continueWithTask Tasks.forException<Void>(task.exception!!)
                user.delete()
            }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Kullanıcının alt verilerini (sepet, favoriler, adresler, yorum kopyaları ve ürünlerdeki asıl yorumları) siler.
    // removeProfile: true -> kullanıcı belgesi de silinir (kendi hesabını silme);
    //                false -> belge "deleted" olarak işaretlenir (admin silmesi; giriş engellensin diye kalır)
    private fun deleteUserData(uid: String, removeProfile: Boolean): Task<Void> {
        val userRef = db.collection("users").document(uid)
        val subcollections = listOf("cart", "favorites", "addresses", "reviews").map { userRef.collection(it).get() }

        return Tasks.whenAllSuccess<QuerySnapshot>(subcollections)
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
                if (removeProfile) {
                    batch.delete(userRef)
                } else {
                    batch.update(
                        userRef, mapOf(
                            "status" to AccountStatus.DELETED.code,
                            "statusUpdatedAt" to System.currentTimeMillis()
                        )
                    )
                }
                batch.commit()
            }
    }

    private const val RECENT_LOGIN_MS = 4 * 60 * 1000L
}
