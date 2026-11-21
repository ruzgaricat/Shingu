package com.example.shingu

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Kayıtlı teslimat adresi (Firestore: users/{uid}/addresses/{addressId})
data class Address(
    val id: String = "",
    val title: String,        // Örn: "Ev", "İş"
    val fullAddress: String,  // Haritadan otomatik bulunan cadde/mahalle/ilçe
    val detail: String,       // Kullanıcının elle yazdığı bina/daire/kapı no
    val latitude: Double,
    val longitude: Double,
    val countryCode: String? = null // Haritadan bulunan ülke kodu (örn. "tr"); eski adreslerde yok
) {
    // Sipariş kaydına adresin o anki kopyası yazılır (adres sonradan silinse de sipariş bozulmaz)
    fun toMap() = mapOf(
        "title" to title,
        "fullAddress" to fullAddress,
        "detail" to detail,
        "latitude" to latitude,
        "longitude" to longitude,
        "countryCode" to countryCode
    )

    // Bu adrese teslimat yapılabilir mi? (Dağıtım alanı: Türkiye)
    val isInDeliveryArea: Boolean get() = DeliveryArea.isServed(countryCode, latitude, longitude)

    // Tek satırlık gösterim: "Gazanfer Bilge Bulvarı, Körfez Mah., İzmit/Kocaeli - No:5 D:3"
    val oneLine: String get() = if (detail.isBlank()) fullAddress else "$fullAddress - $detail"
}

object AddressRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun addressesRef(uid: String) = db.collection("users").document(uid).collection("addresses")

    // Adresleri en yeniden eskiye canlı dinler
    fun listenAddresses(onChange: (List<Address>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration? {
        val uid = uid ?: return null
        return addressesRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                onChange(snapshot?.documents?.map { doc ->
                    Address(
                        id = doc.id,
                        title = doc.getString("title") ?: "Adres",
                        fullAddress = doc.getString("fullAddress") ?: "",
                        detail = doc.getString("detail") ?: "",
                        latitude = doc.getDouble("latitude") ?: 0.0,
                        longitude = doc.getDouble("longitude") ?: 0.0,
                        countryCode = doc.getString("countryCode")
                    )
                } ?: emptyList())
            }
    }

    fun addAddress(address: Address, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val uid = uid ?: return onFailure(IllegalStateException("Giriş yapılmamış"))
        addressesRef(uid)
            .add(address.toMap() + ("createdAt" to System.currentTimeMillis()))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun deleteAddress(addressId: String, onFailure: (Exception) -> Unit = {}) {
        val uid = uid ?: return
        addressesRef(uid).document(addressId).delete().addOnFailureListener(onFailure)
    }
}
