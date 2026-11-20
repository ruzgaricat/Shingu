package com.example.shingu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

// Ana sayfa kampanya slaytı (ViewPager2) için banner adaptörü
class CampaignBannerAdapter(
    private val onClick: (Campaign) -> Unit
) : RecyclerView.Adapter<CampaignBannerAdapter.ViewHolder>() {

    private var campaigns: List<Campaign> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivImage: ImageView = view.findViewById(R.id.ivBannerImage)
        private val tvTitle: TextView = view.findViewById(R.id.tvBannerTitle)

        // Admin formundaki canlı önizleme de aynı bağlama kodunu kullanır
        fun bind(title: String, imageUrl: String) {
            tvTitle.text = title
            Glide.with(itemView.context)
                .load(imageUrl.ifEmpty { null })
                .placeholder(R.drawable.bg_banner_gradient)
                .error(R.drawable.bg_banner_gradient)
                .into(ivImage)
        }
    }

    fun submitList(newCampaigns: List<Campaign>) {
        campaigns = newCampaigns
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_campaign_banner, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val campaign = campaigns[position]
        holder.bind(campaign.title, campaign.imageUrl)
        holder.itemView.setOnClickListener { onClick(campaign) }
    }

    override fun getItemCount() = campaigns.size
}
