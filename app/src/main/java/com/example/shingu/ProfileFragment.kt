package com.example.shingu

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.firestore.FirebaseFirestore

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var tvProfileName: TextView
    private lateinit var btnAdminPanel: Button
    private lateinit var btnAddCategory: Button
    private lateinit var btnAdminUsers: Button
    private lateinit var btnAdminCampaigns: Button
    private lateinit var btnAdminCoupons: Button
    private lateinit var btnAdminDashboard: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        tvProfileName = view.findViewById(R.id.tvProfileName)
        btnAdminPanel = view.findViewById(R.id.btnAdminPanel)
        btnAddCategory = view.findViewById(R.id.btnAdminAddCategory)
        btnAdminUsers = view.findViewById(R.id.btnAdminUsers)
        btnAdminCampaigns = view.findViewById(R.id.btnAdminCampaigns)

        btnAdminCoupons = view.findViewById(R.id.btnAdminCoupons)
        btnAdminDashboard = view.findViewById(R.id.btnAdminDashboard)
        val btnSuspendAccount = view.findViewById<LinearLayout>(R.id.btnSuspendAccount)
        val btnLogout = view.findViewById<LinearLayout>(R.id.btnLogout)

        // Giriş yapan kullanıcının adını ve rolünü Firestore'dan çekelim
        loadUserProfile()

        // Ürün Yönetimi Paneline Git (listele, ekle, düzenle, sil)
        btnAdminPanel.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AdminProductsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Kullanıcı Yönetimi Paneline Git (listele, düzenle, dondur, sil)
        btnAdminUsers.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AdminUsersFragment())
                .addToBackStack(null)
                .commit()
        }

        // Kampanya Yönetimi Paneline Git (ana sayfa slaytı)
        btnAdminCampaigns.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AdminCampaignsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Kategori Yönetimi Paneline Git (listele, ekle, düzenle, sil)
        btnAddCategory.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AdminCategoriesFragment())
                .addToBackStack(null)
                .commit()
        }

        // Üyelik Bilgilerim: ad-soyad, e-posta ve şifre güncelleme
        view.findViewById<View>(R.id.cardProfileInfo).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, EditProfileFragment())
                .addToBackStack(null)
                .commit()
        }

        // Siparişlerim: kullanıcı kendi siparişlerini, admin tüm siparişleri görür
        view.findViewById<LinearLayout>(R.id.btnOrders).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, if (UserSession.isAdmin) AdminOrdersFragment() else MyOrdersFragment())
                .addToBackStack(null)
                .commit()
        }

        // Ürün Yorumlarım: kullanıcının yazdığı tüm yorumlar
        view.findViewById<LinearLayout>(R.id.btnReviews).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, MyReviewsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Adreslerim: kayıtlı teslimat adresleri ve haritadan yeni adres ekleme
        view.findViewById<LinearLayout>(R.id.btnAddresses).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AddressesFragment())
                .addToBackStack(null)
                .commit()
        }

        // Listelerim: favori ürünler listesi
        view.findViewById<LinearLayout>(R.id.btnLists).setOnClickListener { open(FavoritesFragment()) }
        // Kuponlarım: kullanılabilir kuponlar (dokununca kod kopyalanır)
        view.findViewById<LinearLayout>(R.id.btnCoupons).setOnClickListener { open(UserCouponsFragment()) }
        // Yardım & Destek: sık sorulan sorular, oradan geri bildirim formuna geçilebilir
        view.findViewById<LinearLayout>(R.id.btnHelp).setOnClickListener { FeedbackFragment.showHelpDialog(this) }
        // Geri Bildirim Formu: mesaj admin'e iletilir
        view.findViewById<LinearLayout>(R.id.btnFeedback).setOnClickListener { open(FeedbackFragment()) }

        // Yönetici: Kuponlar ve Satış Özeti
        btnAdminCoupons.setOnClickListener { open(AdminCouponsFragment()) }
        btnAdminDashboard.setOnClickListener { open(AdminDashboardFragment()) }

        btnSuspendAccount.setOnClickListener {
            showSuspendDialog()
        }

        // Hesabımı Sil: hesap ve kişisel veriler kalıcı olarak silinir
        view.findViewById<LinearLayout>(R.id.btnDeleteAccount).setOnClickListener { showDeleteAccountDialog() }

        // Gizlilik Politikası: uygulama dışındaki web sayfası tarayıcıda açılır
        view.findViewById<LinearLayout>(R.id.btnPrivacyPolicy).setOnClickListener { LegalTexts.openPrivacyPolicy(requireContext()) }

        btnLogout.setOnClickListener {
            showLogoutDialog()
        }

        return view
    }

    private fun open(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val fullName = document.getString("fullName") ?: "Kullanıcı"
                        tvProfileName.text = fullName

                        // Rol kontrolü: Admin ise admin butonlarını görünür yap, değilse gizle
                        val role = document.getString("role")
                        val adminVisibility = if (role == "admin") View.VISIBLE else View.GONE
                        btnAdminPanel.visibility = adminVisibility
                        btnAddCategory.visibility = adminVisibility
                        btnAdminUsers.visibility = adminVisibility
                        btnAdminCampaigns.visibility = adminVisibility
                        btnAdminCoupons.visibility = adminVisibility
                        btnAdminDashboard.visibility = adminVisibility
                    }
                }
                .addOnFailureListener {
                    tvProfileName.text = auth.currentUser?.displayName ?: "Kullanıcı"
                }
        }
    }

    private fun showSuspendDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Hesabı Askıya Al")
            .setMessage("Hesabınızı askıya almak istediğinizden emin misiniz?")
            .setPositiveButton("Evet, Askıya Al") { _, _ -> suspendUserAccount() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // Hesap silme onayı. E-posta/şifre ile giren kullanıcıdan güvenlik için şifresi istenir.
    private fun showDeleteAccountDialog() {
        if (UserSession.isAdmin) {
            Toast.makeText(requireContext(), "Yönetici hesabı uygulama içinden silinemez.", Toast.LENGTH_LONG).show()
            return
        }
        val user = auth.currentUser ?: return
        val needsPassword = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }

        val padding = (20 * resources.displayMetrics.density).toInt()
        val etPassword = EditText(requireContext()).apply {
            hint = "Şifreniz"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            if (needsPassword) addView(etPassword)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Hesabımı Sil")
            .setMessage(
                "Hesabınız ve kişisel verileriniz (profil, sepet, favoriler, adresler, yorumlar) kalıcı olarak silinecek. " +
                    "Verdiğiniz siparişler yasal kayıt amacıyla saklanır.\n\nBu işlem geri alınamaz." +
                    if (needsPassword) "\n\nOnaylamak için şifrenizi girin:" else ""
            )
            .setView(container)
            .setPositiveButton("Kalıcı Olarak Sil", null) // Hatalı şifrede pencere kapanmasın diye aşağıda bağlanıyor
            .setNegativeButton("Vazgeç", null)
            .create()

        dialog.setOnShowListener {
            val btnDelete = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnDelete.setTextColor(Color.parseColor("#C62828"))
            btnDelete.setOnClickListener {
                val password = etPassword.text.toString()
                if (needsPassword && password.isEmpty()) {
                    etPassword.error = "Şifrenizi girin"
                    return@setOnClickListener
                }
                btnDelete.isEnabled = false
                UserRepository.deleteOwnAccount(if (needsPassword) password else null,
                    onSuccess = {
                        dialog.dismiss()
                        if (!isAdded) return@deleteOwnAccount
                        Toast.makeText(requireContext(), "Hesabınız silindi.", Toast.LENGTH_LONG).show()
                        performLogout()
                    },
                    onFailure = { e ->
                        btnDelete.isEnabled = true
                        val message = if (e is FirebaseAuthInvalidCredentialsException) "Şifre hatalı." else e.localizedMessage
                        if (isAdded) Toast.makeText(requireContext(), "Hesap silinemedi: $message", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
        dialog.show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Çıkış Yap")
            .setMessage("Çıkış yapmak istediğinize emin misiniz?")
            .setPositiveButton("Evet, Çıkış Yap") { _, _ -> performLogout() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun suspendUserAccount() {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid)
                .update("status", "suspended")
                // MainActivity durumu canlı dinlediği için genelde oturumu o kapatır; ekran hâlâ açıksa burada kapatılır
                .addOnSuccessListener { if (isAdded) performLogout() }
        }
    }

    private fun performLogout() {
        auth.signOut()
        val intent = Intent(requireActivity(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }
}