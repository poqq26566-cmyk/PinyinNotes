package com.example.pinyinnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 使用本地 JSON 文件持久化，无需数据库依赖，简单可靠。
 */
class NoteRepository(context: Context) {

    private val file = File(context.filesDir, "notes.json")

    /** 获取全部笔记，按拼音首字母 + 名称排序 */
    fun getAllNotes(): List<Note> {
        return getAllNotesRaw().sortedWith(
            compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name })
        )
    }

    fun addNote(name: String): Note {
        val notes = getAllNotesRaw().toMutableList()
        val newId = (notes.maxOfOrNull { it.id } ?: 0L) + 1
        val note = Note(id = newId, name = name, content = "")
        notes.add(note)
        saveAll(notes)
        return note
    }

    fun getNoteById(id: Long): Note? = getAllNotesRaw().find { it.id == id }

    fun updateNoteContent(id: Long, content: String) {
        val notes = getAllNotesRaw().toMutableList()
        val index = notes.indexOfFirst { it.id == id }
        if (index >= 0) {
            notes[index] = notes[index].copy(content = content)
            saveAll(notes)
        }
    }

    fun deleteNote(id: Long) {
        val notes = getAllNotesRaw().filter { it.id != id }
        saveAll(notes)
    }

    private fun getAllNotesRaw(): List<Note> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val list = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Note(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    content = obj.optString("content", "")
                )
            )
        }
        return list
    }

    private fun saveAll(notes: List<Note>) {
        val arr = JSONArray()
        for (note in notes) {
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("name", note.name)
            obj.put("content", note.content)
            arr.put(obj)
        }
        file.writeText(arr.toString())
    }
}
