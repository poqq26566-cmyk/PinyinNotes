package com.example.pinyinnotes

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 加解密工具。
 * 密钥来源改为 KeyManager（存在用户选择的 SAF 文件夹里的 _vault.key），
 * 这样 App 删除重装后，只要选回同一个文件夹密钥就能恢复，数据不会丢失。
 *
 * 兼容旧版本：如果 KeyManager 的密钥解不开，会自动用旧版写死的密钥再试一次，
 * 保证历史笔记升级后仍能正常打开。
 */
object CryptoUtil {

    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    // 旧版本写死在代码里的密钥，仅用于兼容解密老笔记，不再用它加密新内容
    private val LEGACY_KEY = byteArrayOf(
        0x3a, 0x7f, 0x1c, 0x92.toByte(), 0x5e, 0x44, 0xd1.toByte(), 0x08,
        0x6b, 0x2a, 0x99.toByte(), 0x17, 0x5c, 0xa3.toByte(), 0x40, 0x0e,
        0x77, 0xb6.toByte(), 0x1f, 0x83.toByte(), 0x29, 0x5d, 0xc2.toByte(), 0x64,
        0x11, 0x9a.toByte(), 0x3e, 0x58, 0xf0.toByte(), 0x22, 0x87.toByte(), 0x4d
    )

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KeyManager.getKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    private fun decryptBytes(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val cipherText = data.copyOfRange(IV_LEN, data.size)
        return try {
            // 优先用 KeyManager 的密钥（_vault.key）解密
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, KeyManager.getKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            // 解不开则用旧版写死密钥再试（兼容历史笔记）
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(LEGACY_KEY, "AES"),
                GCMParameterSpec(TAG_LEN_BITS, iv)
            )
            cipher.doFinal(cipherText)
        }
    }

    fun encryptToFileName(name: String): String {
        val encrypted = encryptBytes(name.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decryptFileName(encoded: String): String {
        val data = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return String(decryptBytes(data), Charsets.UTF_8)
    }

    fun encryptContent(text: String): ByteArray = encryptBytes(text.toByteArray(Charsets.UTF_8))

    fun decryptContent(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return String(decryptBytes(data), Charsets.UTF_8)
    }
}
