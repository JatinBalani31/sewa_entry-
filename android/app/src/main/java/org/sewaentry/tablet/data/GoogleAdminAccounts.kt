package org.sewaentry.tablet.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.Locale

class GoogleAdminAccounts(context: Context) {
    private val prefs = securePrefs(context)

    fun all(): List<String> =
        runCatching { JSONArray(prefs.getString(KEY_ADMINS, "[]")).toStringList() }
            .getOrDefault(emptyList())

    fun contains(email: String): Boolean = all().any { it.equals(email.trim(), ignoreCase = true) }

    fun add(email: String): Boolean {
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || !normalized.contains('@')) return false
        val current = all()
        if (current.any { it.equals(normalized, ignoreCase = true) }) return false
        return prefs.edit().putString(KEY_ADMINS, JSONArray((current + normalized).sorted()).toString()).commit()
    }

    fun remove(email: String): Boolean {
        val current = all()
        if (current.size <= 1) return false
        val remaining = current.filterNot { it.equals(email.trim(), ignoreCase = true) }
        if (remaining.size == current.size) return false
        return prefs.edit().putString(KEY_ADMINS, JSONArray(remaining).toString()).commit()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

    companion object {
        private const val PREFS = "sewa-google-admins"
        private const val KEY_ADMINS = "authorized_emails"

        private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
