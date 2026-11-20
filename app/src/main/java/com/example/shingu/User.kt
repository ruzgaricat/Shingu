package com.example.shingu

data class User(
    val uid: String = "",
    val fullName: String = "",
    val email: String = "",
    val role: String = "user", // Varsayılan rol normal kullanıcı
    val status: String = "active" // "active" veya "suspended"; giriş ekranı bu alana bakar
)

// Giriş yapan kullanıcının rolü; MainActivity açılırken Firestore'dan doldurulur
object UserSession {
    var isAdmin: Boolean = false
}
