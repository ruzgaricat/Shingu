package com.example.shingu

import androidx.appcompat.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

// İşlem menüsündeki tek seçenek. destructive: geri alınamayan işlem (silme vb.), kırmızı gösterilir.
data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

// Seçenekleri düz yazı listesi yerine çerçeveli, tam genişlikte butonlar olarak gösteren işlem menüsü.
// (AlertDialog.setItems ile yapılan listelerde seçeneklerin basılabilir olduğu anlaşılmıyordu.)
fun Fragment.showActionDialog(title: String, actions: List<DialogAction>) {
    val context = requireContext()
    val density = resources.displayMetrics.density
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
    }

    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(container)
        .setNegativeButton("Kapat", null)
        .create()

    actions.forEach { action ->
        val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = action.label
            isAllCaps = false
            textSize = 15f
            cornerRadius = (24 * density).toInt()
            strokeWidth = (1.5f * density).toInt()
            val color = Color.parseColor(if (action.destructive) "#E23D63" else "#E5508A")
            setTextColor(color)
            strokeColor = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * density).toInt()).apply {
                topMargin = (6 * density).toInt()
            }
            setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
        }
        container.addView(button)
    }
    dialog.show()
}
