package com.example.shingu

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

// Admin kategori yönetimi: tüm kategorileri ürün sayılarıyla listeler, dokununca düzenleme formu açılır.
// Ürün yönetimiyle aynı liste ve satır tasarımını kullanır.
class AdminCategoriesFragment : Fragment() {

    private val db = FirebaseFirestore.getInstance()
    private var categoriesListener: ListenerRegistration? = null

    data class CategoryRow(val id: String, val name: String, val imageUrl: String, val productCount: Int)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)

        view.findViewById<TextView>(R.id.tvAdminListTitle).text = "Yönetici: Kategoriler"
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Henüz kategori yok"
        val btnNew = view.findViewById<Button>(R.id.btnNewProduct)
        btnNew.text = "+ Yeni Kategori"
        btnNew.setOnClickListener { openForm(null) }

        val adapter = AdminCategoryAdapter { row -> openForm(row.id) }
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAdminProducts)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Kategoriler canlı dinlenir; her değişiklikte ürün sayıları da yeniden hesaplanır
        categoriesListener = db.collection("categories")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (!isAdded) return@addSnapshotListener
                if (error != null) {
                    Toast.makeText(requireContext(), "Kategoriler yüklenemedi: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                val categories = snapshot?.documents.orEmpty()
                tvEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE

                db.collection("products").get().addOnSuccessListener { products ->
                    if (!isAdded) return@addOnSuccessListener
                    // Her kategori adının kaç üründe geçtiğini say
                    val counts = products.documents
                        .flatMap { (it.get("categories") as? List<*>).orEmpty() }
                        .groupingBy { it }
                        .eachCount()
                    adapter.submitList(categories.map { doc ->
                        val name = doc.getString("name") ?: ""
                        CategoryRow(doc.id, name, doc.getString("imageUrl") ?: "", counts[name] ?: 0)
                    })
                }
            }

        return view
    }

    private fun openForm(categoryId: String?) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AdminAddCategoryFragment.newInstance(categoryId))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        categoriesListener?.remove()
        categoriesListener = null
    }
}

class AdminCategoryAdapter(
    private val onClick: (AdminCategoriesFragment.CategoryRow) -> Unit
) : RecyclerView.Adapter<AdminCategoryAdapter.ViewHolder>() {

    private var rows: List<AdminCategoriesFragment.CategoryRow> = emptyList()

    // Ürün yönetimindeki satır tasarımı (item_admin_product) yeniden kullanılır
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvName: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvCount: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvStatus: TextView = view.findViewById(R.id.tvAdminProductStatus)
    }

    fun submitList(newRows: List<AdminCategoriesFragment.CategoryRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.tvName.text = row.name
        holder.tvCount.text = if (row.productCount > 0) "${row.productCount} ürün" else "Ürün yok"
        holder.tvCount.setTextColor(Color.parseColor("#9A88A3"))
        holder.tvStatus.visibility = View.GONE

        Glide.with(holder.itemView.context)
            .load(row.imageUrl)
            .placeholder(R.drawable.ic_placeholder_sakura)
            .into(holder.ivImage)

        holder.itemView.setOnClickListener { onClick(row) }
    }

    override fun getItemCount() = rows.size
}
