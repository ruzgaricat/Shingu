package com.example.shingu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Sipariş tutarı, kupon ve kart doğrulama kurallarının birim testleri (Android/Firebase gerektirmez)
class PricingAndValidationTest {

    private fun coupon(
        type: String = Coupon.TYPE_PERCENT,
        value: Double = 10.0,
        minCartTotal: Double = 0.0,
        active: Boolean = true,
        expiresAt: Long? = null
    ) = Coupon("TEST", "", type, value, minCartTotal, active, expiresAt)

    // ---------- Kargo ----------
    @Test
    fun shippingIsChargedBelowThreshold() {
        val pricing = OrderPricing.calculate(100.0, null)
        assertEquals(ShopConfig.SHIPPING_FEE, pricing.shippingFee, 0.001)
        assertEquals(100.0 + ShopConfig.SHIPPING_FEE, pricing.total, 0.001)
        assertEquals(ShopConfig.FREE_SHIPPING_THRESHOLD - 100.0, pricing.amountToFreeShipping, 0.001)
    }

    @Test
    fun shippingIsFreeAtThreshold() {
        val pricing = OrderPricing.calculate(ShopConfig.FREE_SHIPPING_THRESHOLD, null)
        assertEquals(0.0, pricing.shippingFee, 0.001)
        assertEquals(0.0, pricing.amountToFreeShipping, 0.001)
    }

    @Test
    fun emptyCartHasNoShipping() {
        assertEquals(0.0, OrderPricing.calculate(0.0, null).total, 0.001)
    }

    // ---------- Kupon ----------
    @Test
    fun percentCouponDiscountsSubtotal() {
        val pricing = OrderPricing.calculate(300.0, coupon(value = 10.0))
        assertEquals(30.0, pricing.couponDiscount, 0.001)
        assertEquals(270.0, pricing.total, 0.001) // 270 >= 250 olduğu için kargo bedava
    }

    @Test
    fun couponCanDropOrderBelowFreeShipping() {
        // 260 TL - %10 = 234 TL -> bedava kargo eşiğinin altına düştüğü için kargo eklenir
        val pricing = OrderPricing.calculate(260.0, coupon(value = 10.0))
        assertEquals(ShopConfig.SHIPPING_FEE, pricing.shippingFee, 0.001)
        assertEquals(234.0 + ShopConfig.SHIPPING_FEE, pricing.total, 0.001)
    }

    @Test
    fun amountCouponCannotExceedSubtotal() {
        val pricing = OrderPricing.calculate(40.0, coupon(type = Coupon.TYPE_AMOUNT, value = 100.0))
        assertEquals(40.0, pricing.couponDiscount, 0.001)
    }

    @Test
    fun couponValidationRules() {
        assertNull(coupon(minCartTotal = 100.0).validationError(150.0))
        assertNotNull(coupon(minCartTotal = 100.0).validationError(50.0))
        assertNotNull(coupon(active = false).validationError(150.0))
        assertNotNull(coupon(expiresAt = 1_000L).validationError(150.0, now = 2_000L))
        assertNull(coupon(expiresAt = 3_000L).validationError(150.0, now = 2_000L))
    }

    @Test
    fun couponCodeIsNormalizedToTurkishUppercase() {
        assertEquals("İNDİRİM10", Coupon.normalize("  indirim10 "))
    }

    // ---------- Kart doğrulama ----------
    @Test
    fun luhnAcceptsTestCardsAndRejectsTypos() {
        assertTrue(CardValidator.isLuhnValid("4242424242424242"))
        assertTrue(CardValidator.isLuhnValid("5555555555554444"))
        assertFalse(CardValidator.isLuhnValid("4242424242424241"))
    }

    @Test
    fun cardBrandDetection() {
        assertEquals(CardBrand.VISA, CardBrand.detect("4242424242424242"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("5555555555554444"))
        assertEquals(CardBrand.MASTERCARD, CardBrand.detect("2223003122003222"))
        assertEquals(CardBrand.AMEX, CardBrand.detect("378282246310005"))
        assertEquals(CardBrand.TROY, CardBrand.detect("9792030000000000"))
    }

    @Test
    fun cardNumberErrors() {
        assertNull(CardValidator.numberError("4242 4242 4242 4242"))
        assertNotNull(CardValidator.numberError("4242 4242 4242"))      // eksik hane
        assertNotNull(CardValidator.numberError("4242 4242 4242 4241")) // Luhn hatası
    }

    @Test
    fun expiryErrors() {
        assertNull(CardValidator.expiryError("12/99"))
        assertNotNull(CardValidator.expiryError("01/20")) // geçmiş tarih
        assertNotNull(CardValidator.expiryError("13/30")) // geçersiz ay
        assertNotNull(CardValidator.expiryError("1230"))  // biçim
    }

    @Test
    fun cvvLengthDependsOnBrand() {
        assertNull(CardValidator.cvvError("123", CardBrand.VISA))
        assertNotNull(CardValidator.cvvError("123", CardBrand.AMEX))
        assertNull(CardValidator.cvvError("1234", CardBrand.AMEX))
    }
}
