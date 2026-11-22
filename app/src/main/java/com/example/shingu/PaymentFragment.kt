package com.example.shingu

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var cbAgreement: CheckBox

    // Sepetten gelen kupon kodu; kupon burada tekrar okunur, sipariş verilirken bir kez daha doğrulanır
    private val couponCode: String? get() = arguments?.getString("couponCode")
    private var coupon: Coupon? = null
    private var pricing: OrderPricing = OrderPricing.calculate(0.0, null)

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

        cbAgreement = view.findViewById(R.id.cbAgreement)
        view.findViewById<Button>(R.id.btnShowContract).setOnClickListener { showContract() }
        view.findViewById<Button>(R.id.btnShowKvkk).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("KVKK Aydınlatma Metni")
                .setMessage(LegalTexts.kvkk)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Gizlilik Politikası") { _, _ -> LegalTexts.openPrivacyPolicy(requireContext()) }
                .show()
        }

        // Sepette uygulanan kupon burada tekrar okunur (bu arada pasif yapılmış olabilir)
        couponCode?.let { code ->
            CouponRepository.getCoupon(code).addOnSuccessListener { loaded ->
                if (!isAdded) return@addOnSuccessListener
                coupon = loaded
                updateSummary()
            }
        }

        // Sepet, ürünlerin güncel fiyatlarıyla canlı dinlenir: tutar her zaman güncel sepetten hesaplanır
        cartListener = CartRepository.listenCartLive(
            onChange = { cartItems ->
                if (!isAdded) return@listenCartLive
                items = cartItems
                updateSummary()
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Sepet yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    // Ara toplam, kupon, kargo ve toplamı gösterir. Kupon artık geçerli değilse hesaba katılmaz.
    private fun updateSummary() {
        val subtotal = items.sumOf { it.lineTotal }
        val validCoupon = coupon?.takeIf { it.validationError(subtotal) == null }
        pricing = OrderPricing.calculate(subtotal, validCoupon)

        tvItems.text = "🛒 ${items.size} çeşit ürün, ${items.sumOf { it.quantity }} adet\n" +
            "Ara toplam: ${formatPrice(pricing.subtotal)}" +
            (pricing.couponCode?.let { "\nKupon ($it): -${formatPrice(pricing.couponDiscount)}" } ?: "") +
            (if (couponCode != null && validCoupon == null) "\nKupon ($couponCode) bu sepete artık uygulanamıyor" else "") +
            "\nKargo: " + (if (pricing.shippingFee == 0.0) "Bedava" else formatPrice(pricing.shippingFee))
        tvTotal.text = "Toplam: ${formatPrice(pricing.total)}"
        if (btnPay.isEnabled) btnPay.text = "${formatPrice(pricing.total)} Öde"
    }

    private fun showContract() {
        val summary = items.joinToString { "${it.name} x${it.quantity}" }
        showText("Mesafeli Satış Sözleşmesi", LegalTexts.distanceSalesContract(summary, formatPrice(pricing.total), "${address.title} - ${address.oneLine}"))
    }

    private fun showText(title: String, text: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("Kapat", null)
            .show()
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

        // Kargo alanı dışındaki adrese sipariş verilemez (sepette de engellenir; burada ikinci kontrol)
        if (!address.isInDeliveryArea) {
            Toast.makeText(requireContext(), DeliveryArea.CART_BLOCKED_MESSAGE, Toast.LENGTH_LONG).show()
            return
        }
        if (items.any { !it.available || it.exceedsStock }) {
            Toast.makeText(requireContext(), "Sepetinizde satışta olmayan veya stoğu yetmeyen ürün var. Lütfen sepeti düzenleyin.", Toast.LENGTH_LONG).show()
            return
        }
        // Mesafeli satış sözleşmesi onayı zorunlu
        if (!cbAgreement.isChecked) {
            cbAgreement.error = "Devam etmek için sözleşmeyi onaylayın"
            Toast.makeText(requireContext(), "Lütfen Mesafeli Satış Sözleşmesi'ni onaylayın", Toast.LENGTH_SHORT).show()
            return
        }
        cbAgreement.error = null

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
            CartRepository.placeOrder(items, address, payment, pricing.couponCode, System.currentTimeMillis(),
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
        else btnPay.text = "${formatPrice(pricing.total)} Öde"
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
        fun newInstance(address: Address, couponCode: String?) = PaymentFragment().apply {
            arguments = Bundle().apply {
                putString("couponCode", couponCode)
                putString("addressId", address.id)
                putString("title", address.title)
                putString("fullAddress", address.fullAddress)
                putString("detail", address.detail)
                putDouble("latitude", address.latitude)
                putDouble("longitude", address.longitude)
                putString("countryCode", address.countryCode)
            }
        }

        private fun addressFromArgs(args: Bundle) = Address(
            id = args.getString("addressId") ?: "",
            title = args.getString("title") ?: "",
            fullAddress = args.getString("fullAddress") ?: "",
            detail = args.getString("detail") ?: "",
            latitude = args.getDouble("latitude"),
            longitude = args.getDouble("longitude"),
            countryCode = args.getString("countryCode")
        )
    }
}
