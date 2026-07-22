package com.example.pinyinnotes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private sealed class ListItem
private data class HeaderItem(val letter: String) : ListItem()
private data class EntryListItem<T : NamedItem>(val entry: T) : ListItem()

/** 通用列表适配器，按拼音首字母分组，用于分类列表和笔记列表 */
class NoteAdapter<T : NamedItem>(
    private val onClick: (T) -> Unit,
    private val onLongClick: (T) -> Unit,
    private val getWordCount: ((T) -> Int)? = null,
    private val getLastModified: ((T) -> Long)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }

    fun submitEntries(entries: List<T>) {
        items.clear()
        var lastLetter: String? = null
        for (entry in entries) {
            val letter = PinyinUtils.getFirstLetter(entry.name)
            if (letter != lastLetter) {
                items.add(HeaderItem(letter))
                lastLetter = letter
            }
            items.add(EntryListItem(entry))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HeaderItem -> TYPE_HEADER
            is EntryListItem<*> -> TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            EntryViewHolder(inflater.inflate(R.layout.item_note, parent, false))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HeaderItem -> (holder as HeaderViewHolder).bind(item.letter)
            is EntryListItem<*> -> (holder as EntryViewHolder).bind(
                item.entry as T,
                onClick,
                onLongClick,
                getWordCount,
                getLastModified
            )
        }
    }

    override fun getItemCount(): Int = items.size

    fun getPositionForLetter(letter: String): Int {
        for (i in items.indices) {
            val item = items[i]
            if (item is HeaderItem && item.letter == letter) {
                return i
            }
        }
        return -1
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvHeader)
        fun bind(letter: String) {
            textView.text = letter
        }
    }

    class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvName)
        private val tvWordCount: TextView = itemView.findViewById(R.id.tvWordCount)

        fun <T : NamedItem> bind(
            entry: T,
            onClick: (T) -> Unit,
            onLongClick: (T) -> Unit,
            getWordCount: ((T) -> Int)?,
            getLastModified: ((T) -> Long)?
        ) {
            textView.text = entry.name

            val countText = if (getWordCount != null) {
                val count = getWordCount(entry)
                if (count > 0) "${count}字" else null
            } else null

            val timeText = getLastModified?.invoke(entry)?.takeIf { it > 0 }?.let { formatRelativeTime(it) }

            val parts = listOfNotNull(countText, timeText)
            if (parts.isNotEmpty()) {
                tvWordCount.text = parts.joinToString(" · ")
                tvWordCount.visibility = View.VISIBLE
            } else {
                tvWordCount.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(entry) }
            itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }

        // ✅ 把毫秒时间戳格式化成"2分钟前/3小时前/2天前/2周前/3个月前/2年前"这种相对时间
        private fun formatRelativeTime(timestampMs: Long): String {
            val diffMs = System.currentTimeMillis() - timestampMs
            if (diffMs < 0) return "刚刚"

            val minute = 60_000L
            val hour = 60 * minute
            val day = 24 * hour
            val week = 7 * day
            val month = 30 * day
            val year = 365 * day

            return when {
                diffMs < minute -> "刚刚"
                diffMs < hour -> "${diffMs / minute}分钟前"
                diffMs < day -> "${diffMs / hour}小时前"
                diffMs < week -> "${diffMs / day}天前"
                diffMs < month -> "${diffMs / week}周前"
                diffMs < year -> "${diffMs / month}个月前"
                else -> "${diffMs / year}年前"
            }
        }
    }
}
