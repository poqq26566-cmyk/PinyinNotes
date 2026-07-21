package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码派生改用 Argon2id：比 PBKDF2 抗显卡暴力破解能力强很多（占内存，GPU 难并行）。
 * 兼容旧格式：明文密钥、上一版 PBKDF2 包裹密钥，解锁成功后都会自动迁移成 Argon2id 格式。
 * 配合 VaultKeyCache：解锁成功后缓存进本机 Keystore，避免每次冷启动都要输密码。
 */
object KeyManager {

    private const val KEY_FILE_NAME = "_vault.key"
    private const val KEY_SIZE = 32
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_LEN_BITS = 128

    private const val ARGON2_MEMORY_KB = 65536 // 64MB
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_PARALLELISM = 1

    private const val FORMAT_VERSION_PBKDF2: Byte = 1
    private const val FORMAT_VERSION_ARGON2: Byte = 2
    private const val LEGACY_PLAIN_KEY_SIZE = KEY_SIZE

    private var cachedKey: SecretKey? = null

    sealed class UnlockResult {
        object Success : UnlockResult()
        object WrongPassword : UnlockResult()
        object IoError : UnlockResult()
    }

    fun vaultExists(context: Context, rootFolderUri: Uri): Boolean {
        val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return false
        return rootDoc.listFiles().any { it.name == KEY_FILE_NAME }
    }

    fun createNew(context: Context, rootFolderUri: Uri, password: String): Boolean {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return false
            val realKey = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val fileBytes = wrapKeyArgon2(realKey, password, salt)

            val newFile = rootDoc.createFile("application/octet-stream", KEY_FILE_NAME) ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(fileBytes) } ?: return false

            cachedKey = SecretKeySpec(realKey, "AES")
            true
        } catch (e: Exception) {
            e.printStackTrace(); false
        }
    }

    fun unlock(context: Context, rootFolderUri: Uri, password: String): UnlockResult {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return UnlockResult.IoError
            val keyFile = rootDoc.listFiles().find { it.name == KEY_FILE_NAME } ?: return UnlockResult.IoError
            val fileBytes = context.contentResolver.openInputStream(keyFile.uri)?.use { it.readBytes() }
                ?: return UnlockResult.IoError

            when {
                fileBytes.size == LEGACY_PLAIN_KEY_SIZE -> {
                    cachedKey = SecretKeySpec(fileBytes, "AES")
                    migrateToArgon2(context, keyFile.uri, fileBytes, password)
                    UnlockResult.Success
                }
                fileBytes.isNotEmpty() && fileBytes[0] == FORMAT_VERSION_PBKDF2 -> {
                    val realKey = unwrapKeyPbkdf2(fileBytes, password) ?: return UnlockResult.WrongPassword
                    cachedKey = SecretKeySpec(realKey, "AES")
                    migrateToArgon2(context, keyFile.uri, realKey, password)
                    UnlockResult.Success
                }
                fileBytes.isNotEmpty() && fileBytes[0] == FORMAT_VERSION_ARGON2 -> {
                    val realKey = unwrapKeyArgon2(fileBytes, password) ?: return UnlockResult.WrongPassword
                    cachedKey = SecretKeySpec(realKey, "AES")
                    UnlockResult.Success
                }
                else -> UnlockResult.IoError
            }
        } catch (e: Exception) {
            e.printStackTrace(); UnlockResult.IoError
        }
    }

    private fun migrateToArgon2(context: Context, keyFileUri: Uri, realKey: ByteArray, password: String) {
        try {
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val wrapped = wrapKeyArgon2(realKey, password, salt)
            context.contentResolver.openOutputStream(keyFileUri, "wt")?.use { it.write(wrapped) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deriveKekArgon2(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int): SecretKey {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(KEY_SIZE)
        generator.generateBytes(password.toCharArray(), out)
        return SecretKeySpec(out, "AES")
    }

    private fun deriveKekPbkdf2Legacy(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** [版本2][memoryKB int32][iterations int32][parallelism int32][salt 16][iv 12][密文] */
    private fun wrapKeyArgon2(realKey: ByteArray, password: String, salt: ByteArray): ByteArray {
        val kek = deriveKekArgon2(password, salt, ARGON2_MEMORY_KB, ARGON2_ITERATIONS, ARGON2_PARALLELISM)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(realKey)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(FORMAT_VERSION_ARGON2))
        out.write(intToBytes(ARGON2_MEMORY_KB))
        out.write(intToBytes(ARGON2_ITERATIONS))
        out.write(intToBytes(ARGON2_PARALLELISM))
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
        return out.toByteArray()
    }

    private fun unwrapKeyArgon2(data: ByteArray, password: String): ByteArray? {
        return try {
            var offset = 1
            val memoryKb = bytesToInt(data, offset); offset += 4
            val iterations = bytesToInt(data, offset); offset += 4
            val parallelism = bytesToInt(data, offset); offset += 4
            val salt = data.copyOfRange(offset, offset + SALT_SIZE); offset += SALT_SIZE
            val iv = data.copyOfRange(offset, offset + IV_SIZE); offset += IV_SIZE
            val ciphertext = data.copyOfRange(offset, data.size)

            val kek = deriveKekArgon2(password, salt, memoryKb, iterations, parallelism)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /** 旧格式只读不再写：[版本1][迭代次数 int32][salt 16][iv 12][密文] */
    private fun unwrapKeyPbkdf2(data: ByteArray, password: String): ByteArray? {
        return try {
            var offset = 1
            val iterations = bytesToInt(data, offset); offset += 4
            val salt = data.copyOfRange(offset, offset + SALT_SIZE); offset += SALT_SIZE
            val iv = data.copyOfRange(offset, offset + IV_SIZE); offset += IV_SIZE
            val ciphertext = data.copyOfRange(offset, data.size)

            val kek = deriveKekPbkdf2Legacy(password, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun intToBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun bytesToInt(data: ByteArray, offset: Int): Int = ByteBuffer.wrap(data, offset, 4).int

    fun getKey(): SecretKey = cachedKey
        ?: throw IllegalStateException("KeyManager 未初始化，请先调用 unlock()/createNew()/restoreFromCachedBytes()")

    fun isReady(): Boolean = cachedKey != null
    fun getKeyBytesOrNull(): ByteArray? = cachedKey?.encoded
    fun restoreFromCachedBytes(bytes: ByteArray) { cachedKey = SecretKeySpec(bytes, "AES") }
    fun clear() { cachedKey = null }
}
