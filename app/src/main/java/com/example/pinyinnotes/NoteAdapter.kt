package com.example.pinyinnotes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private sealed class ListItem
private data class HeaderItem(val letter: String) : ListItem()
private data class NoteListItem(val note: Note) : ListItem()

class NoteAdapter(private val onClick: (Note) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NOTE = 1
    }

    /** 传入按拼音排序好的笔记列表，自动生成 A-Z 分组表头 */
    fun submitNotes(notes: List<Note>) {
        items.clear()
        var lastLetter: String? = null
        for (note in notes) {
            val letter = PinyinUtils.getFirstLetter(note.name)
            if (letter != lastLetter) {
                items.add(HeaderItem(letter))
                lastLetter = letter
            }
            items.add(NoteListItem(note))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HeaderItem -> TYPE_HEADER
            is NoteListItem -> TYPE_NOTE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            NoteViewHolder(inflater.inflate(R.layout.item_note, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HeaderItem -> (holder as HeaderViewHolder).bind(item.letter)
            is NoteListItem -> (holder as NoteViewHolder).bind(item.note, onClick)
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvHeader)
        fun bind(letter: String) {
            textView.text = letter
        }
    }

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvName)
        fun bind(note: Note, onClick: (Note) -> Unit) {
            textView.text = note.name
            itemView.setOnClickListener { onClick(note) }
        }
    }
    }
