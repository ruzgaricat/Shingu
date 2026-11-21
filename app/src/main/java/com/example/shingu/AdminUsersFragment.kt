package com.example.shingu

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration

// Admin kullanıcı yönetimi: tüm kullanıcıları listeler; dokunulan kullanıcı için
// bilgileri düzenleme, hesabı dondurma/aktifleştirme ve silme işlemleri sunulur.
class AdminUsersFragment : Fragment() {

    private var usersListener: ListenerRegistration? = null
    private val currentUid get() = FirebaseAuth.getInstance().currentUser?.uid

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Ürün/kategori yönetimiyle aynı liste düzeni; kullanıcılar uygulamadan kayıt olduğu için "yeni" butonu yok
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        val tvTitle = view.findViewById<TextView>(R.id.tvAdminListTitle)
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Kullanıcı bulunamadı"
        view.findViewById<Button>(R.id.btnNewProduct).visibility = View.GONE

        val adapter = AdminUserAdapter(currentUid) { user -> showActions(user) }
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAdminProducts)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        usersListener = UserRepository.listenAllUsers(
            onChange = { users ->
                if (!isAdded) return@listenAllUsers
                // Silinmiş hesaplar listede gösterilmez (kalıcı silme admin scriptiyle yapılır)
                val visible = users.filter { it.status != AccountStatus.DELETED }
                val suspendedCount = visible.count { it.status == AccountStatus.SUSPENDED }
                tvTitle.text = "Yönetici: Kullanıcılar (${visible.size})" +
                    if (suspendedCount > 0) " · $suspendedCount donduruldu" else ""
                adapter.submitList(visible)
                tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Kullanıcılar yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    private fun showActions(user: UserInfo) {
        // Admin kendi rolünü/durumunu buradan değiştiremez (kendini kilitlemesin diye); kendi profiline yönlendirilir
        if (user.uid == currentUid) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, EditProfileFragment())
                .addToBackStack(null)
                .commit()
            return
        }

        val isSuspended = user.status == AccountStatus.SUSPENDED
        showActionDialog(
            user.fullName.ifEmpty { user.email },
            listOf(
                DialogAction("Bilgileri Düzenle") { showEditDialog(user) },
                DialogAction(if (isSuspended) "Hesabı Aktifleştir" else "Hesabı Dondur") {
                    confirmStatusChange(user, if (isSuspended) AccountStatus.ACTIVE else AccountStatus.SUSPENDED)
                },
                DialogAction("Hesabı Sil", destructive = true) { confirmDelete(user) }
            )
        )
    }

    // Ad-soyad ve rol düzenleme penceresi
    private fun showEditDialog(user: UserInfo) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val etName = EditText(requireContext()).apply {
            hint = "Ad Soyad"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(user.fullName)
        }
        val rbUser = RadioButton(requireContext()).apply { id = View.generateViewId(); text = "Kullanıcı" }
        val rbAdmin = RadioButton(requireContext()).apply { id = View.generateViewId(); text = "Admin" }
        val rgRole = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbUser)
            addView(rbAdmin)
            check(if (user.isAdmin) rbAdmin.id else rbUser.id)
        }
        val tvEmail = TextView(requireContext()).apply {
            text = "E-posta: ${user.email}\n(E-postayı sadece kullanıcının kendisi değiştirebilir.)"
            setTextColor(Color.parseColor("#777777"))
            textSize = 12f
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(etName)
            addView(rgRole)
            addView(tvEmail)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Kullanıcıyı Düzenle")
            .setView(container)
            .setPositiveButton("Kaydet", null) // Hatalı girişte pencere kapanmasın diye aşağıda bağlanıyor
            .setNegativeButton("Vazgeç", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (!name.matches("^[a-zA-ZçğıöşüÇĞİÖŞÜ ]+$".toRegex())) {
                    etName.error = "Geçerli bir ad soyad girin"
                    return@setOnClickListener
                }
                val role = if (rgRole.checkedRadioButtonId == rbAdmin.id) "admin" else "user"
                UserRepository.updateUser(user.uid, name, role,
                    onSuccess = {
                        dialog.dismiss()
                        toast("Kullanıcı güncellendi")
                    },
                    onFailure = { e -> toast("Güncellenemedi: ${e.localizedMessage}") }
                )
            }
        }
        dialog.show()
    }

    private fun confirmStatusChange(user: UserInfo, newStatus: AccountStatus) {
        val message = if (newStatus == AccountStatus.SUSPENDED) {
            "${user.email} hesabı dondurulacak. Kullanıcı giriş yapamayacak ve açık oturumu kapatılacak."
        } else {
            "${user.email} hesabı tekrar aktif hale getirilecek."
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (newStatus == AccountStatus.SUSPENDED) "Hesabı Dondur" else "Hesabı Aktifleştir")
            .setMessage(message)
            .setPositiveButton("Onayla") { _, _ ->
                UserRepository.setStatus(user.uid, newStatus,
                    onSuccess = { toast("Hesap durumu: ${newStatus.label}") },
                    onFailure = { e -> toast("Değiştirilemedi: ${e.localizedMessage}") }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun confirmDelete(user: UserInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Hesabı Sil")
            .setMessage(
                "${user.email} hesabı silinecek.\n\n" +
                    "Kullanıcının sepeti, favorileri, adresleri ve yorumları kalıcı olarak silinir; " +
                    "kullanıcı bir daha giriş yapamaz. Verdiği siparişler kayıt amacıyla saklanır.\n\n" +
                    "Bu işlem geri alınamaz."
            )
            .setPositiveButton("Sil") { _, _ ->
                UserRepository.deleteUser(user.uid,
                    onSuccess = { toast("Hesap silindi") },
                    onFailure = { e -> toast("Silinemedi: ${e.localizedMessage}") }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        usersListener?.remove()
        usersListener = null
    }
}

class AdminUserAdapter(
    private val currentUid: String?,
    private val onClick: (UserInfo) -> Unit
) : RecyclerView.Adapter<AdminUserAdapter.ViewHolder>() {

    private var users: List<UserInfo> = emptyList()

    // Ürün yönetimindeki satır tasarımı (item_admin_product) yeniden kullanılır
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvName: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvEmail: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvStatus: TextView = view.findViewById(R.id.tvAdminProductStatus)
    }

    fun submitList(newUsers: List<UserInfo>) {
        users = newUsers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        val name = user.fullName.ifEmpty { "(İsimsiz)" }
        holder.tvName.text = if (user.uid == currentUid) "$name (Siz)" else name
        holder.tvEmail.text = user.email
        holder.tvEmail.setTextColor(Color.parseColor("#555555"))

        holder.ivImage.setImageResource(R.drawable.outline_account_box_24)
        holder.ivImage.setColorFilter(Color.parseColor(if (user.isAdmin) "#6750A4" else "#757575"))

        val roleText = if (user.isAdmin) "Admin" else "Kullanıcı"
        holder.tvStatus.visibility = View.VISIBLE
        holder.tvStatus.text = "$roleText  ·  ${user.status.label}"
        holder.tvStatus.setTextColor(
            Color.parseColor(if (user.status == AccountStatus.SUSPENDED) "#E53935" else "#388E3C")
        )

        holder.itemView.setOnClickListener { onClick(user) }
    }

    override fun getItemCount() = users.size
}
