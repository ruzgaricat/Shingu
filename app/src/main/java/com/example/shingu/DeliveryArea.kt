package com.example.shingu

// Dağıtım alanı: şu an sadece Türkiye içine teslimat yapılır.
object DeliveryArea {

    const val COUNTRY_CODE = "tr"
    const val OUT_OF_AREA_MESSAGE = "⚠ Konumunuz dağıtım alanımız içinde değil. Şu an sadece Türkiye içine teslimat yapıyoruz."

    // Asıl kontrol: Nominatim'in döndürdüğü ülke kodu
    fun isServedCountry(countryCode: String?) = countryCode.equals(COUNTRY_CODE, ignoreCase = true)

    // Yedek kontrol (adres servisine ulaşılamazsa ve eski kayıtlı adresler için): Türkiye'nin yaklaşık
    // sınır kutusu. Kaba bir dikdörtgen olduğu için komşu ülkelerin sınıra yakın kısımlarını da kapsar.
    fun isInsideTurkeyBounds(latitude: Double, longitude: Double) =
        latitude in 35.8..42.2 && longitude in 25.6..44.9
}
