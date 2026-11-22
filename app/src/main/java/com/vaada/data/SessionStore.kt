package com.vaada.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.vaada.models.AppLanguage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the merchant bearer token (AES-GCM, key held in the Android Keystore, never logged) and the chosen
 * language (not secret). The token is only ever restored after the backend validates it via GET /auth/me.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vaada_session", Context.MODE_PRIVATE)

    var language: AppLanguage?
        get() = AppLanguage.fromLocale(prefs.getString(KEY_LANG, null))
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_LANG) else putString(KEY_LANG, value.locale) }.apply() }

    fun saveToken(token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val blob = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist session (${e.javaClass.simpleName}); it will last for this run only")
            clearToken()
        }
    }

    fun loadToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN)) }
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Stored session unreadable (${e.javaClass.simpleName}); clearing it")
            clearToken(); null
        }
    }

    fun clearToken() { prefs.edit().remove(KEY_TOKEN).apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val TAG = "SessionStore"
        const val ALIAS = "vaada_session_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KEY_TOKEN = "token_enc"
        const val KEY_LANG = "language"
    }
}
