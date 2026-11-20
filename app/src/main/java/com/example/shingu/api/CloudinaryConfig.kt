package com.example.shingu.api

// Cloudinary hesap ayarları (cloudinary.com, ücretsiz plan, kart gerektirmez).
// Bu iki değer gizli değildir: "unsigned" yükleme ayarı zaten uygulamanın içinde bulunmak üzere tasarlanmıştır.
// Gizli olan API Secret değeri ASLA buraya yazılmaz.
object CloudinaryConfig {
    // Cloudinary paneli > Dashboard > "Cloud name"
    const val CLOUD_NAME = "sxdu50me"

    // Cloudinary paneli > Settings > Upload > Upload presets > (Signing mode: Unsigned) ön ayarın adı
    const val UPLOAD_PRESET = "shingu_unsigned"

    val isConfigured: Boolean get() = CLOUD_NAME.isNotBlank() && UPLOAD_PRESET.isNotBlank()
}
