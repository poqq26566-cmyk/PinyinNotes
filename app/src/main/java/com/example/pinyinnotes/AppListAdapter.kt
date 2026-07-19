package com.example.pinyinnotes

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil   // ✅ 新增
import androidx.recyclerview.widget.RecyclerView

data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

class AppListAdapter(
    private val allApps: List<AppEntry>,
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var filtered: List<AppEntry> = allApps

    fun filter(query: String) {
        val newList = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
        // ✅ 修复6：用 DiffUtil 做增量刷新，搜索时不再全量重绘
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = filtered.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                filtered[oldPos].packageName == newList[newPos].packageName
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                filtered[oldPos] == newList[newPos]
        })
        filtered = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filtered[position], onClick)
    }

    override fun getItemCount(): Int = filtered.size

    // ✅ 修复7：设置稳定 ID，帮助 RecyclerView 复用 ViewHolder
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = filtered[position].packageName.hashCode().toLong()

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
