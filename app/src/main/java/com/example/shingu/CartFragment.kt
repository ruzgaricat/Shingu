package com.example.shingu

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.shingu.api.ApiClient
import com.example.shingu.api.ExchangeRateResponse
import com.example.shingu.api.WeatherRepository
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class CartFragment : Fragment() {

    private var cartListener: ListenerRegistration? = null
    private var currentItems: List<CartItem> = emptyList()

    private lateinit var adapter: CartAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvTotalForeign: TextView
    private lateinit var layoutSummary: View
    private lateinit var btnPlaceOrder: Button
    private lateinit var tvAddressTitle: TextView
    private lateinit var tvAddress: TextView
    private lateinit var btnChangeAddress: Button
    private lateinit var etCouponCode: TextInputEditText
    private lateinit var btnApplyCoupon: Button
    private lateinit var tvCouponStatus: TextView
    private lateinit var tvBreakdown: TextView
    private lateinit var tvFreeShippingHint: TextView

    // Sepete uygulanan kupon (doğrulanmış); ödeme ekranına kodu taşınır ve orada tekrar doğrulanır
    private var appliedCoupon: Coupon? = null
    private var pricing: OrderPricing = OrderPricing.calculate(0.0, null)

    // Kayıtlı adresler ve seçili teslimat adresi (harita ekranından dönünce de korunur)
    private var addressesListener: ListenerRegistration? = null
    private var addresses: List<Address> = emptyList()
    private var knownAddressIds: Set<String>? = null
    private var selectedAddressId: String? = null
    private val selectedAddress: Address? get() = addresses.firstOrNull { it.id == selectedAddressId }

    // 1 TL'nin USD ve EUR karşılığı (Frankfurter API'den gelir)
    private var tryRates: Map<String, Double>? = null
    private var ratesCall: Call<ExchangeRateResponse>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_cart, container, false)

        tvEmpty = view.findViewById(R.id.tvCartEmpty)
        tvTotal = view.findViewById(R.id.tvCartTotal)
        tvTotalForeign = view.findViewById(R.id.tvCartTotalForeign)
        layoutSummary = view.findViewById(R.id.layoutCartSummary)
        btnPlaceOrder = view.findViewById(R.id.btnPlaceOrder)
        tvAddressTitle = view.findViewById(R.id.tvCartAddressTitle)
        tvAddress = view.findViewById(R.id.tvCartAddress)
        btnChangeAddress = view.findViewById(R.id.btnChangeAddress)
        btnChangeAddress.setOnClickListener { showAddressChooser() }
        etCouponCode = view.findViewById(R.id.etCouponCode)
        btnApplyCoupon = view.findViewById(R.id.btnApplyCoupon)
        tvCouponStatus = view.findViewById(R.id.tvCouponStatus)
        tvBreakdown = view.findViewById(R.id.tvCartBreakdown)
        tvFreeShippingHint = view.findViewById(R.id.tvFreeShippingHint)
        btnApplyCoupon.setOnClickListener { if (appliedCoupon == null) applyCoupon() else removeCoupon(null) }
        appliedCoupon?.let { showCouponApplied(it) }

        adapter = CartAdapter(
            emptyList(),
            onIncrease = { item ->
                CartRepository.changeQuantity(item, 1) { e ->
                    if (isAdded) Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_SHORT).show()
                }
            },
            onDecrease = { CartRepository.changeQuantity(it, -1) },
            onRemove = { CartRepository.removeFromCart(it) }
        )
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerCart)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnPlaceOrder.setOnClickListener { showOrderDialog() }

        // Sepet, ürünlerin güncel fiyat/stok bilgileriyle birlikte canlı dinlenir
        cartListener = CartRepository.listenCartLive(
            onChange = { items -> if (isAdded) showItems(items) },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Sepet yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
        if (cartListener == null) showItems(emptyList())

        loadExchangeRates()
        listenAddresses()

        // Hava durumu gelince indirimli fiyatlar ve toplam yeniden hesaplanır
        WeatherRepository.getCurrent {
            if (isAdded) showItems(currentItems)
        }

        return view
    }

    // Web API 1: Güncel TRY → USD/EUR kurlarını Frankfurter API'den çeker
    private fun loadExchangeRates() {
        ratesCall = ApiClient.exchangeRateApi.getLatestRates(base = "TRY", symbols = "USD,EUR")
        ratesCall?.enqueue(object : Callback<ExchangeRateResponse> {
            override fun onResponse(call: Call<ExchangeRateResponse>, response: Response<ExchangeRateResponse>) {
                if (!isAdded) return
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    tryRates = body.rates
                    updateForeignTotal()
                } else {
                    tvTotalForeign.text = "Döviz kuru alınamadı"
                }
            }

            override fun onFailure(call: Call<ExchangeRateResponse>, t: Throwable) {
                if (!isAdded || call.isCanceled) return
                tvTotalForeign.text = "Döviz kuru alınamadı (internet bağlantınızı kontrol edin)"
            }
        })
    }

    private fun updateForeignTotal() {
        val rates = tryRates ?: return
        val total = pricing.total
        val usd = rates["USD"]?.let { total * it }
        val eur = rates["EUR"]?.let { total * it }
        tvTotalForeign.text = listOfNotNull(
            usd?.let { String.format(Locale.US, "$%.2f", it) },
            eur?.let { String.format(Locale.US, "€%.2f", it) }
        ).joinToString(prefix = "≈ ", separator = "  ·  ")
    }

    private fun showItems(items: List<CartItem>) {
        currentItems = items
        adapter.submitList(items)

        val isEmpty = items.isEmpty()
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        layoutSummary.visibility = if (isEmpty) View.GONE else View.VISIBLE

        // Sepet değişince kupon artık geçerli değilse (örn. tutar alt sınırın altına düştü) kaldırılır
        val subtotal = items.sumOf { it.lineTotal }
        appliedCoupon?.validationError(subtotal)?.let { reason -> removeCoupon(reason) }
        updatePricing()
    }

    // Ara toplam, kupon, kargo ve toplamı yeniden hesaplayıp gösterir
    private fun updatePricing() {
        pricing = OrderPricing.calculate(currentItems.sumOf { it.lineTotal }, appliedCoupon)
        val lines = mutableListOf("Ara toplam: ${formatPrice(pricing.subtotal)}")
        if (pricing.couponCode != null) lines += "Kupon (${pricing.couponCode}): -${formatPrice(pricing.couponDiscount)}"
        lines += "Kargo: " + if (pricing.shippingFee == 0.0) "Bedava" else formatPrice(pricing.shippingFee)
        tvBreakdown.text = lines.joinToString("\n")

        if (pricing.amountToFreeShipping > 0) {
            tvFreeShippingHint.text = "🚚 Bedava kargo için ${formatPrice(pricing.amountToFreeShipping)} daha ekleyin"
            tvFreeShippingHint.visibility = View.VISIBLE
        } else {
            tvFreeShippingHint.visibility = View.GONE
        }
        tvTotal.text = formatPrice(pricing.total)
        updateForeignTotal()
    }

    private fun applyCoupon() {
        val code = etCouponCode.text.toString().trim()
        if (code.isEmpty()) {
            etCouponCode.error = "Kupon kodunu girin"
            return
        }
        btnApplyCoupon.isEnabled = false
        CartRepository.validateCoupon(code, currentItems.sumOf { it.lineTotal })
            .addOnSuccessListener { coupon ->
                if (!isAdded) return@addOnSuccessListener
                btnApplyCoupon.isEnabled = true
                appliedCoupon = coupon
                showCouponApplied(coupon)
                updatePricing()
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                btnApplyCoupon.isEnabled = true
                showCouponMessage(e.localizedMessage ?: "Kupon uygulanamadı", isError = true)
            }
    }

    private fun showCouponApplied(coupon: Coupon) {
        etCouponCode.setText(coupon.code)
        etCouponCode.isEnabled = false
        btnApplyCoupon.text = "Kaldır"
        showCouponMessage("✓ ${coupon.code} uygulandı: ${coupon.label}", isError = false)
    }

    // reason null ise kullanıcı kendisi kaldırdı; değilse kuponun neden kaldırıldığı gösterilir
    private fun removeCoupon(reason: String?) {
        appliedCoupon = null
        etCouponCode.isEnabled = true
        btnApplyCoupon.text = "Uygula"
        if (reason == null) {
            etCouponCode.text?.clear()
            tvCouponStatus.visibility = View.GONE
        } else {
            showCouponMessage("Kupon kaldırıldı: $reason", isError = true)
        }
        updatePricing()
    }

    private fun showCouponMessage(message: String, isError: Boolean) {
        tvCouponStatus.text = message
        tvCouponStatus.setTextColor(Color.parseColor(if (isError) "#C62828" else "#388E3C"))
        tvCouponStatus.visibility = View.VISIBLE
    }

    private fun listenAddresses() {
        addressesListener = AddressRepository.listenAddresses(
            onChange = { list ->
                if (!isAdded) return@listenAddresses
                val previousIds = knownAddressIds
                addresses = list
                knownAddressIds = list.map { it.id }.toSet()

                // Haritadan yeni eklenen adres otomatik seçilir; seçili adres silindiyse en yenisine geçilir
                val newlyAdded = if (previousIds != null) list.firstOrNull { it.id !in previousIds } else null
                when {
                    newlyAdded != null -> selectedAddressId = newlyAdded.id
                    selectedAddress == null -> selectedAddressId = list.firstOrNull()?.id
                }
                showSelectedAddress()
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Adresler yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showSelectedAddress() {
        val address = selectedAddress
        if (address == null) {
            tvAddressTitle.text = "Teslimat Adresi"
            tvAddress.text = "Henüz adres eklemediniz"
            btnChangeAddress.text = "Adres Ekle"
        } else {
            tvAddressTitle.text = "Teslimat Adresi: ${address.title}"
            tvAddress.text = address.oneLine
            btnChangeAddress.text = "Değiştir"
        }
    }

    // Kayıtlı adresler arasından seçim; son seçenek haritadan yeni adres ekler
    private fun showAddressChooser() {
        if (addresses.isEmpty()) {
            AddressesFragment.openMapPicker(parentFragmentManager)
            return
        }
        val labels = addresses.map { "${it.title}\n${it.oneLine}" } + "+ Haritadan yeni adres ekle"
        val checked = addresses.indexOfFirst { it.id == selectedAddressId }
        AlertDialog.Builder(requireContext())
            .setTitle("Teslimat Adresi")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which == addresses.size) {
                    AddressesFragment.openMapPicker(parentFragmentManager)
                } else {
                    selectedAddressId = addresses[which].id
                    showSelectedAddress()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // Sipariş, ödeme ekranında kart onayı alındıktan sonra verilir
    private fun showOrderDialog() {
        if (currentItems.isEmpty()) return
        // Satıştan kalkmış veya stoğu yetmeyen ürün varsa ödemeye geçilmez
        currentItems.firstOrNull { !it.available }?.let {
            Toast.makeText(requireContext(), "${it.name} artık satışta değil, lütfen sepetten çıkarın.", Toast.LENGTH_LONG).show()
            return
        }
        currentItems.firstOrNull { it.exceedsStock }?.let {
            Toast.makeText(requireContext(), "${it.name} için stokta sadece ${it.stock} adet var, lütfen adedi azaltın.", Toast.LENGTH_LONG).show()
            return
        }
        val address = selectedAddress
        if (address == null) {
            Toast.makeText(requireContext(), "Sipariş için önce bir teslimat adresi ekleyin", Toast.LENGTH_SHORT).show()
            AddressesFragment.openMapPicker(parentFragmentManager)
            return
        }
        // Bu kontrolden önce kaydedilmiş adresler Türkiye dışında olabilir; ödemeye geçmeden engellenir
        if (!DeliveryArea.isInsideTurkeyBounds(address.latitude, address.longitude)) {
            AlertDialog.Builder(requireContext())
                .setTitle("Dağıtım Alanı Dışında")
                .setMessage("\"${address.title}\" adresi dağıtım alanımız içinde değil. Şu an sadece Türkiye içine teslimat yapıyoruz. Lütfen başka bir adres seçin.")
                .setPositiveButton("Adres Seç") { _, _ -> showAddressChooser() }
                .setNegativeButton("Vazgeç", null)
                .show()
            return
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, PaymentFragment.newInstance(address, appliedCoupon?.code))
            .addToBackStack(null)
            .commit()
    }

    private fun formatPrice(value: Double) = String.format(Locale("tr", "TR"), "%.2f TL", value)

    override fun onDestroyView() {
        super.onDestroyView()
        cartListener?.remove()
        cartListener = null
        addressesListener?.remove()
        addressesListener = null
        ratesCall?.cancel()
    }
}
