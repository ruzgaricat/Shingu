package com.example.shingu

// Dağıtım alanı: şu an sadece Türkiye içine teslimat yapılır.
object DeliveryArea {

    const val COUNTRY_CODE = "tr"
    const val OUT_OF_AREA_MESSAGE = "⚠ Konumunuz dağıtım alanımız içinde değil. Şu an sadece Türkiye içine teslimat yapıyoruz."
    const val CART_BLOCKED_MESSAGE = "⚠ Seçili adres kargo alanımız dışında. Şu an sadece Türkiye içine teslimat yapıyoruz; siparişi onaylamak için Türkiye'de bir adres seçin."

    // Tek kontrol noktası: ülke kodu biliniyorsa ona göre, bilinmiyorsa (eski adres / adres servisi yanıt vermedi)
    // Türkiye sınır kutusuna göre karar verilir
    fun isServed(countryCode: String?, latitude: Double, longitude: Double): Boolean =
        if (countryCode != null) isServedCountry(countryCode) else isInsideTurkeyBounds(latitude, longitude)

    // Asıl kontrol: Nominatim'in döndürdüğü ülke kodu
    fun isServedCountry(countryCode: String?) = countryCode.equals(COUNTRY_CODE, ignoreCase = true)

    // Yedek kontrol (adres servisine ulaşılamazsa ve eski kayıtlı adresler için): Türkiye'nin yaklaşık
    // sınır kutusu. Kaba bir dikdörtgen olduğu için komşu ülkelerin sınıra yakın kısımlarını da kapsar.
    fun isInsideTurkeyBounds(latitude: Double, longitude: Double) =
        latitude in 35.8..42.2 && longitude in 25.6..44.9
}
