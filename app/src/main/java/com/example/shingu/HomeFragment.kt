package com.example.shingu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.shingu.api.WeatherCondition
import com.example.shingu.api.WeatherRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private lateinit var tvWeatherStrip: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        tvWeatherStrip = view.findViewById(R.id.tvWeatherStrip)
        loadWeather()

        return view
    }

    // Web API 2: Anlık hava durumu Open-Meteo API'den (WeatherRepository üzerinden) gelir
    private fun loadWeather() {
        WeatherRepository.getCurrent { current ->
            if (!isAdded) return@getCurrent
            if (current == null) {
                tvWeatherStrip.text = "Hava durumu alınamadı"
                return@getCurrent
            }
            // Örn: "☔ Kocaeli · 10°C · Yağmurlu"
            val condition = WeatherCondition.fromWmoCode(current.weather_code)
            tvWeatherStrip.text = "${condition.emoji}  Kocaeli · ${current.temperature_2m.roundToInt()}°C · ${condition.label}"
            showDiscountIfAny(condition)
        }
    }

    // Bu havaya etiketlenmiş ürün varsa şeridin sonuna kısa bir indirim notu eklenir
    private fun showDiscountIfAny(condition: WeatherCondition) {
        FirebaseFirestore.getInstance().collection("products")
            .whereEqualTo("weatherTag", condition.code)
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                if (!isAdded || result.isEmpty) return@addOnSuccessListener
                tvWeatherStrip.append(" · Hava indirimi aktif")
            }
    }
}
