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
import android.widget.LinearLayout
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
    private lateinit var layoutImages: LinearLayout

    // Ürün görselleri (sıralı); ilki kapak görselidir ve eski sürümle uyum için "imageUrl" alanına da yazılır
    private val imageUrls = mutableListOf<String>()
    private lateinit var switchOnSale: MaterialSwitch
    private lateinit var actvWeatherTag: AutoCompleteTextView
    private lateinit var layoutWeatherDiscount: TextInputLayout
    private lateinit var etWeatherDiscount: TextInputEditText
    private lateinit var btnSave: Button

    // Seçilen hava durumu etiketi (null = indirim yok)
    private var selectedWeather: WeatherCondition? = null

    // "Galeriden Seç": yüklenen görsel listeye eklenir
    private val imagePicker = ImagePickerHelper(this, "shingu/products") { url -> addImage(url) }

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
        layoutImages = view.findViewById(R.id.layoutProductImages)
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

        // Görseller: URL ile "Ekle" veya galeriden yükleme; küçük resme dokununca kapak yap/sil seçenekleri
        imagePicker.attach(view.findViewById(R.id.btnPickProductImage))
        view.findViewById<Button>(R.id.btnAddImageUrl).setOnClickListener {
            val url = etImageUrl.text.toString().trim()
            if (!url.startsWith("http")) {
                etImageUrl.error = "Geçerli bir görsel bağlantısı girin (http ile başlamalı)"
                return@setOnClickListener
            }
            addImage(url)
            etImageUrl.text?.clear()
        }
        renderImages()

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
        // URL alanına yazılıp "Ekle"ye basılmamış bir bağlantı varsa o da eklenir
        etImageUrl.text.toString().trim().takeIf { it.startsWith("http") }?.let {
            addImage(it)
            etImageUrl.text?.clear()
        }

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
            "imageUrls" to imageUrls.toList(),
            "imageUrl" to (imageUrls.firstOrNull() ?: ""), // Kapak görseli (sepet, kampanya vb. tek görsel kullanan yerler için)
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
        switchOnSale.isChecked = doc.getBoolean("onSale") ?: true
        imageUrls.clear()
        imageUrls.addAll(Product.fromDocument(doc).imageUrls)
        renderImages()

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
        imageUrls.clear()
        renderImages()
        switchOnSale.isChecked = true
        actvCategory.text?.clear()
        selectedCategories.clear()
        chipGroup.removeAllViews()
        etWeatherDiscount.text?.clear()
        selectWeather(null)
    }

    private fun addImage(url: String) {
        if (url in imageUrls) return
        if (imageUrls.size >= MAX_IMAGES) {
            Toast.makeText(requireContext(), "En fazla $MAX_IMAGES görsel eklenebilir", Toast.LENGTH_SHORT).show()
            return
        }
        imageUrls.add(url)
        renderImages()
    }

    // Küçük resimler: ilkinin altında "Kapak" yazar; dokununca kapak yap / sil seçenekleri çıkar
    private fun renderImages() {
        layoutImages.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (88 * density).toInt()
        imageUrls.forEachIndexed { index, url ->
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener { showImageOptions(index) }
            }
            val image = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this).load(url).placeholder(R.drawable.ic_launcher_foreground).into(image)
            val label = TextView(requireContext()).apply {
                text = if (index == 0) "Kapak" else "${index + 1}. görsel"
                textSize = 11f
                gravity = android.view.Gravity.CENTER
            }
            cell.addView(image)
            cell.addView(label)
            layoutImages.addView(cell)
        }
        view?.findViewById<TextView>(R.id.tvProductImagesTitle)?.text =
            if (imageUrls.isEmpty()) "Ürün Fotoğrafları (henüz eklenmedi)" else "Ürün Fotoğrafları (${imageUrls.size}/$MAX_IMAGES, ilki kapak)"
    }

    private fun showImageOptions(index: Int) {
        val options = if (index == 0) arrayOf("Görseli Sil") else arrayOf("Kapak Görseli Yap", "Görseli Sil")
        AlertDialog.Builder(requireContext())
            .setTitle("${index + 1}. görsel")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Kapak Görseli Yap" -> imageUrls.add(0, imageUrls.removeAt(index))
                    "Görseli Sil" -> imageUrls.removeAt(index)
                }
                renderImages()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
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
        private const val MAX_IMAGES = 6
        private const val ARG_PRODUCT_ID = "productId"

        // Düzenleme modu için: AdminAddProductFragment.newInstance(urunId)
        fun newInstance(productId: String?) = AdminAddProductFragment().apply {
            arguments = Bundle().apply { putString(ARG_PRODUCT_ID, productId) }
        }
    }
}
