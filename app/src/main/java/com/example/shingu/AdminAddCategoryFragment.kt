package com.example.shingu

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

// Admin kategori formu: argüman olarak kategori kimliği verilirse "düzenle", verilmezse "yeni ekle" modunda açılır
class AdminAddCategoryFragment : Fragment() {

    private lateinit var db: FirebaseFirestore

    private lateinit var etCategoryName: TextInputEditText
    private lateinit var etCategoryImageUrl: TextInputEditText
    private lateinit var ivPreview: ImageView
    private lateinit var btnSave: Button

    // Varsayılan kategori görseli (Görsel girilmediğinde otomatik atanır)
    private val DEFAULT_CATEGORY_IMAGE = "https://picsum.photos/300/150?blur=1"

    private val categoryId: String? get() = arguments?.getString(ARG_CATEGORY_ID)
    private val isEditMode get() = categoryId != null

    // Düzenleme modunda kategorinin eski adı; ürünlerdeki kategori listelerini güncellemek için gerekir
    private var originalName = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_add_category, container, false)
        db = FirebaseFirestore.getInstance()

        etCategoryName = view.findViewById(R.id.etNewCategoryName)
        etCategoryImageUrl = view.findViewById(R.id.etNewCategoryImageUrl)
        ivPreview = view.findViewById(R.id.ivCategoryPreview)
        btnSave = view.findViewById(R.id.btnSaveCategory)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteCategory)

        etCategoryImageUrl.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updateImagePreview() }

        if (isEditMode) {
            view.findViewById<TextView>(R.id.tvCategoryFormTitle).text = "Yönetici: Kategoriyi Düzenle"
            btnSave.text = "Değişiklikleri Kaydet"
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete() }
            loadCategory()
        }

        btnSave.setOnClickListener { saveCategory() }

        return view
    }

    private fun loadCategory() {
        db.collection("categories").document(categoryId!!).get()
            .addOnSuccessListener { doc ->
                if (!isAdded) return@addOnSuccessListener
                if (!doc.exists()) {
                    Toast.makeText(requireContext(), "Kategori bulunamadı", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                    return@addOnSuccessListener
                }
                originalName = doc.getString("name") ?: ""
                etCategoryName.setText(originalName)
                etCategoryImageUrl.setText(doc.getString("imageUrl"))
                updateImagePreview()
            }
    }

    private fun saveCategory() {
        val categoryName = etCategoryName.text.toString().trim()
        val imageUrlInput = etCategoryImageUrl.text.toString().trim()

        if (categoryName.isEmpty()) {
            etCategoryName.error = "Kategori adı boş olamaz"
            return
        }
        val finalImageUrl = imageUrlInput.ifEmpty { DEFAULT_CATEGORY_IMAGE }

        btnSave.isEnabled = false

        // Aynı isimde başka bir kategori varsa kaydetme
        db.collection("categories").whereEqualTo("name", categoryName).get()
            .continueWithTask { task ->
                val duplicate = task.result.documents.any { it.id != categoryId }
                if (duplicate) throw IllegalStateException("\"$categoryName\" adında bir kategori zaten var.")

                if (isEditMode) {
                    updateCategory(categoryName, finalImageUrl)
                } else {
                    db.collection("categories").add(
                        hashMapOf(
                            "name" to categoryName, // Doğrudan kullanıcının girdiği gibi (Türkçe karakterler dahil) kaydedilir
                            "imageUrl" to finalImageUrl,
                            "createdAt" to System.currentTimeMillis()
                        )
                    )
                }
            }
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                btnSave.isEnabled = true
                if (isEditMode) {
                    Toast.makeText(requireContext(), "Kategori güncellendi!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    Toast.makeText(requireContext(), "Kategori başarıyla oluşturuldu!", Toast.LENGTH_SHORT).show()
                    etCategoryName.text?.clear()
                    etCategoryImageUrl.text?.clear()
                    updateImagePreview()
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), e.localizedMessage ?: "Hata oluştu", Toast.LENGTH_SHORT).show()
            }
    }

    // Kategori adı değiştiyse bu kategorideki ürünlerin kategori listesindeki eski ad da yenisiyle değiştirilir
    private fun updateCategory(newName: String, imageUrl: String): Task<Void> {
        val categoryRef = db.collection("categories").document(categoryId!!)
        val updates = mapOf("name" to newName, "imageUrl" to imageUrl)
        if (newName == originalName) return categoryRef.update(updates)

        return db.collection("products").whereArrayContains("categories", originalName).get()
            .continueWithTask { task ->
                val batch = db.batch()
                batch.update(categoryRef, updates)
                for (doc in task.result.documents) {
                    @Suppress("UNCHECKED_CAST")
                    val categories = (doc.get("categories") as? List<String>).orEmpty()
                        .map { if (it == originalName) newName else it }
                        .distinct()
                    batch.update(doc.reference, "categories", categories)
                }
                batch.commit()
            }
    }

    private fun confirmDelete() {
        // Önce bu kategorideki ürün sayısını göster, onay alınca sil
        db.collection("products").whereArrayContains("categories", originalName).get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                val count = result.size()
                val message = if (count > 0) {
                    "\"$originalName\" silinecek. Bu kategorideki $count ürün silinmez, sadece bu kategoriden çıkarılır."
                } else {
                    "\"$originalName\" kalıcı olarak silinecek."
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Kategoriyi Sil")
                    .setMessage(message)
                    .setPositiveButton("Sil") { _, _ -> deleteCategory(result.documents.map { it.reference }) }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
    }

    private fun deleteCategory(productRefs: List<com.google.firebase.firestore.DocumentReference>) {
        val batch = db.batch()
        batch.delete(db.collection("categories").document(categoryId!!))
        productRefs.forEach { batch.update(it, "categories", FieldValue.arrayRemove(originalName)) }
        batch.commit()
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                Toast.makeText(requireContext(), "Kategori silindi", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                if (isAdded) Toast.makeText(requireContext(), "Silinemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateImagePreview() {
        val url = etCategoryImageUrl.text.toString().trim()
        if (url.isEmpty()) {
            ivPreview.visibility = View.GONE
        } else {
            ivPreview.visibility = View.VISIBLE
            Glide.with(this).load(url).placeholder(R.drawable.ic_launcher_foreground).into(ivPreview)
        }
    }

    companion object {
        private const val ARG_CATEGORY_ID = "categoryId"

        // Düzenleme modu için: AdminAddCategoryFragment.newInstance(kategoriId)
        fun newInstance(categoryId: String?) = AdminAddCategoryFragment().apply {
            arguments = Bundle().apply { putString(ARG_CATEGORY_ID, categoryId) }
        }
    }
}
