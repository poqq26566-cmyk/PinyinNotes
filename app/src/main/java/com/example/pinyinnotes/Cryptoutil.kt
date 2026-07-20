package com.example.pinyinnotes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android 系统级密钥库（AndroidKeyStore）做 AES-GCM 加密。
 * 密钥由系统在设备的安全硬件（TEE/StrongBox，如果设备支持）里生成和保存，
 * 不写在代码里、不出现在 apk 文件中，反编译也拿不到密钥本身。
 * 不需要用户输入密码，App 自动向系统取用。
 *
 * 兼容旧版本：如果用新密钥解不开（说明是旧版本加密的文件），
 * 会自动尝试用旧版本写死的那把密钥再解一次，保证升级后旧笔记不会打不开。
 */
object CryptoUtil {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "pinyin_notes_key"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    // 旧版本写死在代码里的密钥，仅用于兼容解密老笔记，不再用它加密新内容
    private val LEGACY_KEY = byteArrayOf(
        0x3a, 0x7f, 0x1c, 0x92.toByte(), 0x5e, 0x44, 0xd1.toByte(), 0x08,
        0x6b, 0x2a, 0x99.toByte(), 0x17, 0x5c, 0xa3.toByte(), 0x40, 0x0e,
        0x77, 0xb6.toByte(), 0x1f, 0x83.toByte(), 0x29, 0x5d, 0xc2.toByte(), 0x64,
        0x11, 0x9a.toByte(), 0x3e, 0x58, 0xf0.toByte(), 0x22, 0x87.toByte(), 0x4d
    )

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    private fun decryptBytes(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val cipherText = data.copyOfRange(IV_LEN, data.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            // 新密钥解不开，可能是旧版本加密的文件，用旧密钥再试一次
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(LEGACY_KEY, "AES"),
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
