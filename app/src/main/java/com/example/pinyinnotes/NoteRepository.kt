package com.example.pinyinnotes

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/** 某个分类文件夹内的笔记列表与新建，文件名和内容都加密 */
class NoteRepository(private val context: Context, private val folderDoc: DocumentFile) {

    private val EXT = ".dat"

    fun getAllNotes(): List<Note> {
        return folderDoc.listFiles()
            .filter { it.isFile && it.name?.endsWith(EXT) == true }
            .mapNotNull { file ->
                try {
                    val encoded = file.name!!.removeSuffix(EXT)
                    val displayName = CryptoUtil.decryptFileName(encoded)
                    Note(displayName, file.uri)
                } catch (e: Exception) {
                    null
                }
            }
            .sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addNote(name: String): Note? {
        val fileName = CryptoUtil.encryptToFileName(name) + EXT
        val file = folderDoc.createFile("application/octet-stream", fileName) ?: return null
        DocStore.setContent(context, file.uri, "")
        return Note(name, file.uri)
    }

    fun deleteNote(uri: android.net.Uri) {
        DocStore.delete(context, uri)
    }
}
