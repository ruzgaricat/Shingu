package com.example.shingu

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val etEmail = findViewById<EditText>(R.id.etLoginEmail)
        val etPassword = findViewById<EditText>(R.id.etLoginPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGoogleLogin = findViewById<Button>(R.id.btnGoogleLogin)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val tvGoToRegister = findViewById<TextView>(R.id.tvGoToRegister)

        // 1. E-posta ve Şifre ile Giriş Mantığı
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener { result ->
                        val uid = result.user?.uid ?: ""
                        checkUserStatusAndNavigate(uid)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Giriş Başarısız: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Lütfen e-posta ve şifrenizi girin", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Google ile Giriş Butonu
        btnGoogleLogin.setOnClickListener {
            signInWithGoogle()
        }

        // Şifremi Unuttum: e-postaya Firebase şifre sıfırlama bağlantısı gönderilir
        tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog(etEmail.text.toString().trim())
        }

        // 3. Kayıt Ol ekranına geçiş
        tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun showForgotPasswordDialog(prefilledEmail: String) {
        val etResetEmail = EditText(this).apply {
            hint = "E-posta adresiniz"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(prefilledEmail)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(etResetEmail)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Şifremi Unuttum")
            .setMessage("Şifre sıfırlama bağlantısı e-posta adresinize gönderilecek.")
            .setView(container)
            .setPositiveButton("Gönder", null) // Hatalı girişte diyalog kapanmasın diye aşağıda bağlanıyor
            .setNegativeButton("Vazgeç", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = etResetEmail.text.toString().trim()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    etResetEmail.error = "Geçerli bir e-posta adresi girin"
                    return@setOnClickListener
                }
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        dialog.dismiss()
                        Toast.makeText(this, "Bu adrese kayıtlı bir hesap varsa sıfırlama bağlantısı gönderildi.", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Gönderilemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        dialog.show()
    }

    private fun signInWithGoogle() {
        val webClientId = "889950376243-7at4vuh4q06plmvqs4m60nr4j512f8f3.apps.googleusercontent.com"

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credentialManager = CredentialManager.create(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@LoginActivity
                )

                val credential = result.credential
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val googleIdToken = googleIdTokenCredential.idToken

                withContext(Dispatchers.Main) {
                    firebaseAuthWithGoogle(googleIdToken)
                }
            } catch (e: GetCredentialException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Google Giriş İptal Edildi veya Hata Oluştu", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val firebaseUser = authResult.user
                val uid = firebaseUser?.uid ?: ""
                checkAndSaveGoogleUser(uid, firebaseUser?.displayName ?: "Kullanıcı", firebaseUser?.email ?: "")
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Firebase Kimlik Doğrulama Hatası: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun checkAndSaveGoogleUser(uid: String, name: String, email: String) {
        val userRef = db.collection("users").document(uid)

        userRef.get().addOnSuccessListener { document ->
            if (!document.exists()) {
                // İlk defa Google ile giriş yapıyor: Yeni kullanıcı oluştur
                val newUser = hashMapOf(
                    "uid" to uid,
                    "fullName" to name,
                    "email" to email,
                    "role" to "user",
                    "status" to "active"
                )
                userRef.set(newUser).addOnSuccessListener {
                    navigateToMain()
                }
            } else {
                // Zaten kayıtlı: Hesap askıda (suspended) mı kontrol et
                val status = document.getString("status")
                if (status == "suspended") {
                    Toast.makeText(this, "Bu hesap askıya alınmıştır.", Toast.LENGTH_LONG).show()
                    auth.signOut()
                } else {
                    navigateToMain()
                }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Giriş yapılırken hata oluştu: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUserStatusAndNavigate(uid: String) {
        val userRef = db.collection("users").document(uid)
        userRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                val status = document.getString("status")
                if (status == "suspended") {
                    Toast.makeText(this, "Bu hesap askıya alınmıştır.", Toast.LENGTH_LONG).show()
                    auth.signOut()
                } else {
                    // Profilden değiştirilip doğrulanan e-posta Firestore'a da yansıtılır
                    val authEmail = auth.currentUser?.email
                    if (authEmail != null && authEmail != document.getString("email")) {
                        userRef.update("email", authEmail)
                    }
                    navigateToMain()
                }
            } else {
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}