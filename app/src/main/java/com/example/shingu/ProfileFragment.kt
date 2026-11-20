package com.example.shingu

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var tvProfileName: TextView
    private lateinit var btnAdminPanel: Button
    private lateinit var btnAddCategory: Button
    private lateinit var btnAdminUsers: Button
    private lateinit var btnAdminCampaigns: Button

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

        val btnSpecialDays = view.findViewById<LinearLayout>(R.id.btnSpecialDays)
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

        btnSpecialDays.setOnClickListener {
            Toast.makeText(requireContext(), "Özel Gün Hatırlatıcı Yakında!", Toast.LENGTH_SHORT).show()
        }

        btnSuspendAccount.setOnClickListener {
            showSuspendDialog()
        }

        btnLogout.setOnClickListener {
            showLogoutDialog()
        }

        return view
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