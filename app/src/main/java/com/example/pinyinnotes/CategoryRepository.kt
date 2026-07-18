package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 根目录下的分类（子文件夹）列表与新建，分类名也加密 */
class CategoryRepository(context: Context, treeUri: Uri) {

    private val rootDoc: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹")

    fun getAllCategories(): List<Category> {
        return rootDoc.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                try {
                    val name = CryptoUtil.decryptFileName(dir.name ?: return@mapNotNull null)
                    Category(name, dir.uri)
                } catch (e: Exception) {
                    null
                }
            }
            .sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addCategory(name: String): Category? {
        val encName = CryptoUtil.encryptToFileName(name)
        val dir = rootDoc.createDirectory(encName) ?: return null
        return Category(name, dir.uri)
    }
}
