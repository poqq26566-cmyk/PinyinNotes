package com.example.pinyinnotes

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

/** 支持按名称过滤的应用列表适配器 */
class AppListAdapter(
    private val allApps: List<AppEntry>,
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var filtered: List<AppEntry> = allApps

    fun filter(query: String) {
        filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filtered[position], onClick)
    }

    override fun getItemCount(): Int = filtered.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val name: TextView = itemView.findViewById(R.id.tvAppName)

        fun bind(entry: AppEntry, onClick: (AppEntry) -> Unit) {
            icon.setImageDrawable(entry.icon)
            name.text = entry.label
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}
