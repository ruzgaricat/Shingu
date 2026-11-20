package com.example.shingu

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

// Favoriler: users/{uid}/favorites/{productId}
// Sadece ürün kimliği saklanır; fiyat/stok gibi bilgiler her seferinde ürünün güncel halinden okunur.
object FavoritesRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun favoritesRef(uid: String) = db.collection("users").document(uid).collection("favorites")

    // Favori ürün kimliklerini canlı dinler (kalp ikonlarının dolu/boş durumu için)
    fun listenFavoriteIds(onChange: (Set<String>) -> Unit): ListenerRegistration? {
        val uid = uid ?: return null
        return favoritesRef(uid).addSnapshotListener { snapshot, _ ->
            onChange(snapshot?.documents?.map { it.id }?.toSet() ?: emptySet())
        }
    }

    fun setFavorite(productId: String, favorite: Boolean, onFailure: (Exception) -> Unit = {}) {
        val uid = uid ?: return
        val doc = favoritesRef(uid).document(productId)
        val task = if (favorite) {
            doc.set(mapOf("productId" to productId, "addedAt" to System.currentTimeMillis()))
        } else {
            doc.delete()
        }
        task.addOnFailureListener(onFailure)
    }
}
