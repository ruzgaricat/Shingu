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
import com.google.firebase.firestore.ListenerRegistration

// Admin kampanya yönetimi: ana sayfa slaytındaki kampanyaları listeler; dokununca düzenleme formu açılır
class AdminCampaignsFragment : Fragment() {

    private var campaignsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Ürün/kategori/kullanıcı yönetimiyle aynı liste düzeni
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        view.findViewById<TextView>(R.id.tvAdminListTitle).text = "Yönetici: Kampanyalar"
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Henüz kampanya yok"
        view.findViewById<Button>(R.id.btnNewProduct).apply {
            text = "+ Yeni Kampanya"
            setOnClickListener { openForm(null) }
        }

        val adapter = AdminCampaignAdapter { campaign -> openForm(campaign.id) }
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAdminProducts)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        campaignsListener = CampaignRepository.listenCampaigns(
            onChange = { campaigns ->
                if (!isAdded) return@listenCampaigns
                adapter.submitList(campaigns)
                tvEmpty.visibility = if (campaigns.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Kampanyalar yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    private fun openForm(campaignId: String?) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AdminCampaignFormFragment.newInstance(campaignId))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        campaignsListener?.remove()
        campaignsListener = null
    }
}

class AdminCampaignAdapter(
    private val onClick: (Campaign) -> Unit
) : RecyclerView.Adapter<AdminCampaignAdapter.ViewHolder>() {

    private var campaigns: List<Campaign> = emptyList()

    // Ürün yönetimindeki satır tasarımı (item_admin_product) yeniden kullanılır
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.ivAdminProductImage)
        val tvTitle: TextView = view.findViewById(R.id.tvAdminProductName)
        val tvCategory: TextView = view.findViewById(R.id.tvAdminProductPrice)
        val tvStatus: TextView = view.findViewById(R.id.tvAdminProductStatus)
    }

    fun submitList(newCampaigns: List<Campaign>) {
        campaigns = newCampaigns
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val campaign = campaigns[position]
        holder.tvTitle.text = campaign.title
        holder.tvCategory.text = "Kategori: ${campaign.categoryName}"
        holder.tvCategory.setTextColor(Color.parseColor("#6B5873"))
        holder.tvStatus.visibility = View.GONE

        Glide.with(holder.itemView.context)
            .load(campaign.imageUrl)
            .placeholder(R.drawable.ic_placeholder_sakura)
            .into(holder.ivImage)

        holder.itemView.setOnClickListener { onClick(campaign) }
    }

    override fun getItemCount() = campaigns.size
}
