package com.example.shingu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.shingu.model.CategoryModel

class CategoryAdapter(
    private val categoryList: List<CategoryModel>,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvCategoryName: TextView = itemView.findViewById(R.id.tvCategoryName)
        val ivCategory: ImageView = itemView.findViewById(R.id.ivCategory) // XML'deki ImageView ID'nize göre değiştirebilirsiniz
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        // Kategori öğesi için tasarladığın XML layout dosyasını buraya bağla (Örn: item_category)
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categoryList[position]
        holder.tvCategoryName.text = category.name

        // Glide ile kategori görselini yüklüyoruz
        Glide.with(holder.itemView.context)
            .load(category.imageUrl)
            .placeholder(R.drawable.ic_launcher_foreground) // Varsayılan yüklenme görseli
            .into(holder.ivCategory)

        holder.itemView.setOnClickListener {
            onCategoryClick(category.name)
        }
    }

    override fun getItemCount(): Int = categoryList.size
}