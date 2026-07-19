package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract  // ← 新增这个 import
import androidx.documentfile.provider.DocumentFile

/** 通用的加密内容读写 + 删除，不依赖具体所在文件夹 */
object DocStore {

    fun getContent(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        val bytes = input.use { it.readBytes() }
        return try {
            CryptoUtil.decryptContent(bytes)
        } catch (e: Exception) {
            ""
        }
    }

    fun setContent(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(CryptoUtil.encryptContent(content))
        }
    }

    /** 重命名文件或文件夹，成功返回新的 uri */
    fun rename(context: Context, uri: Uri, newEncodedName: String): Uri? {
        return try {
            // ✅ 正确方式：使用 DocumentsContract.renameDocument()
            DocumentsContract.renameDocument(
                context.contentResolver,
                uri,
                newEncodedName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 删除文件或文件夹（含文件夹内所有内容） */
    fun delete(context: Context, uri: Uri) {
        DocumentFile.fromSingleUri(context, uri)?.delete()
    }
}
