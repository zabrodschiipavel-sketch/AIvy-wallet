package com.ivy.ai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for the user's Gemini API key.
 *
 * Backed by [EncryptedSharedPreferences] (AES-256). The key never leaves the device.
 * The user enters it once via Settings; the app reads it on every Gemini call.
 */
interface GeminiApiKeyStore {
    fun get(): String?
    fun set(key: String)
    fun clear()
    fun isConfigured(): Boolean
}

@Singleton
class GeminiApiKeyStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GeminiApiKeyStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(): String? = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    override fun set(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    override fun isConfigured(): Boolean = !get().isNullOrBlank()

    companion object {
        private const val PREFS_FILENAME = "ai_secure_prefs"
        private const val KEY_GEMINI = "gemini_api_key"
    }
}
