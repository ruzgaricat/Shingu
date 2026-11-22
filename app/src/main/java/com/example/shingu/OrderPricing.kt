package com.example.shingu

import java.util.Locale

// Tutar ve tarih biçimleri için Türkçe yerel ayarı (ondalık ayırıcı virgül: 1299,90 TL)
val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")

// Mağaza ayarları
object ShopConfig {
    const val SHIPPING_FEE = 39.90             // Standart kargo ücreti (TL)
    const val FREE_SHIPPING_THRESHOLD = 250.0  // Kupon sonrası tutar bunun üzerindeyse kargo bedava
}

// Sipariş tutarının dökümü. Sepet, ödeme ekranı ve sipariş kaydı aynı hesabı kullanır.
data class OrderPricing(
    val subtotal: Double,        // Ürünler toplamı (hava durumu indirimleri dahil)
    val couponCode: String?,
    val couponDiscount: Double,  // Kupon indirimi (TL)
    val shippingFee: Double,     // 0 ise kargo bedava
    val total: Double
) {
    // Bedava kargoya kalan tutar (bilgilendirme için); bedavaysa 0
    val amountToFreeShipping: Double
        get() = if (shippingFee == 0.0) 0.0 else ShopConfig.FREE_SHIPPING_THRESHOLD - (subtotal - couponDiscount)

    companion object {
        // coupon: sepete uygulanmış ve geçerliliği kontrol edilmiş kupon (yoksa null)
        fun calculate(subtotal: Double, coupon: Coupon?): OrderPricing {
            val discount = coupon?.discountFor(subtotal) ?: 0.0
            val afterDiscount = subtotal - discount
            val shipping = if (subtotal == 0.0 || afterDiscount >= ShopConfig.FREE_SHIPPING_THRESHOLD) 0.0 else ShopConfig.SHIPPING_FEE
            return OrderPricing(
                subtotal = subtotal,
                couponCode = coupon?.code,
                couponDiscount = discount,
                shippingFee = shipping,
                total = afterDiscount + shipping
            )
        }

        fun format(value: Double) = String.format(TR_LOCALE, "%.2f TL", value)
    }
}
