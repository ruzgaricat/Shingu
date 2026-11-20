package com.example.shingu

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)

        // Rol yüklenene kadar normal kullanıcı varsayılır
        UserSession.isAdmin = false
        loadUserRole(bottomNavigationView)

        // Uygulama ilk açıldığında doğrudan Ana Sayfa ile başlasın
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
            bottomNavigationView.selectedItemId = R.id.nav_home
        }

        // Alt çubuk tıklama yönetimi
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_categories -> {
                    loadFragment(CategoriesFragment())
                    true
                }
                R.id.nav_favorites -> {
                    loadFragment(FavoritesFragment())
                    true
                }
                R.id.nav_cart -> {
                    // Admin alışveriş yapmadığı için sepet yerine sipariş yönetimi açılır
                    loadFragment(if (UserSession.isAdmin) AdminOrdersFragment() else CartFragment())
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadUserRole(bottomNavigationView: BottomNavigationView) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document.getString("role") != "admin") return@addOnSuccessListener
                UserSession.isAdmin = true

                // Sepet sekmesinin ikonunu sipariş ikonuyla değiştir
                bottomNavigationView.menu.findItem(R.id.nav_cart)?.setIcon(R.drawable.ic_receipt_24)

                // Rol yüklenmeden önce sepet sekmesi açıldıysa sipariş ekranına geç
                if (bottomNavigationView.selectedItemId == R.id.nav_cart) {
                    loadFragment(AdminOrdersFragment())
                }
            }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}