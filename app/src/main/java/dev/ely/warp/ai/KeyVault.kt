package dev.ely.warp.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys on the device, encrypted by the Android Keystore.
 *
 * Warp is open source and sideloaded, so it ships with **no keys at all**. The
 * user brings their own, and it must never leave the phone: not into the repo,
 * not into a log, and not into the AI's context.
 *
 * How it works: the secret key that does the encrypting is generated *inside*
 * the Android Keystore and cannot be read out — not by Warp, not by anything
 * else. Only encrypt and decrypt operations are possible, and only for this
 * app. What lands in SharedPreferences is ciphertext that is useless on its
 * own.
 *
 * Deliberately dependency-free. androidx.security's EncryptedSharedPreferences
 * would do the same job, but this is a hundred lines we can read end to end,
 * for something worth understanding completely.
 */
object KeyVault {

    private const val TAG = "WarpKeyVault"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "warp_api_key_wrapper"
    private const val PREFS = "warp_keys"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Save [apiKey] for [providerId]. Passing a blank key deletes it.
     *
     * Note there is no logging of the key itself anywhere in this file, on
     * purpose — a key in logcat is a leaked key.
     */
    fun save(context: Context, providerId: String, apiKey: String): Boolean {
        if (apiKey.isBlank()) {
            delete(context, providerId)
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, wrapperKey())

            val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

            // The IV is generated fresh per encryption and is not secret, so
            // it is stored alongside the ciphertext.
            val blob = cipher.iv + encrypted

            prefs(context).edit()
                .putString(providerId, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
            Log.i(TAG, "stored a key for $providerId")
            true
        }.getOrElse {
            Log.e(TAG, "could not store the key for $providerId", it)
            false
        }
    }

    /** The plaintext key, or null if there is none or it cannot be decrypted. */
    fun load(context: Context, providerId: String): String? {
        val stored = prefs(context).getString(providerId, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val body = blob.copyOfRange(IV_BYTES, blob.size)

            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, wrapperKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrElse {
            // Happens if the Keystore key was invalidated — a backup restored
            // onto a different phone, for example. The stored bytes can never
            // be recovered, so clear them and let the user paste the key again.
            Log.w(TAG, "stored key for $providerId is unreadable; clearing it")
            delete(context, providerId)
            null
        }
    }

    fun hasKey(context: Context, providerId: String): Boolean =
        prefs(context).contains(providerId)

    fun delete(context: Context, providerId: String) {
        prefs(context).edit().remove(providerId).apply()
        Log.i(TAG, "removed the key for $providerId")
    }

    /** Every provider that currently has a key stored. */
    fun providersWithKeys(context: Context): Set<String> = prefs(context).all.keys

    /**
     * Show a key without revealing it — for confirming the right one is saved.
     *
     * e.g. `sk-ant-…7f2a`
     */
    fun masked(context: Context, providerId: String): String? {
        val key = load(context, providerId) ?: return null
        if (key.length <= 12) return "•".repeat(key.length)
        return key.take(7) + "…" + key.takeLast(4)
    }

    // ── the Keystore-held wrapper key ────────────────────────────────────

    private fun wrapperKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user authentication requirement: Warp must be able to
                // build and call the AI without a fingerprint prompt each time.
                // The key still never leaves the Keystore.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        Log.i(TAG, "generated the Keystore wrapper key")
        return generator.generateKey()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
