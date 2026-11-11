package de.ronnyporsch.voidcast.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what the settings keep secret with a key in the Android Keystore. The key never leaves the device's
 * secure hardware, so a copy of the settings file (from a rooted device, a backup, a broken sandbox) is of no use
 * without it.
 */
internal object SecretCipher {
    private const val KEY_ALIAS = "voidcast_settings"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** The last value encrypted or decrypted, so saving unchanged settings doesn't ask the keystore again. */
    private var last: Pair<String, String>? = null

    /** [secret] encrypted, or null if the device's keystore doesn't work. */
    @Synchronized
    fun encrypt(secret: String): String? {
        last?.let { (plain, encrypted) -> if (plain == secret) return encrypted }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            Base64.encodeToString(cipher.iv + cipher.doFinal(secret.encodeToByteArray()), Base64.NO_WRAP)
        }.getOrNull()?.also { last = secret to it }
    }

    /** What [encrypt] made [encrypted] from, or null if the key is gone (e.g. the app's data got restored). */
    @Synchronized
    fun decrypt(encrypted: String): String? = runCatching {
        val data = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH))
        cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH).decodeToString()
    }.getOrNull()?.also { last = it to encrypted }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
