package com.example.shingu

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale

// Ödeme ekranı (TEST MODU): ödeme yöntemi sadece online kart. Kart bilgileri doğrulanır ve kısa bir
// "banka onayı" beklemesi taklit edilir; gerçek ödeme alınmaz. Onaylanınca sipariş verilir ve siparişe
// kartın sadece türü ve son 4 hanesi yazılır (kart numarasının tamamı ve CVV hiçbir yere kaydedilmez).
class PaymentFragment : Fragment() {

    private var cartListener: ListenerRegistration? = null
    private var items: List<CartItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var address: Address
    private lateinit var tvItems: TextView
    private lateinit var tvTotal: TextView
    private lateinit var etHolder: TextInputEditText
    private lateinit var etNumber: TextInputEditText
    private lateinit var layoutNumber: TextInputLayout
    private lateinit var etExpiry: TextInputEditText
    private lateinit var etCvv: TextInputEditText
    private lateinit var btnPay: Button
    private lateinit var progress: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_payment, container, false)
        address = addressFromArgs(requireArguments())

        tvItems = view.findViewById(R.id.tvPaymentItems)
        tvTotal = view.findViewById(R.id.tvPaymentTotal)
        etHolder = view.findViewById(R.id.etCardHolder)
        etNumber = view.findViewById(R.id.etCardNumber)
        layoutNumber = view.findViewById(R.id.layoutCardNumber)
        etExpiry = view.findViewById(R.id.etCardExpiry)
        etCvv = view.findViewById(R.id.etCardCvv)
        btnPay = view.findViewById(R.id.btnPay)
        progress = view.findViewById(R.id.progressPayment)

        view.findViewById<TextView>(R.id.tvPaymentAddress).text = "📍 Teslimat: ${address.title} - ${address.oneLine}"

        setupCardNumberFormatting()
        setupExpiryFormatting()
        btnPay.setOnClickListener { pay() }

        // Sepet canlı dinlenir: tutar her zaman güncel sepetten hesaplanır
        cartListener = CartRepository.listenCart(
            onChange = { cartItems ->
                if (!isAdded) return@listenCart
                items = cartItems
                val total = cartItems.sumOf { it.lineTotal }
                tvItems.text = "🛒 ${cartItems.size} çeşit ürün, ${cartItems.sumOf { it.quantity }} adet"
                tvTotal.text = "Toplam: ${formatPrice(total)}"
                btnPay.text = "${formatPrice(total)} Öde"
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Sepet yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    // Kart numarası yazılırken 4'erli gruplanır ve kart türü (Visa, Mastercard, Troy, Amex) gösterilir
    private fun setupCardNumberFormatting() {
        etNumber.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable) {
                val digits = CardValidator.digitsOnly(s.toString()).take(19)
                val formatted = CardValidator.formatNumber(digits)
                if (formatted != s.toString()) {
                    etNumber.removeTextChangedListener(this)
                    etNumber.setText(formatted)
                    etNumber.setSelection(formatted.length)
                    etNumber.addTextChangedListener(this)
                }
                layoutNumber.helperText = if (digits.isEmpty()) "Kart türü numaradan otomatik bulunur"
                else "Kart türü: ${CardBrand.detect(digits).label}"
            }
        })
    }

    // Son kullanma tarihi yazılırken ay ile yılın arasına "/" eklenir (1225 -> 12/25)
    private fun setupExpiryFormatting() {
        etExpiry.addTextChangedListener(object : SimpleWatcher() {
            override fun afterTextChanged(s: Editable) {
                val digits = CardValidator.digitsOnly(s.toString()).take(4)
                val formatted = if (digits.length > 2) "${digits.take(2)}/${digits.drop(2)}" else digits
                if (formatted != s.toString()) {
                    etExpiry.removeTextChangedListener(this)
                    etExpiry.setText(formatted)
                    etExpiry.setSelection(formatted.length)
                    etExpiry.addTextChangedListener(this)
                }
            }
        })
    }

    private fun pay() {
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), "Sepetiniz boş", Toast.LENGTH_SHORT).show()
            return
        }

        // 1) Alan kontrolleri
        val holder = etHolder.text.toString().trim()
        val digits = CardValidator.digitsOnly(etNumber.text.toString())
        val brand = CardBrand.detect(digits)
        val cvv = etCvv.text.toString()
        var valid = true
        fun check(field: EditText, error: String?) {
            field.error = error
            if (error != null) valid = false
        }
        check(etHolder, if (holder.length < 3 || holder.any { it.isDigit() }) "Kart üzerindeki ismi girin" else null)
        check(etNumber, CardValidator.numberError(digits))
        check(etExpiry, CardValidator.expiryError(etExpiry.text.toString()))
        check(etCvv, CardValidator.cvvError(cvv, brand))
        if (!valid) return

        // 2) Banka onayı taklidi (test modu): kısa bir bekleme, belirli test kartı reddedilir
        setProcessing(true)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            if (digits == DECLINED_TEST_CARD) {
                setProcessing(false)
                AlertDialog.Builder(requireContext())
                    .setTitle("Ödeme Reddedildi")
                    .setMessage("Bankanız bu işlemi onaylamadı (test kartı). Başka bir kartla deneyin.")
                    .setPositiveButton("Tamam", null)
                    .show()
                return@postDelayed
            }

            // 3) Ödeme "onaylandı": sipariş verilir. Kaydedilen kart bilgisi sadece tür + son 4 hane
            val payment = mapOf(
                "method" to "card",
                "cardBrand" to brand.label,
                "cardLast4" to digits.takeLast(4),
                "status" to "paid",
                "testMode" to true,
                "paidAt" to System.currentTimeMillis()
            )
            CartRepository.placeOrder(items, address, payment,
                onSuccess = {
                    if (!isAdded) return@placeOrder
                    setProcessing(false)
                    AlertDialog.Builder(requireContext())
                        .setTitle("Ödeme Başarılı ✅")
                        .setMessage("${brand.label} •••• ${digits.takeLast(4)} ile ödemeniz alındı (test modu).\nSiparişinizi Profil > Siparişlerim'den takip edebilirsiniz.")
                        .setPositiveButton("Tamam") { _, _ -> parentFragmentManager.popBackStack() }
                        .setCancelable(false)
                        .show()
                },
                onFailure = { e ->
                    if (!isAdded) return@placeOrder
                    setProcessing(false)
                    // Stok yetersizse vb. ödeme "alınmamış" sayılır, sipariş oluşmaz
                    Toast.makeText(requireContext(), "Sipariş verilemedi, ödeme alınmadı: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            )
        }, PROCESSING_DELAY_MS)
    }

    private fun setProcessing(processing: Boolean) {
        btnPay.isEnabled = !processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        if (processing) btnPay.text = "Banka onayı bekleniyor..."
        else btnPay.text = "${formatPrice(items.sumOf { it.lineTotal })} Öde"
    }

    private fun formatPrice(value: Double) = String.format(Locale("tr", "TR"), "%.2f TL", value)

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        cartListener?.remove()
        cartListener = null
    }

    // TextWatcher'ın kullanılmayan iki metodunu boş geçmek için
    private abstract class SimpleWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    companion object {
        private const val PROCESSING_DELAY_MS = 1500L
        private const val DECLINED_TEST_CARD = "4000000000000002"

        // Teslimat adresi ekrana argüman olarak taşınır
        fun newInstance(address: Address) = PaymentFragment().apply {
            arguments = Bundle().apply {
                putString("addressId", address.id)
                putString("title", address.title)
                putString("fullAddress", address.fullAddress)
                putString("detail", address.detail)
                putDouble("latitude", address.latitude)
                putDouble("longitude", address.longitude)
            }
        }

        private fun addressFromArgs(args: Bundle) = Address(
            id = args.getString("addressId") ?: "",
            title = args.getString("title") ?: "",
            fullAddress = args.getString("fullAddress") ?: "",
            detail = args.getString("detail") ?: "",
            latitude = args.getDouble("latitude"),
            longitude = args.getDouble("longitude")
        )
    }
}
