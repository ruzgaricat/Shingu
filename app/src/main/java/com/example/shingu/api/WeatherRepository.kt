package com.example.shingu.api

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Ürünlere etiket olarak verilebilen hava durumları (Firestore'da 'code' saklanır)
enum class WeatherCondition(val code: String, val label: String, val emoji: String) {
    SUNNY("sunny", "Güneşli", "☀️"),
    CLOUDY("cloudy", "Bulutlu", "☁️"),
    RAINY("rainy", "Yağmurlu", "☔"),
    SNOWY("snowy", "Karlı", "❄️");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }

        // Open-Meteo'nun WMO hava kodunu bizim etiketlerimizden birine eşler
        fun fromWmoCode(code: Int): WeatherCondition = when (code) {
            0, 1 -> SUNNY
            in 51..67, in 80..82, in 95..99 -> RAINY
            in 71..77, 85, 86 -> SNOWY
            else -> CLOUDY
        }
    }
}

// Anlık hava durumunu bir kez çekip 30 dakika saklar; ana sayfa, ürünler ve sepet aynı veriyi kullanır
object WeatherRepository {

    // Kocaeli (İzmit) koordinatları
    private const val LATITUDE = 40.7654
    private const val LONGITUDE = 29.9408
    private const val CACHE_MS = 30 * 60 * 1000L

    var current: CurrentWeather? = null
        private set
    val condition: WeatherCondition?
        get() = current?.let { WeatherCondition.fromWmoCode(it.weather_code) }

    private var fetchedAt = 0L
    private var inFlight = false
    private val waiting = mutableListOf<(CurrentWeather?) -> Unit>()

    // Hava durumu hazır olunca callback çağrılır (hata durumunda null gelir)
    fun getCurrent(onResult: (CurrentWeather?) -> Unit) {
        val cached = current
        if (cached != null && System.currentTimeMillis() - fetchedAt < CACHE_MS) {
            onResult(cached)
            return
        }
        waiting += onResult
        if (inFlight) return
        inFlight = true

        ApiClient.weatherApi.getCurrentWeather(LATITUDE, LONGITUDE)
            .enqueue(object : Callback<WeatherResponse> {
                override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                    response.body()?.current?.let {
                        current = it
                        fetchedAt = System.currentTimeMillis()
                    }
                    finish(if (response.isSuccessful) response.body()?.current else null)
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) = finish(null)
            })
    }

    private fun finish(result: CurrentWeather?) {
        inFlight = false
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(result) }
    }
}

// Ürünün hava durumu etiketi şu anki havaya uyuyorsa indirim uygulanır
object WeatherDiscount {

    fun isActive(weatherTag: String?, discountPercent: Int): Boolean =
        weatherTag != null && discountPercent > 0 && weatherTag == WeatherRepository.condition?.code

    fun effectivePrice(price: Double, weatherTag: String?, discountPercent: Int): Double =
        if (isActive(weatherTag, discountPercent)) price * (100 - discountPercent) / 100.0 else price
}
