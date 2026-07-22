package com.example.pinyinnotes

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** 某个分类文件夹内的笔记列表与新建，文件名和内容都加密 */
class NoteRepository(private val context: Context, private val folderDoc: DocumentFile) {

    private val EXT = ".dat"

    // ✅ 用一次批量 query 把所有文件的名字+修改时间一起拿回来，
    // 而不是 listFiles() 之后再对每个文件单独调用 .name / .lastModified()
    // （SAF 下每次单独属性访问都是一次独立的跨进程查询，文件一多就很慢）
    fun getAllNotes(): List<Note> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderDoc.uri, DocumentsContract.getDocumentId(folderDoc.uri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<Note>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    try {
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue

                        val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        if (fileName == null || !fileName.endsWith(EXT)) continue

                        val docId = cursor.getString(idIdx)
                        val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                        val uri = DocumentsContract.buildDocumentUriUsingTree(folderDoc.uri, docId)

                        val encoded = fileName.removeSuffix(EXT)
                        val displayName = CryptoUtil.decryptFileName(encoded)
                        result.add(Note(displayName, uri, lastMod))
                    } catch (e: Exception) {
                        // 跳过单条解析失败的文件，不影响其它笔记
                    }
                }
            }
        } catch (e: Exception) {
            // 查询失败就退回空列表，避免整个 App 崩掉
        }
        return result.sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addNote(name: String): Note? {
        val fileName = CryptoUtil.encryptToFileName(name) + EXT
        val file = folderDoc.createFile("application/octet-stream", fileName) ?: return null
        DocStore.setContent(context, file.uri, "")
        val lastMod = try { file.lastModified() } catch (e: Exception) { 0L }
        return Note(name, file.uri, lastMod)
    }

    fun deleteNote(uri: android.net.Uri) {
        DocStore.delete(context, uri)
    }

    fun renameNote(uri: android.net.Uri, newName: String): Note? {
        val newFileName = CryptoUtil.encryptToFileName(newName) + EXT
        val newUri = DocStore.rename(context, uri, newFileName) ?: return null
        return Note(newName, newUri)
    }
}
