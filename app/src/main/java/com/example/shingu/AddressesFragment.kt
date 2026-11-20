package com.example.shingu

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration

// Profil > Adreslerim: kayıtlı teslimat adresleri; "+ Yeni Adres" haritadan seçim ekranını açar
class AddressesFragment : Fragment() {

    private var addressesListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Yönetim listeleriyle aynı düzen: başlık + yeni ekle butonu + liste
        val view = inflater.inflate(R.layout.fragment_admin_products, container, false)
        view.findViewById<TextView>(R.id.tvAdminListTitle).text = "Adreslerim"
        val tvEmpty = view.findViewById<TextView>(R.id.tvAdminProductsEmpty)
        tvEmpty.text = "Henüz kayıtlı adresiniz yok"
        view.findViewById<Button>(R.id.btnNewProduct).apply {
            text = "+ Yeni Adres"
            setOnClickListener { openMapPicker(parentFragmentManager) }
        }

        val adapter = AddressAdapter { address -> confirmDelete(address) }
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerAdminProducts)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        addressesListener = AddressRepository.listenAddresses(
            onChange = { addresses ->
                if (!isAdded) return@listenAddresses
                adapter.submitList(addresses)
                tvEmpty.visibility = if (addresses.isEmpty()) View.VISIBLE else View.GONE
            },
            onFailure = { e ->
                if (isAdded) Toast.makeText(requireContext(), "Adresler yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )

        return view
    }

    private fun confirmDelete(address: Address) {
        AlertDialog.Builder(requireContext())
            .setTitle("Adresi Sil")
            .setMessage("\"${address.title}\" adresi silinecek. Emin misiniz?")
            .setPositiveButton("Sil") { _, _ -> AddressRepository.deleteAddress(address.id) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        addressesListener?.remove()
        addressesListener = null
    }

    companion object {
        // Haritadan adres seçme ekranını açar (Adreslerim ve Sepet ortak kullanır)
        fun openMapPicker(fragmentManager: FragmentManager) {
            fragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, MapPickerFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}

class AddressAdapter(
    private val onDelete: (Address) -> Unit
) : RecyclerView.Adapter<AddressAdapter.ViewHolder>() {

    private var addresses: List<Address> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvAddressTitle)
        val tvLine: TextView = view.findViewById(R.id.tvAddressLine)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAddress)
    }

    fun submitList(newAddresses: List<Address>) {
        addresses = newAddresses
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_address, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val address = addresses[position]
        holder.tvTitle.text = address.title
        holder.tvLine.text = address.oneLine
        holder.btnDelete.setOnClickListener { onDelete(address) }
    }

    override fun getItemCount() = addresses.size
}
