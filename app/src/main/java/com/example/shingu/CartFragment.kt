package com.example.shingu

import android.app.AlertDialog
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

        adapter = CartAdapter(
            emptyList(),
            onIncrease = { CartRepository.changeQuantity(it, 1) },
            onDecrease = { CartRepository.changeQuantity(it, -1) },
            onRemove = { CartRepository.removeFromCart(it) }
        )
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerCart)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnPlaceOrder.setOnClickListener { showOrderDialog() }

        // Sepet Firestore'dan canlı dinlenir; ekleme/silme anında ekrana yansır
        cartListener = CartRepository.listenCart(
            onChange = { items -> showItems(items) },
            onFailure = { e -> Toast.makeText(requireContext(), "Sepet yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
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
        val total = currentItems.sumOf { it.lineTotal }
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
        tvTotal.text = formatPrice(items.sumOf { it.lineTotal })
        updateForeignTotal()
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

    private fun showOrderDialog() {
        if (currentItems.isEmpty()) return
        val address = selectedAddress
        if (address == null) {
            Toast.makeText(requireContext(), "Sipariş için önce bir teslimat adresi ekleyin", Toast.LENGTH_SHORT).show()
            AddressesFragment.openMapPicker(parentFragmentManager)
            return
        }
        val total = currentItems.sumOf { it.lineTotal }
        AlertDialog.Builder(requireContext())
            .setTitle("Siparişi Onayla")
            .setMessage("${currentItems.size} çeşit ürün, toplam ${formatPrice(total)}.\n\nTeslimat: ${address.title} - ${address.oneLine}\n\nSiparişi vermek istiyor musunuz?")
            .setPositiveButton("Sipariş Ver") { _, _ -> placeOrder(address) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun placeOrder(address: Address) {
        btnPlaceOrder.isEnabled = false
        CartRepository.placeOrder(
            currentItems,
            address,
            onSuccess = {
                // Fragment bu arada kapanmışsa (context null) mesaj gösterilmez
                if (isAdded) {
                    btnPlaceOrder.isEnabled = true
                    Toast.makeText(requireContext(), "Siparişiniz alındı!", Toast.LENGTH_LONG).show()
                }
            },
            onFailure = { e ->
                if (isAdded) {
                    btnPlaceOrder.isEnabled = true
                    Toast.makeText(requireContext(), "Sipariş verilemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        )
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
