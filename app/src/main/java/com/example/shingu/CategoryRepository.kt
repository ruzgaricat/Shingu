package com.example.shingu

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch

// Kategori işlemleri: kategori yönetimi ve kampanyalar ortak kullanır.
// Ürünler kategoriyi ADIYLA tuttuğu (products.categories listesi) için yeniden adlandırma ve
// silme işlemleri ürünlere de yansıtılır.
object CategoryRepository {

    private val db get() = FirebaseFirestore.getInstance()

    // Aynı isimde başka bir kategori var mı? (exceptId: düzenlenen kategorinin kendisi sayılmaz)
    fun nameExists(name: String, exceptId: String? = null): Task<Boolean> =
        db.collection("categories").whereEqualTo("name", name).get()
            .continueWith { task -> task.result.documents.any { it.id != exceptId } }

    // Kategori bilgilerini günceller; ad değiştiyse bu kategorideki ürünlerin listesindeki eski ad yenisiyle değişir.
    // extraWrites: aynı işleme eklenecek başka yazmalar (örn. bağlı kampanyanın güncellenmesi)
    fun update(
        categoryId: String,
        oldName: String,
        newName: String,
        imageUrl: String,
        extraWrites: (WriteBatch) -> Unit = {}
    ): Task<Void> {
        val categoryRef = db.collection("categories").document(categoryId)
        return db.collection("products").whereArrayContains("categories", oldName).get()
            .continueWithTask { task ->
                val batch = db.batch()
                batch.update(categoryRef, mapOf("name" to newName, "imageUrl" to imageUrl))
                if (newName != oldName) {
                    for (doc in task.result.documents) {
                        @Suppress("UNCHECKED_CAST")
                        val categories = (doc.get("categories") as? List<String>).orEmpty()
                            .map { if (it == oldName) newName else it }
                            .distinct()
                        batch.update(doc.reference, "categories", categories)
                    }
                }
                extraWrites(batch)
                batch.commit()
            }
    }

    // Kategoriyi siler: ürünler silinmez, sadece bu kategoriden çıkarılır.
    // Kategoriye bağlı kampanya varsa o da silinir (slaytta boş kategoriye giden banner kalmasın).
    fun delete(categoryId: String, name: String): Task<Void> {
        val productsTask = db.collection("products").whereArrayContains("categories", name).get()
        val campaignsTask = db.collection("campaigns").whereEqualTo("categoryId", categoryId).get()
        return Tasks.whenAllSuccess<Any>(productsTask, campaignsTask)
            .continueWithTask {
                val batch = db.batch()
                batch.delete(db.collection("categories").document(categoryId))
                productsTask.result.documents.forEach {
                    batch.update(it.reference, "categories", FieldValue.arrayRemove(name))
                }
                campaignsTask.result.documents.forEach { batch.delete(it.reference) }
                batch.commit()
            }
    }

    // Kategorideki ürün sayısı (silme onayı mesajı için)
    fun productCount(name: String): Task<Int> =
        db.collection("products").whereArrayContains("categories", name).get()
            .continueWith { it.result.size() }
}
