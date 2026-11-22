package com.example.shingu

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

// Admin kampanya formu: argüman olarak kampanya kimliği verilirse "düzenle", verilmezse "yeni" modunda açılır.
// Kampanya oluşturulunca aynı adla bir kategori açılır; silinince o kategori de silinir.
class AdminCampaignFormFragment : Fragment() {

    private lateinit var etTitle: TextInputEditText
    private lateinit var etImageUrl: TextInputEditText
    private lateinit var btnSave: Button
    private lateinit var preview: CampaignBannerAdapter.ViewHolder

    private val campaignId: String? get() = arguments?.getString(ARG_CAMPAIGN_ID)
    private val isEditMode get() = campaignId != null
    private var campaign: Campaign? = null

    // "Galeriden Seç": yüklenen fotoğrafın bağlantısı URL alanına yazılır ve önizleme güncellenir
    private val imagePicker = ImagePickerHelper(this, "shingu/campaigns") { url ->
        etImageUrl.setText(url)
        updatePreview()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_campaign_form, container, false)

        etTitle = view.findViewById(R.id.etCampaignTitle)
        etImageUrl = view.findViewById(R.id.etCampaignImageUrl)
        btnSave = view.findViewById(R.id.btnSaveCampaign)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteCampaign)

        // Banner'ın canlı önizlemesi: ana sayfadaki slaytla aynı tasarım
        val previewContainer = view.findViewById<FrameLayout>(R.id.layoutBannerPreview)
        val previewView = inflater.inflate(R.layout.item_campaign_banner, previewContainer, false)
        previewContainer.addView(previewView)
        preview = CampaignBannerAdapter.ViewHolder(previewView)
        updatePreview()
        etTitle.doAfterTextChanged { updatePreview() }
        etImageUrl.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updatePreview() }
        imagePicker.attach(view.findViewById(R.id.btnPickCampaignImage))

        if (isEditMode) {
            view.findViewById<TextView>(R.id.tvCampaignFormTitle).text = "Yönetici: Kampanyayı Düzenle"
            btnSave.text = "Değişiklikleri Kaydet"
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { confirmDelete() }
            loadCampaign()
        }

        btnSave.setOnClickListener { saveCampaign() }

        return view
    }

    private fun updatePreview() {
        val title = etTitle.text.toString().trim().ifEmpty { "Kampanya Adı" }
        preview.bind(title, etImageUrl.text.toString().trim())
    }

    private fun loadCampaign() {
        CampaignRepository.getCampaign(campaignId!!).addOnSuccessListener { loaded ->
            if (!isAdded) return@addOnSuccessListener
            if (loaded == null) {
                Toast.makeText(requireContext(), "Kampanya bulunamadı", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                return@addOnSuccessListener
            }
            campaign = loaded
            etTitle.setText(loaded.title)
            etImageUrl.setText(loaded.imageUrl)
            updatePreview()
        }
    }

    private fun saveCampaign() {
        val title = etTitle.text.toString().trim()
        val imageUrl = etImageUrl.text.toString().trim()
        if (title.length < 2) {
            etTitle.error = "Kampanya adı en az 2 karakter olmalı"
            return
        }
        if (!imageUrl.startsWith("http")) {
            etImageUrl.error = "Geçerli bir fotoğraf bağlantısı girin (http ile başlamalı)"
            return
        }

        val existing = campaign
        if (isEditMode && existing == null) return // Kampanya henüz yüklenmedi

        btnSave.isEnabled = false
        val task = if (existing != null) {
            CampaignRepository.update(existing, title, imageUrl)
        } else {
            CampaignRepository.create(title, imageUrl)
        }
        task.addOnSuccessListener {
            if (!isAdded) return@addOnSuccessListener
            val message = if (existing != null) "Kampanya güncellendi" else "Kampanya ve \"$title\" kategorisi oluşturuldu"
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            btnSave.isEnabled = true
            Toast.makeText(requireContext(), e.localizedMessage ?: "Hata oluştu", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val campaign = campaign ?: return
        CategoryRepository.productCount(campaign.categoryName).addOnSuccessListener { count ->
            if (!isAdded) return@addOnSuccessListener
            val productNote = if (count > 0) " Bu kategorideki $count ürün silinmez, sadece kategoriden çıkarılır." else ""
            AlertDialog.Builder(requireContext())
                .setTitle("Kampanyayı Sil")
                .setMessage("\"${campaign.title}\" kampanyası ve aynı adlı kategori silinecek.$productNote")
                .setPositiveButton("Sil") { _, _ ->
                    CampaignRepository.delete(campaign)
                        .addOnSuccessListener {
                            if (!isAdded) return@addOnSuccessListener
                            Toast.makeText(requireContext(), "Kampanya ve kategorisi silindi", Toast.LENGTH_SHORT).show()
                            parentFragmentManager.popBackStack()
                        }
                        .addOnFailureListener { e ->
                            if (isAdded) Toast.makeText(requireContext(), "Silinemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    companion object {
        private const val ARG_CAMPAIGN_ID = "campaignId"

        fun newInstance(campaignId: String?) = AdminCampaignFormFragment().apply {
            arguments = Bundle().apply { putString(ARG_CAMPAIGN_ID, campaignId) }
        }
    }
}
