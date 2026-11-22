package com.example.shingu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : BaseActivity() {

    private var userListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        setupInsets(bottomNavigationView)

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
        // Kullanıcı belgesi canlı dinlenir: admin hesabı dondurur/silerse açık oturum hemen kapatılır
        userListener = FirebaseFirestore.getInstance().collection("users").document(uid)
            .addSnapshotListener { document, _ ->
                if (document == null || isFinishing) return@addSnapshotListener
                val status = AccountStatus.fromCode(document.getString("status"))
                if (status != AccountStatus.ACTIVE) {
                    signOutBlockedUser(status)
                    return@addSnapshotListener
                }

                if (document.getString("role") != "admin" || UserSession.isAdmin) return@addSnapshotListener
                UserSession.isAdmin = true

                // Sepet sekmesinin ikonunu sipariş ikonuyla değiştir
                bottomNavigationView.menu.findItem(R.id.nav_cart)?.setIcon(R.drawable.ic_receipt_24)

                // Rol yüklenmeden önce açılmış sekme kullanıcı görünümüyle çizilmişti (Sepete Ekle butonları,
                // satışta olmayan ürünlerin gizlenmesi); kullanıcı alt sayfalara girmediyse admin görünümüyle yenilenir
                // (isStateSaved: uygulama arka plandaysa ekran değişikliği yapılamaz, çökme olmasın)
                if (supportFragmentManager.backStackEntryCount == 0 && !supportFragmentManager.isStateSaved) {
                    when (bottomNavigationView.selectedItemId) {
                        R.id.nav_home -> loadFragment(HomeFragment())
                        R.id.nav_categories -> loadFragment(CategoriesFragment())
                        R.id.nav_favorites -> loadFragment(FavoritesFragment())
                        R.id.nav_cart -> loadFragment(AdminOrdersFragment())
                    }
                }
            }
    }

    private fun signOutBlockedUser(status: AccountStatus) {
        val message = if (status == AccountStatus.DELETED) "Hesabınız silinmiştir." else "Hesabınız dondurulmuştur."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        userListener?.remove()
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        userListener?.remove()
    }

    // Sayfalar durum çubuğunun altından başlar; alt çubuk gezinme çubuğu boşluğunu kendisi ekler.
    // Klavye açılınca alt çubuk gizlenir ve sayfa klavyenin üstünde biter (yazılan alan görünür kalır).
    private fun setupInsets(bottomNav: BottomNavigationView) {
        val navDivider = findViewById<View>(R.id.navDivider)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragmentContainer)) { container, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            bottomNav.isVisible = !keyboardOpen
            navDivider.isVisible = !keyboardOpen
            container.updatePadding(
                top = bars.top,
                bottom = if (keyboardOpen) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            )
            insets
        }
    }

    private fun loadFragment(fragment: Fragment) {
        // Sekme değişince önceki sekmenin alt sayfa geçmişi temizlenir; aksi halde geri tuşu
        // başka bir sekmenin eski ekranına dönüyordu
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}