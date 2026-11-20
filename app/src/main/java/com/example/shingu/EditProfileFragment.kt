package com.example.shingu

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ExecutionException

// Kullanıcının (ve admin'in) kendi ad-soyad, e-posta ve şifresini güncellediği ekran
class EditProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var etName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etNewPassword: TextInputEditText
    private lateinit var etNewPasswordRepeat: TextInputEditText
    private lateinit var etCurrentPassword: TextInputEditText
    private lateinit var btnSave: Button

    private var originalName = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_edit_profile, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        etName = view.findViewById(R.id.etEditName)
        etEmail = view.findViewById(R.id.etEditEmail)
        etNewPassword = view.findViewById(R.id.etEditNewPassword)
        etNewPasswordRepeat = view.findViewById(R.id.etEditNewPasswordRepeat)
        etCurrentPassword = view.findViewById(R.id.etEditCurrentPassword)
        btnSave = view.findViewById(R.id.btnSaveProfile)
        val btnSendResetEmail = view.findViewById<Button>(R.id.btnSendResetEmail)

        val user = auth.currentUser
        if (user == null) {
            btnSave.isEnabled = false
            return view
        }

        // Google ile giriş yapanların uygulamada şifresi yoktur; e-posta/şifre alanları gizlenir
        if (!hasPasswordLogin(user)) {
            view.findViewById<View>(R.id.layoutCredentials).visibility = View.GONE
            btnSendResetEmail.visibility = View.GONE
        }

        etEmail.setText(user.email)
        loadName(user.uid)

        btnSave.setOnClickListener { saveChanges(user) }
        btnSendResetEmail.setOnClickListener { sendResetEmail(user) }

        return view
    }

    private fun hasPasswordLogin(user: FirebaseUser) =
        user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }

    private fun loadName(uid: String) {
        db.collection("users").document(uid).get().addOnSuccessListener { document ->
            originalName = document.getString("fullName") ?: ""
            if (isAdded) etName.setText(originalName)
        }
    }

    private fun saveChanges(user: FirebaseUser) {
        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val newPassword = etNewPassword.text.toString()
        val newPasswordRepeat = etNewPasswordRepeat.text.toString()
        val currentPassword = etCurrentPassword.text.toString()

        val nameChanged = name != originalName
        val emailChanged = hasPasswordLogin(user) && !email.equals(user.email, ignoreCase = true)
        val passwordChanged = newPassword.isNotEmpty()

        // 1. Alan kontrolleri (kayıt ekranındaki kurallarla aynı)
        if (name.isEmpty()) {
            etName.error = "İsim alanı boş bırakılamaz"
            return
        }
        if (!name.matches("^[a-zA-ZçğıöşüÇĞİÖŞÜ ]+$".toRegex())) {
            etName.error = "İsim rakam veya özel sembol içeremez"
            return
        }
        if (emailChanged && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Geçersiz e-posta adresi"
            return
        }
        if (passwordChanged && newPassword.length < 6) {
            etNewPassword.error = "Şifre en az 6 karakter olmalıdır"
            return
        }
        if (passwordChanged && newPassword != newPasswordRepeat) {
            etNewPasswordRepeat.error = "Şifreler eşleşmiyor"
            return
        }
        if ((emailChanged || passwordChanged) && currentPassword.isEmpty()) {
            etCurrentPassword.error = "Bu değişiklik için mevcut şifrenizi girin"
            return
        }
        if (!nameChanged && !emailChanged && !passwordChanged) {
            toast("Değişiklik yapılmadı")
            return
        }

        btnSave.isEnabled = false

        // 2. E-posta/şifre değişikliği için Firebase yakın zamanda giriş ister: önce yeniden doğrula
        val reauth: Task<Void> = if (emailChanged || passwordChanged) {
            user.reauthenticate(EmailAuthProvider.getCredential(user.email ?: "", currentPassword))
        } else {
            Tasks.forResult(null)
        }

        reauth
            .continueWithTask { task ->
                // Yeniden doğrulama başarısızsa (ör. yanlış şifre) hiçbir şey güncellenmez
                if (!task.isSuccessful) return@continueWithTask Tasks.forException<Void>(task.exception!!)
                val updates = mutableListOf<Task<*>>()
                if (nameChanged) {
                    updates += db.collection("users").document(user.uid).update("fullName", name)
                    updates += user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
                }
                if (passwordChanged) updates += user.updatePassword(newPassword)
                // Yeni adrese doğrulama e-postası gider; bağlantıya tıklanınca e-posta değişir
                if (emailChanged) updates += user.verifyBeforeUpdateEmail(email)
                Tasks.whenAll(updates)
            }
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                btnSave.isEnabled = true
                originalName = name
                clearPasswordFields()

                val message = if (emailChanged) {
                    "Bilgiler kaydedildi. E-postanızın değişmesi için $email adresine gelen bağlantıya tıklayın."
                } else {
                    "Bilgileriniz güncellendi."
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                btnSave.isEnabled = true
                // Tasks.whenAll asıl hatayı ExecutionException içine sarar; içteki hatayı çıkar
                val cause = if (e is ExecutionException && e.cause is Exception) e.cause else e
                val message = when (cause) {
                    is FirebaseAuthInvalidCredentialsException -> "Mevcut şifre hatalı."
                    is FirebaseAuthUserCollisionException -> "Bu e-posta adresi zaten kullanımda."
                    else -> "Güncellenemedi: ${cause?.localizedMessage}"
                }
                toast(message)
            }
    }

    private fun sendResetEmail(user: FirebaseUser) {
        val email = user.email ?: return
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener { toast("Şifre sıfırlama bağlantısı $email adresine gönderildi.") }
            .addOnFailureListener { e -> toast("Gönderilemedi: ${e.localizedMessage}") }
    }

    private fun clearPasswordFields() {
        etNewPassword.text?.clear()
        etNewPasswordRepeat.text?.clear()
        etCurrentPassword.text?.clear()
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
