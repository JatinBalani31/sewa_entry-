package org.sewaentry.tablet.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The PIN that separates the counter from the admin.
 *
 * The register is the public half — it sits on a table in the hall and anyone
 * doing sewa uses it. Everything that reads or rewrites the record (the summary
 * and the roster) sits behind this.
 *
 * The PIN is stored as a salted SHA-256 digest, so someone reading the
 * preferences file cannot read the PIN back. That is the honest limit of it: on
 * a rooted tablet a four-digit PIN can be brute-forced from the hash in
 * moments. It is a lock on a door in a hall full of sangat, not a vault — it
 * stops the tablet's own screen being used to change records, which is what it
 * is for.
 */
class AdminLock(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sewa-admin", Context.MODE_PRIVATE)

    /** No PIN yet: the first person into the admin area chooses one. */
    val isConfigured: Boolean
        get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun setPin(pin: String): Boolean {
        if (!isPlausible(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt))
            .apply()
        return true
    }

    fun check(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        // Constant-time compare: cheap, and means a wrong PIN gives away
        // nothing through timing.
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())
    }

    fun changePin(current: String, next: String): Boolean =
        check(current) && setPin(next)

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray()).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"

        const val LENGTH = 4

        /** Four digits — anything else is a typo, not a PIN. */
        fun isPlausible(pin: String): Boolean =
            pin.length == LENGTH && pin.all { it.isDigit() }
    }
}
