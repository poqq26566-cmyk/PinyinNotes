package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 使用 SAF 操作用户选定的文件夹，文件名和内容都用固定密钥加密，
 * 文件管理器里看到的是乱码文件名和乱码内容。
 */
class NoteRepository(private val context: Context, treeUri: Uri) {

    private val rootDoc: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹")

    private val EXT = ".dat"

    fun getAllNotes(): List<Note> {
        return rootDoc.listFiles()
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
        val file = rootDoc.createFile("application/octet-stream", fileName) ?: return null
        writeContent(file.uri, "")
        return Note(name, file.uri)
    }

    fun getNoteContent(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        val bytes = input.use { it.readBytes() }
        return try {
            CryptoUtil.decryptContent(bytes)
        } catch (e: Exception) {
            ""
        }
    }

    fun updateNoteContent(uri: Uri, content: String) {
        writeContent(uri, content)
    }

    fun deleteNote(uri: Uri) {
        DocumentFile.fromSingleUri(context, uri)?.delete()
    }

    private fun writeContent(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(CryptoUtil.encryptContent(content))
        }
    }
}
