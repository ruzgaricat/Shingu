package com.example.shingu

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Admin kupon yönetimi: kuponları listeler; yeni kupon ekler, düzenler, siler.
// Kullanıcı ekranında (Kuponlarım) aynı satır tasarımı ve CouponAdapter kullanılır.
class AdminCouponsFragment : Fragment() {

    private var couponsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        view.findViewById<TextView>(R.id.tvAdminListTitle).text = "Yönetici: Kuponlar"
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Henüz kupon yok"
        view.findViewById<Button>(R.id.btnNewProduct).apply {
            text = "+ Yeni Kupon"
            setOnClickListener { showCouponForm(null) }
        }

        val adapter = CouponAdapter(showAdminInfo = true) { coupon -> showCouponActions(coupon) }
        view.findViewById<RecyclerView>(R.id.recyclerAdminProducts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        couponsListener = CouponRepository.listenCoupons(onlyActive = false,
            onChange = { coupons ->
                if (!isAdded) return@listenCoupons
                adapter.submitList(coupons)
                tvEmpty.visibility = if (coupons.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e -> toast("Kuponlar yüklenemedi: ${e.localizedMessage}") }
        )
        return view
    }

    private fun showCouponActions(coupon: Coupon) {
        showActionDialog(
            coupon.code,
            listOf(
                DialogAction("Düzenle") { showCouponForm(coupon) },
                DialogAction(if (coupon.active) "Pasif Yap" else "Aktif Yap") {
                    CouponRepository.saveCoupon(coupon.copy(active = !coupon.active))
                        .addOnFailureListener { e -> toast("Güncellenemedi: ${e.localizedMessage}") }
                },
                DialogAction("Sil", destructive = true) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Kuponu Sil")
                        .setMessage("${coupon.code} kuponu silinecek. Daha önce bu kuponla verilmiş siparişler etkilenmez.")
                        .setPositiveButton("Sil") { _, _ ->
                            CouponRepository.deleteCoupon(coupon.code)
                                .addOnFailureListener { e -> toast("Silinemedi: ${e.localizedMessage}") }
                        }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            )
        )
    }

    // Kupon formu; existing null ise yeni kupon. Kod, kuponun belge kimliği olduğu için düzenlemede değiştirilemez.
    private fun showCouponForm(existing: Coupon?) {
        val form = layoutInflater.inflate(R.layout.dialog_coupon_form, null)
        val etCode = form.findViewById<TextInputEditText>(R.id.etCouponFormCode)
        val etDescription = form.findViewById<TextInputEditText>(R.id.etCouponFormDescription)
        val rbPercent = form.findViewById<RadioButton>(R.id.rbCouponPercent)
        val rbAmount = form.findViewById<RadioButton>(R.id.rbCouponAmount)
        val etValue = form.findViewById<TextInputEditText>(R.id.etCouponFormValue)
        val etMin = form.findViewById<TextInputEditText>(R.id.etCouponFormMin)
        val etDays = form.findViewById<TextInputEditText>(R.id.etCouponFormDays)
        val switchActive = form.findViewById<MaterialSwitch>(R.id.switchCouponActive)

        existing?.let { c ->
            etCode.setText(c.code)
            etCode.isEnabled = false
            etDescription.setText(c.description)
            rbPercent.isChecked = c.isPercent
            rbAmount.isChecked = !c.isPercent
            etValue.setText(formatNumber(c.value))
            etMin.setText(formatNumber(c.minCartTotal))
            c.expiresAt?.let { expires ->
                val days = TimeUnit.MILLISECONDS.toDays(expires - System.currentTimeMillis()).coerceAtLeast(0)
                etDays.setText(days.toString())
            }
            switchActive.isChecked = c.active
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Yeni Kupon" else "Kuponu Düzenle")
            .setView(form)
            .setPositiveButton("Kaydet", null) // Hatalı girişte pencere kapanmasın diye aşağıda bağlanıyor
            .setNegativeButton("Vazgeç", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = Coupon.normalize(etCode.text.toString())
                val value = etValue.text.toString().replace(',', '.').toDoubleOrNull()
                val minTotal = etMin.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val days = etDays.text.toString().toLongOrNull()
                val isPercent = rbPercent.isChecked

                if (!code.matches(Regex("^[A-Z0-9ÇĞİÖŞÜ]{3,20}$"))) {
                    etCode.error = "3-20 karakter; sadece harf ve rakam"
                    return@setOnClickListener
                }
                if (value == null || value <= 0 || (isPercent && value > 90)) {
                    etValue.error = if (isPercent) "1 ile 90 arasında bir yüzde girin" else "Geçerli bir tutar girin"
                    return@setOnClickListener
                }

                val coupon = Coupon(
                    code = code,
                    description = etDescription.text.toString().trim(),
                    type = if (isPercent) Coupon.TYPE_PERCENT else Coupon.TYPE_AMOUNT,
                    value = value,
                    minCartTotal = minTotal,
                    active = switchActive.isChecked,
                    expiresAt = days?.let { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it) }
                )

                // Yeni kuponda aynı kod varsa üzerine yazılmaz
                val existsCheck = if (existing == null) CouponRepository.getCoupon(code) else null
                if (existsCheck == null) {
                    saveAndClose(coupon, dialog)
                } else {
                    existsCheck.addOnSuccessListener { found ->
                        if (found != null) etCode.error = "Bu kod zaten kullanılıyor" else saveAndClose(coupon, dialog)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun saveAndClose(coupon: Coupon, dialog: AlertDialog) {
        CouponRepository.saveCoupon(coupon)
            .addOnSuccessListener {
                dialog.dismiss()
                toast("${coupon.code} kaydedildi")
            }
            .addOnFailureListener { e -> toast("Kaydedilemedi: ${e.localizedMessage}") }
    }

    private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        couponsListener?.remove()
        couponsListener = null
    }
}

// Kupon satırı (admin listesi ve Kuponlarım ekranı). Ürün yönetimindeki satır tasarımı yeniden kullanılır.
class CouponAdapter(
    private val showAdminInfo: Boolean,
    private val onClick: (Coupon) -> Unit
) : RecyclerView.Adapter<CouponAdapter.ViewHolder>() {

    private var coupons: List<Coupon> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvCode: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvLabel: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvInfo: TextView = view.findViewById(R.id.tvAdminProductStatus)
        val tvAction: TextView = view.findViewById(R.id.tvAdminRowAction)
    }

    fun submitList(newCoupons: List<Coupon>) {
        coupons = newCoupons
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val coupon = coupons[position]
        holder.ivIcon.setImageResource(R.drawable.ic_receipt_24)
        holder.tvAction.text = if (showAdminInfo) "Düzenle >" else "Kopyala"
        holder.tvCode.text = coupon.code
        holder.tvLabel.text = coupon.label + if (coupon.description.isNotBlank()) " · ${coupon.description}" else ""

        val conditions = mutableListOf<String>()
        if (coupon.minCartTotal > 0) conditions += "En az ${OrderPricing.format(coupon.minCartTotal)}"
        conditions += coupon.expiresAt?.let { "Son gün: ${dateFormat.format(Date(it))}" } ?: "Süresiz"
        val expired = coupon.expiresAt != null && coupon.expiresAt < System.currentTimeMillis()
        if (showAdminInfo) conditions += when {
            expired -> "Süresi doldu"
            coupon.active -> "Aktif"
            else -> "Pasif"
        }
        holder.tvInfo.visibility = View.VISIBLE
        holder.tvInfo.text = conditions.joinToString("  ·  ")
        holder.tvInfo.setTextColor(Color.parseColor(if (showAdminInfo && (!coupon.active || expired)) "#757575" else "#388E3C"))

        holder.itemView.setOnClickListener { onClick(coupon) }
    }

    override fun getItemCount() = coupons.size
}
