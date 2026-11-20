package com.example.shingu

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Ana sayfa slaytındaki kampanya (Firestore: campaigns/{id})
// Her kampanya, kendi adıyla açılan bir kategoriye bağlıdır (categoryId); banner'a dokununca o kategori açılır.
data class Campaign(
    val id: String,
    val title: String,
    val imageUrl: String,
    val categoryId: String,
    val categoryName: String
) {
    companion object {
        fun fromDocument(doc: DocumentSnapshot) = Campaign(
            id = doc.id,
            title = doc.getString("title") ?: "",
            imageUrl = doc.getString("imageUrl") ?: "",
            categoryId = doc.getString("categoryId") ?: "",
            categoryName = doc.getString("categoryName") ?: doc.getString("title") ?: ""
        )
    }
}

object CampaignRepository {

    private val db get() = FirebaseFirestore.getInstance()

    // Kampanyaları en yeniden eskiye canlı dinler (slayt ve admin listesi)
    fun listenCampaigns(onChange: (List<Campaign>) -> Unit, onFailure: (Exception) -> Unit): ListenerRegistration =
        db.collection("campaigns")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onFailure(error)
                    return@addSnapshotListener
                }
                onChange(snapshot?.documents?.map(Campaign::fromDocument) ?: emptyList())
            }

    fun getCampaign(id: String): Task<Campaign?> =
        db.collection("campaigns").document(id).get()
            .continueWith { task -> task.result.takeIf { it.exists() }?.let(Campaign::fromDocument) }

    // Kampanya + aynı adlı kategori tek işlemde oluşturulur. Aynı adda kategori varsa hata verilir
    // (aksi halde kampanya silinince admin'in elle açtığı kategori de silinirdi).
    fun create(title: String, imageUrl: String): Task<Void> =
        CategoryRepository.nameExists(title).continueWithTask { task ->
            if (task.result) throw IllegalStateException("\"$title\" adında bir kategori zaten var. Farklı bir kampanya adı girin.")

            val categoryRef = db.collection("categories").document()
            val campaignRef = db.collection("campaigns").document()
            val now = System.currentTimeMillis()
            db.batch()
                .set(categoryRef, mapOf("name" to title, "imageUrl" to imageUrl, "campaignId" to campaignRef.id, "createdAt" to now))
                .set(campaignRef, mapOf("title" to title, "imageUrl" to imageUrl, "categoryId" to categoryRef.id, "categoryName" to title, "createdAt" to now))
                .commit()
        }

    // Kampanya güncellenince bağlı kategori de aynı ad ve görselle güncellenir (ürünlerdeki kategori adı dahil)
    fun update(campaign: Campaign, newTitle: String, newImageUrl: String): Task<Void> {
        val nameCheck = if (newTitle == campaign.categoryName) Tasks.forResult(false)
        else CategoryRepository.nameExists(newTitle, exceptId = campaign.categoryId)

        return nameCheck.continueWithTask { task ->
            if (task.result) throw IllegalStateException("\"$newTitle\" adında bir kategori zaten var.")
            CategoryRepository.update(campaign.categoryId, campaign.categoryName, newTitle, newImageUrl) { batch ->
                batch.update(
                    db.collection("campaigns").document(campaign.id),
                    mapOf("title" to newTitle, "imageUrl" to newImageUrl, "categoryName" to newTitle)
                )
            }
        }
    }

    // Kampanya silinince bağlı kategori de silinir; CategoryRepository.delete kampanya belgesini de siler
    fun delete(campaign: Campaign): Task<Void> =
        CategoryRepository.delete(campaign.categoryId, campaign.categoryName)
}
