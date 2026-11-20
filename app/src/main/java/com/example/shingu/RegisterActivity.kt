package com.example.shingu

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : BaseActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val etName = findViewById<EditText>(R.id.etRegisterName)
        val etEmail = findViewById<EditText>(R.id.etRegisterEmail)
        val etPassword = findViewById<EditText>(R.id.etRegisterPassword)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val tvGoToLogin = findViewById<TextView>(R.id.tvGoToLogin)

        btnRegister.setOnClickListener {
            val name = etName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            // 1. İsim Kontrolü
            val namePattern = "^[a-zA-ZçğıöşüÇĞİÖŞÜ ]+$"
            if (name.isEmpty()) {
                etName.error = "İsim alanı boş bırakılamaz"
                return@setOnClickListener
            } else if (!name.matches(namePattern.toRegex())) {
                etName.error = "İsim rakam veya özel sembol içeremez"
                return@setOnClickListener
            }

            // 2. E-posta Format Kontrolü
            if (email.isEmpty()) {
                etEmail.error = "E-posta alanı boş bırakılamaz"
                return@setOnClickListener
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etEmail.error = "Geçersiz e-posta adresi"
                return@setOnClickListener
            }

            // 3. Şifre Uzunluk Kontrolü
            if (password.length < 6) {
                etPassword.error = "Şifre en az 6 karakter olmalıdır"
                return@setOnClickListener
            }

            // Tüm kontroller başarılı ise kayıt işlemini başlat
            registerUser(name, email, password)
        }

        tvGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun registerUser(name: String, email: String, pass: String) {
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid ?: ""
                val newUser = User(
                    uid = uid,
                    fullName = name,
                    email = email,
                    role = "user"
                )

                // Firestore'a kaydı arka planda başlatıyoruz
                db.collection("users").document(uid).set(newUser)

                // Auth başarılı olduğu an doğrudan giriş ekranına yönlendiriyoruz
                Toast.makeText(this, "Kayıt başarılı! Giriş yapabilirsiniz.", Toast.LENGTH_LONG).show()

                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                val errorMessage = when (e) {
                    is FirebaseAuthWeakPasswordException -> "Şifre çok zayıf. Lütfen daha güçlü bir şifre girin."
                    is FirebaseAuthUserCollisionException -> "Bu e-posta adresi zaten kullanımda."
                    is FirebaseAuthInvalidCredentialsException -> "Geçersiz e-posta biçimi."
                    else -> "Kayıt hatası: ${e.localizedMessage}"
                }
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }
    }
}