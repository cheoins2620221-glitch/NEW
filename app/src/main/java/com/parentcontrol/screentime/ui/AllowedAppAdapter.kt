package com.parentcontrol.screentime.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.parentcontrol.screentime.data.AllowedAppEntity

class AllowedAppAdapter(
    private val onDelete: (AllowedAppEntity) -> Unit
) : RecyclerView.Adapter<AllowedAppAdapter.ViewHolder>() {

    private var items: List<AllowedAppEntity> = emptyList()

    fun submitList(newItems: List<AllowedAppEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(2001)
        val subtitle: TextView = view.findViewById(2002)
        val deleteBtn: Button = view.findViewById(2003)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 20)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(context).apply { id = 2001; textSize = 15f }
        val subtitle = TextView(context).apply { id = 2002; textSize = 12f }
        textColumn.addView(title)
        textColumn.addView(subtitle)
        val deleteBtn = Button(context).apply { id = 2003; text = "삭제" }
        row.addView(textColumn)
        row.addView(deleteBtn)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = "${item.appLabel} (${item.packageName})"
        holder.subtitle.text = if (item.isSystemDefault) "자동 등록 (전화/문자)" else "직접 추가함"
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size
}
