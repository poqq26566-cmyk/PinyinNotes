package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 使用 SAF（Storage Access Framework）操作用户选定的文件夹。
 * 不需要"所有文件访问权限"，每条笔记对应文件夹里一个独立的 txt 文件。
 */
class NoteRepository(private val context: Context, treeUri: Uri) {

    private val rootDoc: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹")

    fun getAllNotes(): List<Note> {
        return rootDoc.listFiles()
            .filter { it.isFile && it.name?.endsWith(".txt") == true }
            .map { Note(it.name!!.removeSuffix(".txt")) }
            .sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addNote(name: String): Note {
        val safeName = sanitize(name)
        if (rootDoc.findFile("$safeName.txt") == null) {
            rootDoc.createFile("text/plain", safeName)
        }
        return Note(safeName)
    }

    fun getNoteContent(name: String): String {
        val file = rootDoc.findFile("$name.txt") ?: return ""
        val input = context.contentResolver.openInputStream(file.uri) ?: return ""
        return input.bufferedReader().use { it.readText() }
    }

    fun updateNoteContent(name: String, content: String) {
        val file = rootDoc.findFile("$name.txt")
            ?: rootDoc.createFile("text/plain", name)
            ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(content.toByteArray())
        }
    }

    fun deleteNote(name: String) {
        rootDoc.findFile("$name.txt")?.delete()
    }

    /** 过滤安卓文件名不允许的字符 */
    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
