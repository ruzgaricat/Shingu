package com.example.shingu

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var cbRememberMe: CheckBox
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        // Başlık durum çubuğunun arkasına kadar uzanır, yazılar altında kalmaz
        findViewById<android.view.View>(R.id.loginHeader).padForSystemBars(top = true)
        findViewById<android.view.View>(R.id.loginRoot).padForSystemBars(bottom = true)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val etEmail = findViewById<EditText>(R.id.etLoginEmail)
        val etPassword = findViewById<EditText>(R.id.etLoginPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGoogleLogin = findViewById<Button>(R.id.btnGoogleLogin)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val tvGoToRegister = findViewById<TextView>(R.id.tvGoToRegister)
        cbRememberMe = findViewById(R.id.cbRememberMe)

        // Beni Hatırla: önceki tercih ve e-posta geri yüklenir
        val rememberMe = prefs.getBoolean(KEY_REMEMBER_ME, false)
        cbRememberMe.isChecked = rememberMe
        if (rememberMe) etEmail.setText(prefs.getString(KEY_REMEMBERED_EMAIL, ""))

        // Firebase oturumu cihazda saklanır; "beni hatırla" seçiliyse doğrudan ana ekrana geçilir,
        // seçili değilse uygulama her açılışta oturumu kapatır
        val currentUser = auth.currentUser
        if (currentUser != null) {
            if (rememberMe) {
                checkUserStatusAndNavigate(currentUser.uid)
            } else {
                auth.signOut()
            }
        }

        // 1. E-posta ve Şifre ile Giriş Mantığı
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener { result ->
                        saveRememberMe(email)
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
            } catch (e: NoCredentialException) {
                // Cihazda kayıtlı Google hesabı yok
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Cihazda Google hesabı bulunamadı. Ayarlar > Hesaplar'dan bir Google hesabı ekleyin.", Toast.LENGTH_LONG).show()
                }
            } catch (e: GetCredentialCancellationException) {
                // Kullanıcı hesap seçim penceresini kapattı; hata mesajı gösterilmez
            } catch (e: GetCredentialException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Google ile giriş yapılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Örn. Google kimlik bilgisi çözülemedi; yakalanmazsa uygulama çökerdi
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Google ile giriş yapılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                saveRememberMe(firebaseUser?.email ?: "")
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
                userRef.set(newUser)
                    .addOnSuccessListener { navigateToMain() }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Profil oluşturulamadı: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        auth.signOut()
                    }
            } else {
                // Zaten kayıtlı: hesap dondurulmuş, askıya alınmış veya silinmiş mi kontrol et
                handleAccountStatus(document)
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Giriş yapılırken hata oluştu: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUserStatusAndNavigate(uid: String) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (isFinishing) return@addOnSuccessListener
                if (document.exists()) handleAccountStatus(document) else navigateToMain()
            }
            .addOnFailureListener { e ->
                // Örn. internet yoksa giriş ekranında sessizce takılı kalınmasın, sebep gösterilsin
                Toast.makeText(this, "Hesap bilgileri alınamadı: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    // Hesap durumuna göre giriş:
    //  - aktif: ana ekrana geçilir
    //  - kullanıcının kendi askıya aldığı hesap: yeniden etkinleştirme sorulur (üyeliği geçici pasifleştirme)
    //  - admin'in dondurduğu veya silinmiş hesap: giriş engellenir
    private fun handleAccountStatus(document: DocumentSnapshot) {
        val status = AccountStatus.fromCode(document.getString("status"))
        when {
            status == AccountStatus.ACTIVE -> {
                // Profilden değiştirilip doğrulanan e-posta Firestore'a da yansıtılır
                val authEmail = auth.currentUser?.email
                if (authEmail != null && authEmail != document.getString("email")) {
                    document.reference.update("email", authEmail)
                }
                navigateToMain()
            }
            status == AccountStatus.SUSPENDED && document.getString("suspendedBy") == SUSPENDED_BY_USER ->
                showReactivateDialog(document.reference)
            else -> {
                val message = if (status == AccountStatus.DELETED) "Bu hesap silinmiştir." else "Bu hesap yönetici tarafından dondurulmuştur."
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                auth.signOut()
            }
        }
    }

    private fun showReactivateDialog(userRef: DocumentReference) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Hesabınız Askıda")
            .setMessage("Hesabınızı daha önce askıya almıştınız. Yeniden etkinleştirip devam etmek ister misiniz?")
            .setCancelable(false)
            .setPositiveButton("Hesabımı Etkinleştir") { _, _ ->
                UserRepository.reactivateOwnAccount(userRef)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Hesabınız yeniden etkinleştirildi. Tekrar hoş geldiniz!", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Hesap etkinleştirilemedi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        auth.signOut()
                    }
            }
            .setNegativeButton("Vazgeç") { _, _ -> auth.signOut() }
            .show()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // Başarılı girişte "beni hatırla" tercihi kaydedilir; seçili değilse kayıtlı e-posta silinir
    private fun saveRememberMe(email: String) {
        prefs.edit()
            .putBoolean(KEY_REMEMBER_ME, cbRememberMe.isChecked)
            .putString(KEY_REMEMBERED_EMAIL, if (cbRememberMe.isChecked) email else "")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "shingu_login"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_REMEMBERED_EMAIL = "remembered_email"
    }
}