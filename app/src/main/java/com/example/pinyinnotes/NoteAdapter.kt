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
    private val getWordCount: ((T) -> Int)? = null
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
                getWordCount
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
            getWordCount: ((T) -> Int)?
        ) {
            textView.text = entry.name

            if (getWordCount != null) {
                val count = getWordCount(entry)
                if (count > 0) {
                    tvWordCount.text = count.toString() + "字"
                    tvWordCount.visibility = View.VISIBLE
                } else {
                    tvWordCount.visibility = View.GONE
                }
            } else {
                tvWordCount.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(entry) }
            itemView.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }
}
