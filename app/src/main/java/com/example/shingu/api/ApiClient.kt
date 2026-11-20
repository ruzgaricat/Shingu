package com.example.shingu.api

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---------- 1. Web API: Frankfurter (döviz kurları, anahtar gerektirmez) ----------
// Örnek: https://api.frankfurter.dev/v1/latest?base=TRY&symbols=USD,EUR
interface ExchangeRateApi {
    @GET("v1/latest")
    fun getLatestRates(
        @Query("base") base: String,
        @Query("symbols") symbols: String
    ): Call<ExchangeRateResponse>
}

data class ExchangeRateResponse(
    val base: String,
    val date: String,
    val rates: Map<String, Double>
)

// ---------- 2. Web API: Open-Meteo (hava durumu, anahtar gerektirmez) ----------
// Örnek: https://api.open-meteo.com/v1/forecast?latitude=40.77&longitude=29.94&current=temperature_2m,weather_code,wind_speed_10m
interface WeatherApi {
    @GET("v1/forecast")
    fun getCurrentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,weather_code,wind_speed_10m",
        @Query("timezone") timezone: String = "auto"
    ): Call<WeatherResponse>
}

data class WeatherResponse(val current: CurrentWeather)

data class CurrentWeather(
    val time: String,
    val temperature_2m: Double,
    val weather_code: Int,
    val wind_speed_10m: Double
)

// ---------- 3. Web API: Nominatim / OpenStreetMap (koordinattan adres bulma, anahtar gerektirmez) ----------
// Örnek: https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=40.76&lon=29.94&accept-language=tr
interface GeocodingApi {
    @GET("reverse")
    fun reverse(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("accept-language") language: String = "tr"
    ): Call<ReverseGeocodeResponse>
}

data class ReverseGeocodeResponse(
    val display_name: String?,
    val address: Map<String, String>?
) {
    // Ülke kodu (örn. "tr"); dağıtım alanı kontrolünde kullanılır
    fun countryCode(): String? = address?.get("country_code")

    // Türkiye adres düzenine uygun kısa adres: "Cadde No, Mahalle, İlçe/İl"
    fun shortAddress(): String? {
        val a = address ?: return display_name
        val street = listOfNotNull(a["road"] ?: a["pedestrian"] ?: a["footway"], a["house_number"]).joinToString(" ")
        val neighbourhood = a["suburb"] ?: a["neighbourhood"] ?: a["quarter"] ?: a["village"]
        val district = a["town"] ?: a["county"] ?: a["district"]
        val province = a["province"] ?: a["state"] ?: a["city"]
        val region = listOfNotNull(district, province).distinct().joinToString("/")
        return listOf(street, neighbourhood ?: "", region)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifEmpty { display_name }
    }
}

// ---------- 4. Web API: Cloudinary (görsel yükleme, ücretsiz hesap, kart gerektirmez) ----------
// "Unsigned upload preset" ile uygulamadan doğrudan yükleme yapılır; gizli API anahtarı uygulamaya konmaz.
// Örnek: POST https://api.cloudinary.com/v1_1/<bulut-adı>/image/upload  (file + upload_preset)
interface CloudinaryApi {
    @Multipart
    @POST("v1_1/{cloudName}/image/upload")
    fun upload(
        @Path("cloudName") cloudName: String,
        @Part file: MultipartBody.Part,
        @Part("upload_preset") uploadPreset: RequestBody,
        @Part("folder") folder: RequestBody
    ): Call<CloudinaryUploadResponse>
}

// Yükleme sonucu: secure_url, görselin herkese açık https bağlantısıdır
data class CloudinaryUploadResponse(
    val secure_url: String?,
    val bytes: Long?,
    val width: Int?,
    val height: Int?
)

// Retrofit örnekleri uygulama boyunca bir kez oluşturulur
object ApiClient {
    val exchangeRateApi: ExchangeRateApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.frankfurter.dev/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExchangeRateApi::class.java)
    }

    val weatherApi: WeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }

    // Nominatim kullanım kuralı gereği her istekte uygulamayı tanıtan bir User-Agent gönderilir
    val geocodingApi: GeocodingApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Shingu-Android/1.0 (ders projesi)").build())
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeocodingApi::class.java)
    }

    // Görsel yüklemesi yavaş bağlantıda uzun sürebileceği için zaman aşımları uzatıldı
    val cloudinaryApi: CloudinaryApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.cloudinary.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudinaryApi::class.java)
    }
}
