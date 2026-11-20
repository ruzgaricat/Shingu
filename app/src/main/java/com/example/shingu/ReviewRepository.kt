package com.example.shingu

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Ürün yorumu (Firestore: products/{productId}/reviews/{userId})
// Belge kimliği kullanıcının uid'i olduğu için her kullanıcı bir ürüne tek yorum yazabilir; tekrar yazarsa güncellenir.
// Aynı yorumun bir kopyası users/{userId}/reviews/{productId} altında da tutulur ("Ürün Yorumlarım" ekranı için).
data class Review(
    val userId: String,
    val userName: String,
    val rating: Int,
    val comment: String,
    val createdAt: Long,
    val productId: String = ""
)

object ReviewRepository {

    private val db get() = FirebaseFirestore.getInstance()

    private fun reviewsRef(productId: String) =
        db.collection("products").document(productId).collection("reviews")

    private fun userReviewsRef(userId: String) =
        db.collection("users").document(userId).collection("reviews")

    fun listenReviews(productId: String, onChange: (List<Review>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration {
        return reviewsRef(productId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                val reviews = snapshot?.documents?.map { doc ->
                    Review(
                        userId = doc.id,
                        userName = doc.getString("userName") ?: "Kullanıcı",
                        rating = doc.getLong("rating")?.toInt() ?: 0,
                        comment = doc.getString("comment") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L
                    )
                } ?: emptyList()
                onChange(reviews)
            }
    }

    fun saveReview(productId: String, rating: Int, comment: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onFailure(IllegalStateException("Giriş yapılmamış"))

        // Yorumda görünecek ad Firestore'daki profil adından alınır
        db.collection("users").document(user.uid).get()
            .continueWithTask { task ->
                val userName = task.result?.getString("fullName")
                    ?: user.displayName
                    ?: user.email?.substringBefore("@")
                    ?: "Kullanıcı"
                val review = mapOf(
                    "userId" to user.uid,
                    "userName" to userName,
                    "productId" to productId,
                    "rating" to rating,
                    "comment" to comment,
                    "createdAt" to System.currentTimeMillis()
                )
                // Ürün altındaki yorum ve kullanıcı altındaki kopyası birlikte yazılır
                db.batch()
                    .set(reviewsRef(productId).document(user.uid), review)
                    .set(userReviewsRef(user.uid).document(productId), review)
                    .commit()
            }
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    // Kullanıcının tüm yorumlarını en yeniden eskiye canlı dinler
    fun listenUserReviews(userId: String, onChange: (List<Review>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration {
        return userReviewsRef(userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                val reviews = snapshot?.documents?.map { doc ->
                    Review(
                        userId = userId,
                        userName = doc.getString("userName") ?: "Kullanıcı",
                        rating = doc.getLong("rating")?.toInt() ?: 0,
                        comment = doc.getString("comment") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        productId = doc.id
                    )
                } ?: emptyList()
                onChange(reviews)
            }
    }

    // Yorum hem ürün altından hem kullanıcının kopyasından silinir
    fun deleteReview(productId: String, userId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        db.batch()
            .delete(reviewsRef(productId).document(userId))
            .delete(userReviewsRef(userId).document(productId))
            .commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
