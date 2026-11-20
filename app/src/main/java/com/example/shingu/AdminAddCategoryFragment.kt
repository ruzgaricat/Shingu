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
import com.google.android.material.textfield.TextInputEditText
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
    // Kategori bir kampanya tarafından açıldıysa o kampanyanın kimliği
    private var linkedCampaignId: String? = null

    // "Galeriden Seç": yüklenen görselin bağlantısı URL alanına yazılır
    private val imagePicker = ImagePickerHelper(this, "shingu/categories") { url ->
        etCategoryImageUrl.setText(url)
        updateImagePreview()
    }

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
        imagePicker.attach(view.findViewById(R.id.btnPickCategoryImage))

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
                linkedCampaignId = doc.getString("campaignId")
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
        CategoryRepository.nameExists(categoryName, exceptId = categoryId)
            .continueWithTask { task ->
                if (task.result) throw IllegalStateException("\"$categoryName\" adında bir kategori zaten var.")

                if (isEditMode) {
                    // Kampanyaya bağlı kategori yeniden adlandırılırsa kampanya başlığı da aynı kalsın
                    CategoryRepository.update(categoryId!!, originalName, categoryName, finalImageUrl) { batch ->
                        linkedCampaignId?.let { campaignId ->
                            batch.update(
                                db.collection("campaigns").document(campaignId),
                                mapOf("title" to categoryName, "categoryName" to categoryName)
                            )
                        }
                    }
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

    private fun confirmDelete() {
        // Önce bu kategorideki ürün sayısını göster, onay alınca sil
        CategoryRepository.productCount(originalName)
            .addOnSuccessListener { count ->
                if (!isAdded) return@addOnSuccessListener
                var message = if (count > 0) {
                    "\"$originalName\" silinecek. Bu kategorideki $count ürün silinmez, sadece bu kategoriden çıkarılır."
                } else {
                    "\"$originalName\" kalıcı olarak silinecek."
                }
                if (linkedCampaignId != null) message += "\n\nBu kategori bir kampanyaya bağlı; ana sayfadaki kampanya da silinecek."
                AlertDialog.Builder(requireContext())
                    .setTitle("Kategoriyi Sil")
                    .setMessage(message)
                    .setPositiveButton("Sil") { _, _ -> deleteCategory() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
    }

    private fun deleteCategory() {
        CategoryRepository.delete(categoryId!!, originalName)
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
