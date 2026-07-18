package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
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

    /** 删除文件或文件夹（含文件夹内所有内容） */
    fun delete(context: Context, uri: Uri) {
        DocumentFile.fromSingleUri(context, uri)?.delete()
    }
}
