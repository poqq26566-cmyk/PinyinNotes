package com.example.pinyinnotes

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地固定密钥 AES-GCM 加密。
 * 目的只是让文件名和内容在文件管理器里显示为乱码，
 * 不需要用户输入密码，App 内部自动加解密。
 * 注意：密钥固定写在代码里，只防"打开文件管理器随手一看"，
 * 不是专业级密码保护。
 */
object CryptoUtil {

    private val KEY = byteArrayOf(
        0x3a, 0x7f, 0x1c, 0x92.toByte(), 0x5e, 0x44, 0xd1.toByte(), 0x08,
        0x6b, 0x2a, 0x99.toByte(), 0x17, 0x5c, 0xa3.toByte(), 0x40, 0x0e,
        0x77, 0xb6.toByte(), 0x1f, 0x83.toByte(), 0x29, 0x5d, 0xc2.toByte(), 0x64,
        0x11, 0x9a.toByte(), 0x3e, 0x58, 0xf0.toByte(), 0x22, 0x87.toByte(), 0x4d
    )

    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun decryptBytes(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val cipherText = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        return cipher.doFinal(cipherText)
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
