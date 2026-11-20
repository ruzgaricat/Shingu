package com.example.shingu

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.example.shingu.api.WeatherCondition
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

// Admin ürün formu: argüman olarak ürün kimliği verilirse "düzenle", verilmezse "yeni ekle" modunda açılır
class AdminAddProductFragment : Fragment() {

    private lateinit var db: FirebaseFirestore
    private val selectedCategories = mutableListOf<String>()
    private lateinit var chipGroup: ChipGroup
    private lateinit var actvCategory: AutoCompleteTextView

    private lateinit var etName: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var etPrice: TextInputEditText
    private lateinit var etStock: TextInputEditText
    private lateinit var etImageUrl: TextInputEditText
    private lateinit var ivPreview: ImageView
    private lateinit var btnRemoveImage: Button
    private lateinit var switchOnSale: MaterialSwitch
    private lateinit var actvWeatherTag: AutoCompleteTextView
    private lateinit var layoutWeatherDiscount: TextInputLayout
    private lateinit var etWeatherDiscount: TextInputEditText
    private lateinit var btnSave: Button

    // Seçilen hava durumu etiketi (null = indirim yok)
    private var selectedWeather: WeatherCondition? = null

    // "Galeriden Seç": yüklenen görselin bağlantısı URL alanına yazılır
    private val imagePicker = ImagePickerHelper(this, "shingu/products") { url ->
        etImageUrl.setText(url)
        updateImagePreview()
    }

    private val productId: String? get() = arguments?.getString(ARG_PRODUCT_ID)
    private val isEditMode get() = productId != null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_add_product, container, false)
        db = FirebaseFirestore.getInstance()

        etName = view.findViewById(R.id.etProductName)
        etDescription = view.findViewById(R.id.etProductDescription)
        etPrice = view.findViewById(R.id.etProductPrice)
        etStock = view.findViewById(R.id.etProductStock)
        etImageUrl = view.findViewById(R.id.etProductImageUrl)
        ivPreview = view.findViewById(R.id.ivProductPreview)
        btnRemoveImage = view.findViewById(R.id.btnRemoveProductImage)
        switchOnSale = view.findViewById(R.id.switchProductOnSale)
        btnSave = view.findViewById(R.id.btnAddProduct)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteProduct)

        actvCategory = view.findViewById(R.id.actvCategorySelect)
        chipGroup = view.findViewById(R.id.chipGroupCategories)

        // Sadece önceden oluşturulmuş kategorileri havuzdan çekiyoruz
        loadPredefinedCategories()

        actvWeatherTag = view.findViewById(R.id.actvWeatherTag)
        layoutWeatherDiscount = view.findViewById(R.id.layoutWeatherDiscount)
        etWeatherDiscount = view.findViewById(R.id.etWeatherDiscount)
        setupWeatherTag()

        // Görsel URL'si girildikçe önizleme güncellenir; "Görseli Kaldır" alanı temizler
        etImageUrl.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updateImagePreview() }
        imagePicker.attach(view.findViewById(R.id.btnPickProductImage))
        btnRemoveImage.setOnClickListener {
            etImageUrl.text?.clear()
            updateImagePreview()
        }

        if (isEditMode) {
            view.findViewById<TextView>(R.id.tvProductFormTitle).text = "Yönetici: Ürünü Düzenle"
            btnSave.text = "Değişiklikleri Kaydet"
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete() }
            loadProduct()
        }

        btnSave.setOnClickListener { saveProduct() }

        return view
    }

    private fun saveProduct() {
        val name = etName.text.toString().trim()
        val description = etDescription.text.toString().trim()
        // Türkçe klavyede ondalık ayırıcı virgül olabilir ("12,5" -> 12.5)
        val price = etPrice.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val stock = etStock.text.toString().trim().toLongOrNull()
        val imageUrl = etImageUrl.text.toString().trim()

        if (name.isEmpty()) {
            etName.error = "Ürün adı boş olamaz"
            return
        }
        if (price == null || price <= 0) {
            etPrice.error = "Geçerli bir fiyat girin"
            return
        }
        if (stock == null || stock < 0) {
            etStock.error = "Geçerli bir adet girin (0 veya üzeri)"
            return
        }
        if (selectedCategories.isEmpty()) {
            Toast.makeText(requireContext(), "En az bir kategori seçin.", Toast.LENGTH_SHORT).show()
            return
        }

        // Hava etiketi seçildiyse indirim oranı 1-90 arasında olmalı
        val weather = selectedWeather
        val discount = etWeatherDiscount.text.toString().trim().toIntOrNull() ?: 0
        if (weather != null && discount !in 1..90) {
            etWeatherDiscount.error = "İndirim oranı 1 ile 90 arasında olmalı"
            return
        }

        val productMap = hashMapOf<String, Any?>(
            "name" to name,
            "description" to description,
            "price" to price,
            "stock" to stock,
            "onSale" to switchOnSale.isChecked, // Satışta değilse kullanıcılar ürünü görmez
            "categories" to selectedCategories.toList(), // Seçilen mevcut kategoriler listesi
            "imageUrl" to imageUrl,
            "weatherTag" to weather?.code,
            "weatherDiscount" to if (weather != null) discount else 0,
            "updatedAt" to System.currentTimeMillis()
        )

        btnSave.isEnabled = false
        val task = if (isEditMode) {
            db.collection("products").document(productId!!).update(productMap)
        } else {
            productMap["createdAt"] = System.currentTimeMillis()
            db.collection("products").add(productMap)
        }

        task.addOnSuccessListener {
            if (!isAdded) return@addOnSuccessListener
            btnSave.isEnabled = true
            if (isEditMode) {
                Toast.makeText(requireContext(), "Ürün güncellendi!", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                Toast.makeText(requireContext(), "Ürün başarıyla eklendi!", Toast.LENGTH_SHORT).show()
                clearForm()
            }
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            btnSave.isEnabled = true
            Toast.makeText(requireContext(), "Hata: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    // Düzenleme modunda ürünün mevcut bilgileri forma doldurulur
    private fun loadProduct() {
        db.collection("products").document(productId!!).get()
            .addOnSuccessListener { doc ->
                if (!isAdded) return@addOnSuccessListener
                if (!doc.exists()) {
                    Toast.makeText(requireContext(), "Ürün bulunamadı", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                    return@addOnSuccessListener
                }
                fillForm(doc)
            }
    }

    private fun fillForm(doc: DocumentSnapshot) {
        etName.setText(doc.getString("name"))
        etDescription.setText(doc.getString("description"))
        etPrice.setText(doc.getDouble("price")?.toString())
        etStock.setText(doc.getLong("stock")?.toString())
        etImageUrl.setText(doc.getString("imageUrl"))
        switchOnSale.isChecked = doc.getBoolean("onSale") ?: true
        updateImagePreview()

        @Suppress("UNCHECKED_CAST")
        (doc.get("categories") as? List<String>)?.forEach { category ->
            if (!selectedCategories.contains(category)) {
                selectedCategories.add(category)
                addCategoryChip(category)
            }
        }

        val weather = WeatherCondition.fromCode(doc.getString("weatherTag"))
        selectWeather(weather)
        if (weather != null) etWeatherDiscount.setText(doc.getLong("weatherDiscount")?.toString())
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle("Ürünü Sil")
            .setMessage("${etName.text} kalıcı olarak silinecek. Emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                db.collection("products").document(productId!!).delete()
                    .addOnSuccessListener {
                        if (!isAdded) return@addOnSuccessListener
                        Toast.makeText(requireContext(), "Ürün silindi", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    }
                    .addOnFailureListener { e ->
                        if (isAdded) Toast.makeText(requireContext(), "Silinemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun clearForm() {
        etName.text?.clear()
        etDescription.text?.clear()
        etPrice.text?.clear()
        etStock.text?.clear()
        etImageUrl.text?.clear()
        updateImagePreview()
        switchOnSale.isChecked = true
        actvCategory.text?.clear()
        selectedCategories.clear()
        chipGroup.removeAllViews()
        etWeatherDiscount.text?.clear()
        selectWeather(null)
    }

    private fun updateImagePreview() {
        val url = etImageUrl.text.toString().trim()
        if (url.isEmpty()) {
            ivPreview.visibility = View.GONE
            btnRemoveImage.visibility = View.GONE
        } else {
            ivPreview.visibility = View.VISIBLE
            btnRemoveImage.visibility = View.VISIBLE
            Glide.with(this).load(url).placeholder(R.drawable.ic_launcher_foreground).into(ivPreview)
        }
    }

    private fun setupWeatherTag() {
        val options = listOf(NO_WEATHER) + WeatherCondition.entries.map { weatherLabel(it) }
        actvWeatherTag.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, options))
        selectWeather(null)

        actvWeatherTag.setOnItemClickListener { _, _, position, _ ->
            // 0. seçenek "Yok"; diğerleri enum sırasıyla eşleşir
            selectWeather(if (position == 0) null else WeatherCondition.entries[position - 1])
        }
    }

    private fun selectWeather(weather: WeatherCondition?) {
        selectedWeather = weather
        actvWeatherTag.setText(weather?.let { weatherLabel(it) } ?: NO_WEATHER, false)
        layoutWeatherDiscount.visibility = if (weather != null) View.VISIBLE else View.GONE
    }

    private fun weatherLabel(weather: WeatherCondition) = "${weather.emoji} ${weather.label}"

    private fun loadPredefinedCategories() {
        // Doğrudan 'categories' koleksiyonundan önceden tanımlı verileri alıyoruz
        db.collection("categories").get().addOnSuccessListener { result ->
            if (!isAdded) return@addOnSuccessListener
            val categoryList = mutableListOf<String>()
            for (doc in result.documents) {
                val name = doc.getString("name")
                if (!name.isNullOrEmpty()) {
                    categoryList.add(name)
                }
            }

            // AutoCompleteTextView ile arama ve kaydırma özelliği
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categoryList)
            actvCategory.setAdapter(adapter)

            actvCategory.setOnItemClickListener { _, _, position, _ ->
                val selectedCategory = adapter.getItem(position) ?: return@setOnItemClickListener

                if (!selectedCategories.contains(selectedCategory)) {
                    selectedCategories.add(selectedCategory)
                    addCategoryChip(selectedCategory)
                }

                actvCategory.text?.clear()
            }
        }
    }

    private fun addCategoryChip(categoryName: String) {
        val chip = Chip(requireContext()).apply {
            text = categoryName
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                chipGroup.removeView(this)
                selectedCategories.remove(categoryName)
            }
        }
        chipGroup.addView(chip)
    }

    companion object {
        private const val NO_WEATHER = "Yok (indirim uygulanmaz)"
        private const val ARG_PRODUCT_ID = "productId"

        // Düzenleme modu için: AdminAddProductFragment.newInstance(urunId)
        fun newInstance(productId: String?) = AdminAddProductFragment().apply {
            arguments = Bundle().apply { putString(ARG_PRODUCT_ID, productId) }
        }
    }
}
