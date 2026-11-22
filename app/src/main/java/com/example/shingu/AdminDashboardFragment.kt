package com.example.shingu

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

// Admin satış özeti: siparişlerden ciro, sipariş durumları ve en çok satan ürünler hesaplanır.
// Siparişler canlı dinlendiği için yeni sipariş gelince ekran kendiliğinden güncellenir.
class AdminDashboardFragment : Fragment() {

    private var ordersListener: ListenerRegistration? = null
    private val locale = TR_LOCALE

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_dashboard, container, false)

        view.findViewById<Button>(R.id.btnOpenFeedback).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AdminFeedbackFragment())
                .addToBackStack(null)
                .commit()
        }

        ordersListener = OrderRepository.listenAllOrders(
            onChange = { orders -> if (isAdded) showStats(view, orders) },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Siparişler yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        // Müşteri sayısı: admin olmayan, silinmemiş kullanıcılar
        FirebaseFirestore.getInstance().collection("users").get().addOnSuccessListener { result ->
            if (!isAdded) return@addOnSuccessListener
            val customers = result.documents.count { it.getString("role") != "admin" && it.getString("status") != "deleted" }
            setStat(view.findViewById(R.id.tvStatCustomers), "Müşteri", customers.toString())
        }
        return view
    }

    private fun showStats(view: View, orders: List<Order>) {
        val valid = orders.filter { it.status != OrderStatus.CANCELLED }
        val todayStart = startOfDay(0)
        val weekStart = startOfDay(-6)

        val todayRevenue = valid.filter { it.createdAt >= todayStart }.sumOf { it.totalPrice }
        val weekOrders = valid.filter { it.createdAt >= weekStart }
        setStat(view.findViewById(R.id.tvStatToday), "Bugünkü ciro", OrderPricing.format(todayRevenue))
        setStat(view.findViewById(R.id.tvStatWeek), "Son 7 gün (${weekOrders.size} sipariş)", OrderPricing.format(weekOrders.sumOf { it.totalPrice }))
        setStat(view.findViewById(R.id.tvStatPending), "Bekleyen sipariş", orders.count { it.status == OrderStatus.PENDING }.toString())

        showDailyBars(view.findViewById(R.id.layoutDailyBars), valid)

        view.findViewById<TextView>(R.id.tvStatusCounts).text = OrderStatus.entries
            .joinToString("\n") { status -> "${status.label}: ${orders.count { it.status == status }}" }

        // En çok satan 5 ürün (adet bazında, iptaller hariç)
        val topProducts = valid.flatMap { it.items }
            .groupBy { it["name"] as? String ?: "?" }
            .mapValues { (_, items) -> items.sumOf { (it["quantity"] as? Number)?.toLong() ?: 0L } }
            .entries.sortedByDescending { it.value }
            .take(5)
        view.findViewById<TextView>(R.id.tvTopProducts).text =
            if (topProducts.isEmpty()) "Henüz satış yok"
            else topProducts.mapIndexed { i, (name, qty) -> "${i + 1}. $name — $qty adet" }.joinToString("\n")
    }

    // Son 7 gün için her güne bir satır: gün adı, ciroyla orantılı çubuk ve tutar
    private fun showDailyBars(container: LinearLayout, orders: List<Order>) {
        container.removeAllViews()
        val dayFormat = SimpleDateFormat("EEE dd.MM", locale)
        val days = (-6..0).map { offset ->
            val start = startOfDay(offset)
            val end = startOfDay(offset + 1)
            start to orders.filter { it.createdAt in start until end }.sumOf { it.totalPrice }
        }
        val maxRevenue = days.maxOf { it.second }.coerceAtLeast(1.0)
        val density = resources.displayMetrics.density

        days.forEach { (dayStart, revenue) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            row.addView(TextView(requireContext()).apply {
                text = dayFormat.format(Date(dayStart))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams((78 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            // Çubuk + boşluk, ağırlıkları ciroya göre bölünür
            val barArea = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, (14 * density).toInt(), 1f)
                weightSum = 1f
            }
            val ratio = (revenue / maxRevenue).toFloat()
            barArea.addView(View(requireContext()).apply {
                setBackgroundColor(Color.parseColor("#E5508A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio.coerceAtLeast(0.01f))
            })
            row.addView(barArea)
            row.addView(TextView(requireContext()).apply {
                text = String.format(locale, "%,.0f TL", revenue)
                textSize = 12f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((80 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            container.addView(row)
        }
    }

    // Kart metni: üstte küçük başlık, altta büyük kalın değer
    private fun setStat(textView: TextView, title: String, value: String) {
        textView.text = SpannableStringBuilder().apply {
            append("$title\n")
            val start = length
            append(value)
            setSpan(StyleSpan(Typeface.BOLD), start, length, 0)
            setSpan(RelativeSizeSpan(1.5f), start, length, 0)
        }
    }

    // Bugünden "offset" gün sonraki günün başlangıcı (00:00)
    private fun startOfDay(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    override fun onDestroyView() {
        super.onDestroyView()
        ordersListener?.remove()
        ordersListener = null
    }
}
