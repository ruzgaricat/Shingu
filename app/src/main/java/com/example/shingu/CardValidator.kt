package com.example.shingu

import java.util.Calendar

// Kart türü: numaranın başlangıç hanelerinden anlaşılır; uzunluk ve CVV hane sayısı türe göre değişir
enum class CardBrand(val label: String, val lengths: Set<Int>, val cvvLength: Int) {
    VISA("Visa", setOf(16), 3),
    MASTERCARD("Mastercard", setOf(16), 3),
    TROY("Troy", setOf(16), 3),
    AMEX("American Express", setOf(15), 4),
    UNKNOWN("Kart", setOf(16), 3);

    companion object {
        fun detect(digits: String): CardBrand = when {
            digits.startsWith("9792") -> TROY
            digits.startsWith("34") || digits.startsWith("37") -> AMEX
            digits.startsWith("4") -> VISA
            digits.length >= 2 && digits.take(2).toInt() in 51..55 -> MASTERCARD
            digits.length >= 4 && digits.take(4).toInt() in 2221..2720 -> MASTERCARD
            else -> UNKNOWN
        }
    }
}

// Kart bilgisi doğrulama (test ödemesi için). Gerçek ödeme alınmaz; kart numarasının tamamı ve CVV
// hiçbir yere gönderilmez/kaydedilmez, siparişe sadece kart türü ve son 4 hane yazılır.
object CardValidator {

    fun digitsOnly(text: String) = text.filter { it.isDigit() }

    // Luhn algoritması: kart numarasının son hanesi bir kontrol hanesidir; yanlış yazılan numaraları yakalar
    fun isLuhnValid(digits: String): Boolean {
        if (digits.isEmpty()) return false
        var sum = 0
        digits.reversed().forEachIndexed { index, c ->
            var d = c - '0'
            if (index % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    // Kart numarası hatası varsa mesajını, yoksa null döner
    fun numberError(text: String): String? {
        val digits = digitsOnly(text)
        val brand = CardBrand.detect(digits)
        return when {
            digits.isEmpty() -> "Kart numarasını girin"
            digits.length !in brand.lengths -> "Kart numarası ${brand.lengths.first()} haneli olmalı"
            !isLuhnValid(digits) -> "Geçersiz kart numarası"
            else -> null
        }
    }

    // "AA/YY" biçimindeki son kullanma tarihi; geçmiş bir ay olamaz
    fun expiryError(text: String): String? {
        val parts = text.split("/")
        val month = parts.getOrNull(0)?.toIntOrNull()
        val year = parts.getOrNull(1)?.takeIf { it.length == 2 }?.toIntOrNull()
        if (month == null || year == null || month !in 1..12) return "AA/YY biçiminde girin"

        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR) % 100
        val currentMonth = now.get(Calendar.MONTH) + 1
        return if (year < currentYear || (year == currentYear && month < currentMonth)) "Kartın süresi dolmuş" else null
    }

    fun cvvError(cvv: String, brand: CardBrand): String? =
        if (cvv.length == brand.cvvLength && cvv.all { it.isDigit() }) null else "${brand.cvvLength} haneli güvenlik kodunu girin"

    // Görüntüleme için 4'erli gruplama: "4242424242424242" -> "4242 4242 4242 4242"
    fun formatNumber(digits: String) = digits.chunked(4).joinToString(" ")
}
