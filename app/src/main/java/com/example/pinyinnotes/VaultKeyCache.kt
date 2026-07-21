package com.example.pinyinnotes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把"解锁后的内容密钥"缓存在本机、由 Android Keystore 硬件级密钥保护的私有存储里。
 * 这样用户装完 App、第一次输入密码解锁之后，只要不换文件夹、不清 App 数据/卸载，
 * 以后每次冷启动都不用再输入密码。
 *
 * 注意安全边界：这是"设备本地免密"，跟 KeyManager 里"文件夹密钥文件需要密码"
 * 的保护并不冲突——文件夹里的 _vault.key 依然是密码包裹过的，万一这个文件夹
 * 被单独复制/同步到别的地方，没有密码还是打不开；这里缓存的东西只存在这台
 * 设备的 App 私有目录里，且被 Android Keystore 的硬件密钥再包一层（无法导出）。
 * 代价是：这台设备只要能打开这个 App（不需要额外验证），就能看到笔记——
 * 相当于把"防护"从"密码"移到了"这台手机本身"。
 */
object VaultKeyCache {

    private const val PREFS = "vault_key_cache"
    private const val KEY_TREE_URI = "cached_tree_uri"
    private const val KEY_WRAPPED = "cached_wrapped_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "pinyinnotes_vault_cache_key"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false) // 不强制指纹/锁屏验证：装完只输一次密码
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** 是否已经有对应这个文件夹 uri 的本机缓存 */
    fun hasCache(context: Context, treeUri: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TREE_URI, null) == treeUri &&
            prefs.getString(KEY_WRAPPED, null) != null
    }

    /** 保存本次解锁得到的内容密钥，绑定到这个文件夹 uri */
    fun save(context: Context, treeUri: String, realKeyBytes: ByteArray) {
        try {
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(realKeyBytes)
            val combined = iv + ciphertext
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TREE_URI, treeUri)
                .putString(KEY_WRAPPED, encoded)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
            // 缓存失败不影响主流程，大不了下次还得输一次密码
        }
    }

    /** 取出并解密缓存的内容密钥；拿不到或解不开时返回 null（调用方应回退到输入密码） */
    fun load(context: Context, treeUri: String): ByteArray? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_TREE_URI, null) != treeUri) return null
            val encoded = prefs.getString(KEY_WRAPPED, null) ?: return null
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LEN) return null
            val iv = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)

            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 清除本机缓存（换文件夹、或者密钥不匹配需要强制重新走密码流程时用） */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
