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
import java.util.Locale

// Admin ürün yönetimi: tüm ürünleri listeler, dokununca düzenleme formu açılır
class AdminProductsFragment : Fragment() {

    private var productsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)

        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        val adapter = AdminProductAdapter { product -> openForm(product.id) }
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAdminProducts)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<Button>(R.id.btnNewProduct).setOnClickListener { openForm(null) }

        // Ürünler canlı dinlenir; düzenleme/silme sonrası liste kendiliğinden güncellenir
        productsListener = FirebaseFirestore.getInstance().collection("products")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (!isAdded) return@addSnapshotListener
                if (error != null) {
                    Toast.makeText(requireContext(), "Ürünler yüklenemedi: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                val products = snapshot?.documents?.map { Product.fromDocument(it) } ?: emptyList()
                adapter.submitList(products)
                tvEmpty.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
            }

        return view
    }

    private fun openForm(productId: String?) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AdminAddProductFragment.newInstance(productId))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        productsListener?.remove()
        productsListener = null
    }
}

class AdminProductAdapter(
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<AdminProductAdapter.ViewHolder>() {

    private var products: List<Product> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvName: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvPrice: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvStatus: TextView = view.findViewById(R.id.tvAdminProductStatus)
    }

    fun submitList(newProducts: List<Product>) {
        products = newProducts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = products[position]
        holder.tvName.text = product.name
        holder.tvPrice.text = String.format(Locale("tr", "TR"), "%.2f TL", product.price)

        val stockText = product.stock?.let { "Stok: $it" } ?: "Stok girilmemiş"
        val (saleText, color) = when {
            !product.onSale -> "Satışta değil" to "#9A88A3"
            product.isOutOfStock -> "Tükendi" to "#FF4F8B"
            else -> "Satışta" to "#2FAE80"
        }
        holder.tvStatus.text = "$stockText  ·  $saleText"
        holder.tvStatus.setTextColor(Color.parseColor(color))

        Glide.with(holder.itemView.context)
            .load(product.imageUrls.firstOrNull())
            .placeholder(R.drawable.ic_placeholder_sakura)
            .into(holder.ivImage)

        holder.itemView.setOnClickListener { onClick(product) }
    }

    override fun getItemCount() = products.size
}
