package com.example.pinyinnotes

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 每条笔记对应一个独立的 .txt 文件，存放在公共目录：
 * /storage/emulated/0/Vault/拼音笔记/名称.txt
 * 文件名即笔记名，内容是纯文本，文件管理器里可直接打开查看，
 * 修改某条笔记只会改动它自己对应的文件。
 */
class NoteRepository(context: Context) {

    private val dir: File = run {
        val d = File(Environment.getExternalStorageDirectory(), "Vault/拼音笔记")
        if (!d.exists()) d.mkdirs()
        d
    }

    fun getAllNotes(): List<Note> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()
        return files
            .map { Note(it.name.removeSuffix(".txt")) }
            .sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addNote(name: String): Note {
        val safeName = sanitize(name)
        val file = File(dir, "$safeName.txt")
        if (!file.exists()) file.writeText("")
        return Note(safeName)
    }

    fun getNoteContent(name: String): String {
        val file = File(dir, "$name.txt")
        return if (file.exists()) file.readText() else ""
    }

    fun updateNoteContent(name: String, content: String) {
        File(dir, "$name.txt").writeText(content)
    }

    fun deleteNote(name: String) {
        File(dir, "$name.txt").delete()
    }

    /** 过滤安卓文件名不允许的字符 */
    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
