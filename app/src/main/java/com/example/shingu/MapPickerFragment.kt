package com.example.shingu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.shingu.api.ApiClient
import com.example.shingu.api.ReverseGeocodeResponse
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

// Haritadan adres seçme: harita kaydırıldıkça ortadaki iğnenin altındaki adres (cadde seviyesine kadar)
// Nominatim API ile otomatik bulunur. "Konumumu bul" butonu GPS ile haritayı kullanıcının konumuna taşır.
class MapPickerFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var tvPickedAddress: TextView
    private lateinit var tvOutOfArea: TextView
    private lateinit var etTitle: TextInputEditText
    private lateinit var etDetail: TextInputEditText
    private lateinit var btnSave: Button

    // Haritanın ortasındaki noktanın bulunan adresi
    private var pickedAddress: String? = null
    private var pickedPoint: GeoPoint? = null
    private var pickedCountryCode: String? = null // Adresle birlikte kaydedilir; sepetteki kargo alanı kontrolünde kullanılır

    private var geocodeCall: Call<ReverseGeocodeResponse>? = null
    private var locationRequestedByUser = false
    private val handler = Handler(Looper.getMainLooper())
    private val geocodeRunnable = Runnable { reverseGeocode(mapView.mapCenter as GeoPoint) }

    // Konum izni sonucu: izin verilirse konum bulunur
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            moveToCurrentLocation()
        } else if (locationRequestedByUser) {
            Toast.makeText(requireContext(), "Konum izni verilmedi; haritadan elle seçebilirsiniz.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // osmdroid ayarları: harita karoları sunucusu uygulamayı User-Agent ile tanımalı
        val context = requireContext().applicationContext
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "Shingu-Android/1.0 (ders projesi)"

        val view = inflater.inflate(R.layout.fragment_map_picker, container, false)

        mapView = view.findViewById(R.id.mapView)
        tvPickedAddress = view.findViewById(R.id.tvPickedAddress)
        tvOutOfArea = view.findViewById(R.id.tvOutOfArea)
        tvOutOfArea.text = DeliveryArea.OUT_OF_AREA_MESSAGE
        etTitle = view.findViewById(R.id.etAddressTitle)
        etDetail = view.findViewById(R.id.etAddressDetail)
        btnSave = view.findViewById(R.id.btnSaveAddress)

        setupMap()

        view.findViewById<FloatingActionButton>(R.id.fabMyLocation).setOnClickListener { requestLocation(byUser = true) }
        btnSave.setOnClickListener { saveAddress() }

        // Ekran açılınca kullanıcının konumuna gitmeyi dene
        requestLocation(byUser = false)

        return view
    }

    private fun setupMap() {
        // OpenStreetMap karo sunucusu, uygulamayı tanıtmayan istekleri (örn. "com.example..." User-Agent) 403 ile engeller;
        // yukarıda tanımlanan açıklayıcı User-Agent ile istek atılır.
        mapView.setTileSource(OSM_STANDARD)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.controller.setZoom(17.0)
        mapView.controller.setCenter(DEFAULT_POINT)

        // Harita durduktan kısa süre sonra adres sorgulanır (Nominatim saniyede 1 istek sınırı için)
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = scheduleGeocode()
            override fun onZoom(event: ZoomEvent?): Boolean = scheduleGeocode()
        })
        scheduleGeocode()
    }

    private fun scheduleGeocode(): Boolean {
        btnSave.isEnabled = false
        tvPickedAddress.text = "Adres bulunuyor..."
        handler.removeCallbacks(geocodeRunnable)
        handler.postDelayed(geocodeRunnable, 800)
        return true
    }

    // Web API 3: Koordinatı adrese çevirir (Nominatim reverse geocoding)
    private fun reverseGeocode(point: GeoPoint) {
        geocodeCall?.cancel()
        geocodeCall = ApiClient.geocodingApi.reverse(point.latitude, point.longitude)
        geocodeCall?.enqueue(object : Callback<ReverseGeocodeResponse> {
            override fun onResponse(call: Call<ReverseGeocodeResponse>, response: Response<ReverseGeocodeResponse>) {
                if (!isAdded) return
                val body = response.body()
                val address = body?.shortAddress()
                if (response.isSuccessful && !address.isNullOrBlank()) {
                    showPicked(point, address, body.countryCode())
                } else {
                    showPicked(point, null, null)
                }
            }

            override fun onFailure(call: Call<ReverseGeocodeResponse>, t: Throwable) {
                if (!isAdded || call.isCanceled) return
                showPicked(point, null, null)
            }
        })
    }

    // Adres bulunamazsa koordinatla kaydetmeye izin verilir; kullanıcı detay alanına tarif yazabilir.
    // Konum Türkiye dışındaysa uyarı gösterilir ve adres kaydedilemez.
    private fun showPicked(point: GeoPoint, address: String?, countryCode: String?) {
        pickedPoint = point
        pickedCountryCode = countryCode
        pickedAddress = address ?: String.format(Locale.US, "Konum: %.5f, %.5f", point.latitude, point.longitude)
        tvPickedAddress.text = pickedAddress

        // Ülke kodu biliniyorsa ona, bilinmiyorsa (servis yanıt vermediyse) Türkiye sınır kutusuna bakılır
        val inServiceArea = DeliveryArea.isServed(countryCode, point.latitude, point.longitude)

        tvOutOfArea.visibility = if (inServiceArea) View.GONE else View.VISIBLE
        btnSave.isEnabled = inServiceArea
    }

    private fun requestLocation(byUser: Boolean) {
        locationRequestedByUser = byUser
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            moveToCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // GPS ile anlık konumu alıp haritayı oraya taşır (izin requestLocation'da kontrol edildi)
    @SuppressLint("MissingPermission")
    private fun moveToCurrentLocation() {
        Toast.makeText(requireContext(), "Konumunuz bulunuyor...", Toast.LENGTH_SHORT).show()
        LocationServices.getFusedLocationProviderClient(requireContext())
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (!isAdded) return@addOnSuccessListener
                if (location == null) {
                    Toast.makeText(requireContext(), "Konum alınamadı. Cihazın konum servisi açık mı?", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude), 18.0, 800L)
            }
            .addOnFailureListener { e ->
                if (isAdded) Toast.makeText(requireContext(), "Konum alınamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveAddress() {
        val point = pickedPoint ?: return
        val fullAddress = pickedAddress ?: return
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            etTitle.error = "Adres adı girin (örn. Ev, İş)"
            return
        }

        btnSave.isEnabled = false
        val address = Address(
            title = title,
            fullAddress = fullAddress,
            detail = etDetail.text.toString().trim(),
            latitude = point.latitude,
            longitude = point.longitude,
            countryCode = pickedCountryCode
        )
        AddressRepository.addAddress(address,
            onSuccess = {
                if (!isAdded) return@addAddress
                Toast.makeText(requireContext(), "Adres kaydedildi", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            },
            onFailure = { e ->
                if (!isAdded) return@addAddress
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), "Adres kaydedilemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(geocodeRunnable)
        geocodeCall?.cancel()
        mapView.onDetach()
    }

    companion object {
        // Konum alınamazsa harita Kocaeli (İzmit) merkezinde açılır
        private val DEFAULT_POINT = GeoPoint(40.7654, 29.9408)

        // Standart OSM karoları; önceki engellenmiş denemelerden kalan önbellek karışmasın diye ayrı bir adla tanımlı
        private val OSM_STANDARD = XYTileSource(
            "OsmStandard", 1, 19, 256, ".png",
            arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap katkıcıları"
        )
    }
}
