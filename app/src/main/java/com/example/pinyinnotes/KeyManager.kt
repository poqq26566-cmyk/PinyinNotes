package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
 * AES-256 内容密钥不再以明文形式存盘。
 * 改为：用用户设置的密码，通过 PBKDF2 派生出一把"密钥加密密钥"（KEK），
 * 再用 KEK 把随机生成的内容密钥包裹（AES-GCM）后存进 SAF 文件夹里的 _vault.key。
 *
 * 好处：拿到这个文件夹（比如文件夹本身在云盘同步、或手机被别人打开文件管理器）
 * 而不知道密码的人，是打不开笔记的——密钥本身也是被加密保护的。
 *
 * 兼容旧版本：旧版把密钥明文存盘（正好 32 字节）。检测到这种旧格式时，
 * 会先用这把明文密钥正常解锁，然后立刻用当前输入的密码重新包裹、覆盖写回文件，
 * 完成一次性迁移，用户无感知，之后就是新格式了。
 *
 * 配合 VaultKeyCache 使用：解锁成功后把密钥缓存进本机 Android Keystore 保护的
 * 私有存储，这样用户不用每次冷启动都重新输入密码。
 */
object KeyManager {

    private const val KEY_FILE_NAME = "_vault.key"
    private const val KEY_SIZE = 32           // 256-bit 内容密钥
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_LEN_BITS = 128
    private const val PBKDF2_ITERATIONS = 200_000
    private const val FORMAT_VERSION: Byte = 1

    // 旧版明文密钥文件的固定大小，用来识别"这是没有密码保护的老格式"
    private const val LEGACY_PLAIN_KEY_SIZE = KEY_SIZE

    private var cachedKey: SecretKey? = null

    sealed class UnlockResult {
        object Success : UnlockResult()
        object WrongPassword : UnlockResult()
        object IoError : UnlockResult()
    }

    /** 判断该文件夹下是否已经存在密钥文件。用来决定 UI 上该提示"设置密码"还是"输入密码"。
     *  涉及 SAF 访问，需在子线程调用。 */
    fun vaultExists(context: Context, rootFolderUri: Uri): Boolean {
        val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return false
        return rootDoc.listFiles().any { it.name == KEY_FILE_NAME }
    }

    /**
     * 首次使用：用密码生成一把新的内容密钥并加密存盘。
     * 仅应在 vaultExists() == false 时调用。必须在子线程调用。
     */
    fun createNew(context: Context, rootFolderUri: Uri, password: String): Boolean {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return false
            val realKey = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val fileBytes = wrapKey(realKey, password, salt)

            val newFile = rootDoc.createFile("application/octet-stream", KEY_FILE_NAME)
                ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(fileBytes) }
                ?: return false

            cachedKey = SecretKeySpec(realKey, "AES")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 用密码解锁已有的密钥文件。必须在子线程调用。
     */
    fun unlock(context: Context, rootFolderUri: Uri, password: String): UnlockResult {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri)
                ?: return UnlockResult.IoError
            val keyFile = rootDoc.listFiles().find { it.name == KEY_FILE_NAME }
                ?: return UnlockResult.IoError
            val fileBytes = context.contentResolver.openInputStream(keyFile.uri)?.use { it.readBytes() }
                ?: return UnlockResult.IoError

            if (fileBytes.size == LEGACY_PLAIN_KEY_SIZE) {
                // 旧版明文密钥：直接采用，然后立刻用当前密码重新包裹、覆盖写回（一次性迁移）
                cachedKey = SecretKeySpec(fileBytes, "AES")
                val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
                val wrapped = wrapKey(fileBytes, password, salt)
                context.contentResolver.openOutputStream(keyFile.uri, "wt")?.use { it.write(wrapped) }
                return UnlockResult.Success
            }

            val realKey = unwrapKey(fileBytes, password) ?: return UnlockResult.WrongPassword
            cachedKey = SecretKeySpec(realKey, "AES")
            UnlockResult.Success
        } catch (e: Exception) {
            e.printStackTrace()
            UnlockResult.IoError
        }
    }

    private fun deriveKek(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** 文件格式：[版本1字节][迭代次数4字节][salt 16字节][iv 12字节][AES-GCM密文(含16字节tag)] */
    private fun wrapKey(realKey: ByteArray, password: String, salt: ByteArray): ByteArray {
        val kek = deriveKek(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(realKey)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(FORMAT_VERSION))
        out.write(intToBytes(PBKDF2_ITERATIONS))
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
        return out.toByteArray()
    }

    /** 解不开（版本不对/密码错误/数据损坏）统一返回 null，由调用方视为"密码错误" */
    private fun unwrapKey(data: ByteArray, password: String): ByteArray? {
        return try {
            var offset = 0
            val version = data[offset]; offset += 1
            if (version != FORMAT_VERSION) return null
            val iterations = bytesToInt(data, offset); offset += 4
            val salt = data.copyOfRange(offset, offset + SALT_SIZE); offset += SALT_SIZE
            val iv = data.copyOfRange(offset, offset + IV_SIZE); offset += IV_SIZE
            val ciphertext = data.copyOfRange(offset, data.size)

            val kek = deriveKek(password, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun intToBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    private fun bytesToInt(data: ByteArray, offset: Int): Int = ByteBuffer.wrap(data, offset, 4).int

    /** 获取当前密钥，未解锁时抛出异常 */
    fun getKey(): SecretKey = cachedKey
        ?: throw IllegalStateException("KeyManager 未初始化，请先调用 unlock()/createNew()/restoreFromCachedBytes()")

    fun isReady(): Boolean = cachedKey != null

    /** 取出当前内存中密钥的原始字节，用于写入本机免密缓存（VaultKeyCache） */
    fun getKeyBytesOrNull(): ByteArray? = cachedKey?.encoded

    /** 用本机免密缓存里取出的字节直接恢复密钥，跳过密码校验 */
    fun restoreFromCachedBytes(bytes: ByteArray) {
        cachedKey = SecretKeySpec(bytes, "AES")
    }

    /** App 退出或切换文件夹时清除内存中的密钥 */
    fun clear() {
        cachedKey = null
    }
}
