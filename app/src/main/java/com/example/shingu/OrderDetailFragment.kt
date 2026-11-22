package com.example.shingu

import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sipariş detayı: durum geçmişi, ürünler, tutar dökümü (ara toplam, kupon, kargo), ödeme, adres ve iptal.
// Hem kullanıcı (Siparişlerim) hem admin (sipariş yönetimi) bu ekranı kullanır.
class OrderDetailFragment : Fragment() {

    private val orderId: String get() = requireArguments().getString(ARG_ORDER_ID)!!
    private var orderListener: ListenerRegistration? = null
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_order_detail, container, false)

        // Sipariş canlı dinlenir: admin durumu değiştirince detay da güncellenir
        orderListener = OrderRepository.listenOrder(orderId,
            onChange = { order ->
                if (!isAdded) return@listenOrder
                if (order == null) {
                    Toast.makeText(requireContext(), "Sipariş bulunamadı", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    showOrder(view, order)
                }
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Sipariş yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
        return view
    }

    private fun showOrder(view: View, order: Order) {
        view.findViewById<TextView>(R.id.tvDetailOrderNo).text = "Sipariş #${order.id.take(6).uppercase(Locale("tr", "TR"))}"
        view.findViewById<TextView>(R.id.tvDetailOrderMeta).text =
            dateFormat.format(Date(order.createdAt)) + if (UserSession.isAdmin) "  ·  ${order.userEmail}" else ""
        view.findViewById<TextView>(R.id.tvDetailStatus).apply {
            text = order.status.label + if (order.cancelledBy != null) " (${if (order.cancelledBy == "admin") "mağaza tarafından" else "sizin tarafınızdan"})" else ""
            setTextColor(order.status.color())
        }

        showTimeline(view.findViewById(R.id.layoutDetailTimeline), order)
        showItems(view.findViewById(R.id.layoutDetailItems), order)

        // Tutar dökümü; kupon/kargo özelliğinden önceki siparişlerde ara toplam yoktur, sadece toplam gösterilir
        val lines = mutableListOf<String>()
        order.subtotal?.let { lines += "Ara toplam: ${OrderPricing.format(it)}" }
        order.couponCode?.let { lines += "Kupon ($it): -${OrderPricing.format(order.couponDiscount)}" }
        if (order.subtotal != null) lines += "Kargo: " + if (order.shippingFee == 0.0) "Bedava" else OrderPricing.format(order.shippingFee)
        order.payment?.let { lines += "Ödeme: 💳 $it" }
        view.findViewById<TextView>(R.id.tvDetailSummary).text = lines.joinToString("\n").ifEmpty { "—" }
        view.findViewById<TextView>(R.id.tvDetailTotal).text = "Toplam: ${OrderPricing.format(order.totalPrice)}"
        view.findViewById<TextView>(R.id.tvDetailAddress).text = order.address ?: "Adres bilgisi yok"

        // İptal butonu: kullanıcı sadece "Beklemede", admin teslim edilmemiş her siparişi iptal edebilir
        val canCancel = if (UserSession.isAdmin) order.canAdminCancel else order.canUserCancel
        val btnCancel = view.findViewById<Button>(R.id.btnDetailCancel)
        val tvCancelInfo = view.findViewById<TextView>(R.id.tvDetailCancelInfo)
        btnCancel.visibility = if (canCancel) View.VISIBLE else View.GONE
        btnCancel.setOnClickListener { confirmCancelOrder(order, byAdmin = UserSession.isAdmin) }
        tvCancelInfo.visibility = View.VISIBLE
        tvCancelInfo.text = when {
            canCancel -> "İptal edilirse ürünler stoğa geri eklenir; ödeme iadesi test modunda simüle edilir."
            order.status == OrderStatus.CANCELLED -> "Bu sipariş iptal edildi."
            !UserSession.isAdmin -> "Sipariş işleme alındığı için uygulamadan iptal edilemez."
            else -> ""
        }
    }

    private fun showTimeline(container: LinearLayout, order: Order) {
        container.removeAllViews()
        // Eski siparişlerde geçmiş yoksa en azından oluşturulma ve şu anki durum gösterilir
        val history = order.statusHistory.ifEmpty { listOf(StatusChange(order.status, order.createdAt)) }
        history.sortedBy { it.at }.forEach { change ->
            container.addView(TextView(requireContext()).apply {
                text = "●  ${change.status.label}  —  ${dateFormat.format(Date(change.at))}"
                setTextColor(change.status.color())
                textSize = 14f
                setPadding(0, 4, 0, 4)
            })
        }
    }

    private fun showItems(container: LinearLayout, order: Order) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        order.items.forEach { item ->
            val quantity = (item["quantity"] as? Number)?.toLong() ?: 0L
            val unitPrice = (item["price"] as? Number)?.toDouble() ?: 0.0
            val discount = (item["weatherDiscount"] as? Number)?.toInt() ?: 0

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val image = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Glide.with(this).load(item["imageUrl"] as? String).placeholder(R.drawable.ic_placeholder_sakura).into(image)
            val text = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).toInt()
                }
                this.text = "${item["name"]}\n$quantity x ${OrderPricing.format(unitPrice)}" +
                    if (discount > 0) "  (hava indirimi %$discount)" else ""
                setTextColor(Color.parseColor("#4A3550"))
                textSize = 14f
            }
            val lineTotal = TextView(requireContext()).apply {
                this.text = OrderPricing.format(unitPrice * quantity)
                setTextColor(Color.parseColor("#4A3550"))
                textSize = 14f
            }
            row.addView(image)
            row.addView(text)
            row.addView(lineTotal)
            container.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orderListener?.remove()
        orderListener = null
    }

    companion object {
        private const val ARG_ORDER_ID = "orderId"

        fun newInstance(orderId: String) = OrderDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ORDER_ID, orderId) }
        }
    }
}

// Sipariş iptali onayı (sipariş detayı ve admin sipariş listesi ortak kullanır)
fun Fragment.confirmCancelOrder(order: Order, byAdmin: Boolean) {
    AlertDialog.Builder(requireContext())
        .setTitle("Siparişi İptal Et")
        .setMessage(
            "Sipariş #${order.id.take(6).uppercase(Locale("tr", "TR"))} iptal edilecek.\n\n" +
                "Ürünler stoğa geri eklenir. Bu işlem geri alınamaz."
        )
        .setPositiveButton("İptal Et") { _, _ ->
            OrderRepository.cancelOrder(order.id, byAdmin,
                onSuccess = { if (isAdded) Toast.makeText(requireContext(), "Sipariş iptal edildi", Toast.LENGTH_SHORT).show() },
                onFailure = { e ->
                    if (isAdded) Toast.makeText(requireContext(), "İptal edilemedi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            )
        }
        .setNegativeButton("Vazgeç", null)
        .show()
}
