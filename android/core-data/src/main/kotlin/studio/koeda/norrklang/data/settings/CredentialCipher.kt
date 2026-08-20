package studio.koeda.norrklang.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import studio.koeda.norrklang.data.diagnostics.Diagnostics

/**
 * Encrypts credential values at rest. [encrypt] output carries a prefix;
 * anything else is treated as legacy plaintext (pre-encryption installs) and
 * passed through by [decrypt] so [ServerSettingsRepository] can migrate it.
 */
interface CredentialCipher {

    fun encrypt(plaintext: String): String

    /**
     * The plaintext for [stored], or null when it can no longer be decrypted
     * (Keystore key lost/invalidated) — callers must treat that as signed out.
     */
    fun decrypt(stored: String): String?

    fun isEncrypted(stored: String): Boolean
}

/**
 * AES/GCM with a key confined to the Android Keystore — key material never
 * enters app memory or any backup, so the on-disk DataStore entry is useless
 * without executing on this device.
 */
class KeystoreCredentialCipher @Inject constructor() : CredentialCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH_BYTES),
            )
            String(
                cipher.doFinal(payload, IV_LENGTH_BYTES, payload.size - IV_LENGTH_BYTES),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // Broader than GeneralSecurityException on purpose: vendor keymint
            // HALs surface failures as RuntimeExceptions (ProviderException,
            // android.security.KeyStoreException), KeyStore.load throws
            // IOException, and bad Base64 is IllegalArgumentException. Every
            // one of them must read as "signed out" — this runs on process
            // start, where a throw becomes a bind/crash loop in the car.
            Diagnostics.record("keystore-decrypt", e)
            null
        }
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    // Synchronized: two concurrent first-encrypt calls could each generate a
    // key, and the second would overwrite the alias and orphan whatever the
    // first one encrypted.
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "norrklang_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "enc1:"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
