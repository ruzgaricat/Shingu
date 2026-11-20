package com.example.shingu

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Kullanıcı geri bildirimi (Firestore: feedback/{id})
data class Feedback(
    val id: String,
    val userEmail: String,
    val subject: String,
    val message: String,
    val createdAt: Long,
    val read: Boolean
) {
    companion object {
        val SUBJECTS = listOf("Öneri", "Şikayet", "Hata bildirimi", "Sipariş / teslimat", "Diğer")

        fun fromDocument(doc: DocumentSnapshot) = Feedback(
            id = doc.id,
            userEmail = doc.getString("userEmail") ?: "",
            subject = doc.getString("subject") ?: "",
            message = doc.getString("message") ?: "",
            createdAt = doc.getLong("createdAt") ?: 0L,
            read = doc.getBoolean("read") ?: false
        )
    }
}

// Profil > Geri Bildirim Formu: konu + mesaj gönderilir, admin "Satış Özeti > Geri Bildirimler"den okur
class FeedbackFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_feedback, container, false)
        val actvSubject = view.findViewById<AutoCompleteTextView>(R.id.actvFeedbackSubject)
        val etMessage = view.findViewById<TextInputEditText>(R.id.etFeedbackMessage)
        val btnSend = view.findViewById<Button>(R.id.btnSendFeedback)

        actvSubject.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, Feedback.SUBJECTS))
        actvSubject.setText(Feedback.SUBJECTS.first(), false)

        btnSend.setOnClickListener {
            val message = etMessage.text.toString().trim()
            if (message.length < 10) {
                etMessage.error = "Lütfen en az 10 karakterlik bir mesaj yazın"
                return@setOnClickListener
            }
            val user = FirebaseAuth.getInstance().currentUser ?: return@setOnClickListener
            btnSend.isEnabled = false
            FirebaseFirestore.getInstance().collection("feedback").add(
                mapOf(
                    "userId" to user.uid,
                    "userEmail" to (user.email ?: ""),
                    "subject" to actvSubject.text.toString(),
                    "message" to message,
                    "read" to false,
                    "createdAt" to System.currentTimeMillis()
                )
            ).addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                Toast.makeText(requireContext(), "Teşekkürler! Geri bildiriminiz iletildi.", Toast.LENGTH_LONG).show()
                parentFragmentManager.popBackStack()
            }.addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                btnSend.isEnabled = true
                Toast.makeText(requireContext(), "Gönderilemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        return view
    }

    companion object {
        // Profil > Yardım & Destek: sık sorulan sorular; buradan geri bildirim formuna geçilebilir
        fun showHelpDialog(fragment: Fragment) {
            val faq = """
                Siparişimi nasıl takip ederim?
                Profil > Siparişlerim'den siparişinize dokunarak durum geçmişini görebilirsiniz.

                Siparişimi iptal edebilir miyim?
                Sipariş "Beklemede" durumundayken sipariş detayındaki "Siparişi İptal Et" butonuyla iptal edebilirsiniz. İşleme alınan siparişler için bize geri bildirim formundan ulaşın.

                Kargo ücreti ne kadar?
                Standart kargo ${OrderPricing.format(ShopConfig.SHIPPING_FEE)}; ${OrderPricing.format(ShopConfig.FREE_SHIPPING_THRESHOLD)} ve üzeri siparişlerde kargo bedava.

                Kupon kodumu nereye yazarım?
                Sepet ekranındaki "Kupon kodu" alanına yazıp "Uygula"ya basın. Size açık kuponları Profil > Kuponlarım'da görebilirsiniz.

                Hangi bölgelere teslimat yapıyorsunuz?
                Şu an yalnızca Türkiye içine teslimat yapıyoruz.

                Kart bilgilerim saklanıyor mu?
                Hayır. Kart numaranızın tamamı ve CVV kaydedilmez; siparişte sadece kart türü ve son 4 hane görünür.
            """.trimIndent()

            AlertDialog.Builder(fragment.requireContext())
                .setTitle("Yardım & Destek")
                .setMessage(faq)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Bize Yazın") { _, _ ->
                    fragment.parentFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, FeedbackFragment())
                        .addToBackStack(null)
                        .commit()
                }
                .show()
        }
    }
}

// Admin: kullanıcı geri bildirimleri. Dokununca mesajın tamamı açılır ve "okundu" işaretlenir.
class AdminFeedbackFragment : Fragment() {

    private var listener: ListenerRegistration? = null
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        val tvTitle = view.findViewById<TextView>(R.id.tvAdminListTitle)
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Henüz geri bildirim yok"
        view.findViewById<Button>(R.id.btnNewProduct).visibility = View.GONE

        val adapter = FeedbackAdapter { feedback -> showFeedback(feedback) }
        view.findViewById<RecyclerView>(R.id.recyclerAdminProducts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        listener = FirebaseFirestore.getInstance().collection("feedback")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (!isAdded) return@addSnapshotListener
                if (error != null) {
                    Toast.makeText(requireContext(), "Yüklenemedi: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.map(Feedback::fromDocument).orEmpty()
                adapter.submitList(list)
                val unread = list.count { !it.read }
                tvTitle.text = "Geri Bildirimler" + if (unread > 0) " ($unread yeni)" else ""
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        return view
    }

    private fun showFeedback(feedback: Feedback) {
        val ref = FirebaseFirestore.getInstance().collection("feedback").document(feedback.id)
        if (!feedback.read) ref.update("read", true)
        AlertDialog.Builder(requireContext())
            .setTitle(feedback.subject)
            .setMessage("${feedback.userEmail}\n${dateFormat.format(Date(feedback.createdAt))}\n\n${feedback.message}")
            .setPositiveButton("Kapat", null)
            .setNegativeButton("Sil") { _, _ -> ref.delete() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener?.remove()
        listener = null
    }
}

class FeedbackAdapter(
    private val onClick: (Feedback) -> Unit
) : RecyclerView.Adapter<FeedbackAdapter.ViewHolder>() {

    private var items: List<Feedback> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))

    // Ürün yönetimindeki satır tasarımı (item_admin_product) yeniden kullanılır
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvSubject: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvPreview: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvInfo: TextView = view.findViewById(R.id.tvAdminProductStatus)
        val tvAction: TextView = view.findViewById(R.id.tvAdminRowAction)
    }

    fun submitList(newItems: List<Feedback>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.ivIcon.setImageResource(android.R.drawable.ic_dialog_email)
        holder.tvSubject.text = (if (item.read) "" else "● ") + item.subject
        holder.tvPreview.text = item.message
        holder.tvPreview.setTextColor(Color.parseColor("#555555"))
        holder.tvInfo.text = "${item.userEmail}  ·  ${dateFormat.format(Date(item.createdAt))}"
        holder.tvInfo.setTextColor(Color.parseColor("#777777"))
        holder.tvAction.text = "Oku >"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
