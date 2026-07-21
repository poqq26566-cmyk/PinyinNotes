package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 把 AES-256 密钥以明文字节的形式存在用户选择的 SAF 文件夹里（_vault.key）。
 * 好处：App 删除重装后，只要用户选回同一个文件夹，密钥就能恢复，数据不会丢失。
 */
object KeyManager {

    private const val KEY_FILE_NAME = "_vault.key"
    private const val KEY_SIZE = 32 // 256-bit

    private var cachedKey: SecretKey? = null

    /**
     * 从 SAF 根文件夹加载或创建密钥文件。
     * 必须在子线程调用（涉及 IO）。
     * 成功返回 true，之后可用 getKey() 取密钥。
     */
    fun loadOrCreate(context: Context, rootFolderUri: Uri): Boolean {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return false

            // 1. 尝试读取已有密钥文件
            val keyFile = rootDoc.listFiles().find { it.name == KEY_FILE_NAME }
            if (keyFile != null) {
                val keyBytes = context.contentResolver
                    .openInputStream(keyFile.uri)?.use { it.readBytes() }
                if (keyBytes != null && keyBytes.size == KEY_SIZE) {
                    cachedKey = SecretKeySpec(keyBytes, "AES")
                    return true
                }
            }

            // 2. 首次使用：生成随机密钥并写入文件夹
            val keyBytes = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
            val newFile = rootDoc.createFile("application/octet-stream", KEY_FILE_NAME)
                ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(keyBytes) }
            cachedKey = SecretKeySpec(keyBytes, "AES")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 获取当前密钥，未初始化时抛出异常 */
    fun getKey(): SecretKey = cachedKey
        ?: throw IllegalStateException("KeyManager 未初始化，请先调用 loadOrCreate()")

    fun isReady(): Boolean = cachedKey != null

    /** App 退出或切换文件夹时清除内存中的密钥 */
    fun clear() {
        cachedKey = null
    }
}
