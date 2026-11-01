package org.moviepilot.android.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 令牌等敏感值的本机加密存储，对应 iOS 的 Keychain（kSecAttrAccessibleWhenUnlockedThisDeviceOnly）。
 *
 * 实现要点：
 * 1. 密钥由 Android Keystore 生成并永不导出，等价于“设备绑定、不同步 iCloud”；
 * 2. Android 9 起额外要求 setUnlockedDeviceRequired，设备锁定时无法解密；
 * 3. 密文只放在应用私有 SharedPreferences，条目名按实例 UUID 隔离。
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        val payload = encrypt(value) ?: return
        prefs.edit().putString(name, payload).apply()
    }

    fun get(name: String): String? {
        val payload = prefs.getString(name, null) ?: return null
        return decrypt(payload)
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun encrypt(value: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    } catch (error: Exception) {
        null
    }

    private fun decrypt(payload: String): String? = try {
        val parts = payload.split(":")
        if (parts.size != 2) {
            null
        } else {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }
    } catch (error: Exception) {
        null
    }

    /** 首次使用时创建不可导出的 AES 密钥；失败时由调用方按“无法安全保存”处理。 */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER)
        store.load(null)
        val existing = store.getEntry(ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "moviepilot.secrets.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "moviepilot.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** 每个实例独立的令牌条目名。 */
        fun tokenKey(instanceId: String): String = "token.$instanceId"
    }
}