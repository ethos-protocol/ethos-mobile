package com.ethosprotocol.services

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps and unwraps key bytes with a hardware-backed key that never leaves the Keystore. */
internal interface KeyWrapper {
    /** Returns the IV followed by the ciphertext of [plain]. */
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** [KeyWrapper] backed by an AES-256-GCM key in the Android Keystore (#726). */
internal class AndroidKeystoreWrapper : KeyWrapper {

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ethos_pending_actions_db_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val body = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}

/**
 * Supplies the 256-bit key for the encrypted pending-actions database (#726).
 *
 * A random key is generated once, wrapped by the Keystore key, and only the wrapped form is
 * persisted. The raw key is never written to SharedPreferences or any other plain storage.
 */
@Singleton
class PendingActionDatabaseKeyProvider internal constructor(
    private val prefs: SharedPreferences,
    private val wrapper: KeyWrapper,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AndroidKeystoreWrapper(),
    )

    companion object {
        internal const val PREFS_NAME = "pending_action_db_key"
        internal const val KEY_WRAPPED = "wrapped_key"
        private const val KEY_BYTES = 32
    }

    /** Returns the database key, creating and wrapping it on first use. */
    @Synchronized
    fun getKey(): ByteArray {
        val stored = prefs.getString(KEY_WRAPPED, null)
        if (stored != null) {
            return wrapper.unwrap(Base64.decode(stored, Base64.NO_WRAP))
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = Base64.encodeToString(wrapper.wrap(fresh), Base64.NO_WRAP)
        check(prefs.edit().putString(KEY_WRAPPED, wrapped).commit()) { "Failed to persist wrapped DB key" }
        return fresh
    }
}
