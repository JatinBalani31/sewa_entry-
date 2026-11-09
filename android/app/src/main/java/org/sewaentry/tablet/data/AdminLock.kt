package org.sewaentry.tablet.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.pow

data class PinAttemptResult(
    val accepted: Boolean,
    val retryAfterMillis: Long = 0L,
)

/**
 * The PIN protecting the tablet's manual-entry and admin screens.
 *
 * New PIN hashes and lockout state are stored in EncryptedSharedPreferences.
 * A legacy four-digit hash is kept only long enough to authenticate the
 * administrator during the forced six-digit upgrade.
 */
class AdminLock(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val isConfigured: Boolean
        get() = prefs.contains(KEY_HASH) || legacyPrefs.contains(LEGACY_KEY_HASH) ||
            legacyPrefs.contains(LEGACY_KEY_SALT)

    val requiresPinMigration: Boolean
        get() = !prefs.contains(KEY_HASH) &&
            (legacyPrefs.contains(LEGACY_KEY_HASH) || legacyPrefs.contains(LEGACY_KEY_SALT))

    fun setPin(pin: String): Boolean {
        if (!isPlausible(pin)) return false
        if (!prefs.edit()
            .putString(KEY_HASH, BCrypt.hashpw(pin, BCrypt.gensalt(BCRYPT_COST)))
            .commit()
        ) {
            return false
        }
        check(legacyPrefs.edit().remove(LEGACY_KEY_HASH).remove(LEGACY_KEY_SALT).commit()) {
            "Could not clear the legacy admin PIN after saving the new PIN."
        }
        clearFailures()
        return true
    }

    /** Verifies the current PIN, including the old four-digit value during migration. */
    fun verifyCurrentPin(pin: String): PinAttemptResult =
        authenticate(pin) {
            if (requiresPinMigration) verifyLegacyHash(pin)
            else verifyBcryptHash(pin)
        }

    /** Verifies the old PIN before the UI allows the forced six-digit upgrade. */
    fun verifyLegacyPin(pin: String): PinAttemptResult =
        authenticate(pin, ::verifyLegacyHash)

    fun migratePin(oldPin: String, newPin: String): PinAttemptResult {
        val verified = verifyLegacyPin(oldPin)
        if (!verified.accepted) return verified
        return if (setPin(newPin)) PinAttemptResult(accepted = true)
        else PinAttemptResult(accepted = false)
    }

    fun changePin(currentPin: String, newPin: String): PinAttemptResult {
        val verified = verifyCurrentPin(currentPin)
        if (!verified.accepted) return verified
        return if (setPin(newPin)) PinAttemptResult(accepted = true)
        else PinAttemptResult(accepted = false)
    }

    private fun authenticate(pin: String, verify: (String) -> Boolean): PinAttemptResult {
        val now = System.currentTimeMillis()
        val state = readLockoutState()
        val remaining = AdminPinLockout.remainingMillis(state, now)
        if (remaining > 0) return PinAttemptResult(accepted = false, retryAfterMillis = remaining)

        if (verify(pin)) {
            clearFailures()
            return PinAttemptResult(accepted = true)
        }

        val next = AdminPinLockout.recordFailure(state, now)
        check(prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, next.failedAttempts)
            .putInt(KEY_LOCKOUT_COUNT, next.lockoutCount)
            .putLong(KEY_LOCKED_UNTIL, next.lockedUntilMillis)
            .commit()
        ) { "Could not persist the admin PIN cooldown." }
        return PinAttemptResult(
            accepted = false,
            retryAfterMillis = AdminPinLockout.remainingMillis(next, now),
        )
    }

    private fun verifyBcryptHash(pin: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        return BCrypt.checkpw(pin, stored)
    }

    private fun verifyLegacyHash(pin: String): Boolean {
        if (!isLegacyPinPlausible(pin)) return false
        val salt = legacyPrefs.getString(LEGACY_KEY_SALT, null)?.fromHex() ?: return false
        val expected = legacyPrefs.getString(LEGACY_KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(hashLegacyPin(pin, salt).toByteArray(), expected.toByteArray())
    }

    private fun hashLegacyPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray()).toHex()
    }

    private fun readLockoutState() = PinLockoutState(
        failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0),
        lockoutCount = prefs.getInt(KEY_LOCKOUT_COUNT, 0),
        lockedUntilMillis = prefs.getLong(KEY_LOCKED_UNTIL, 0L),
    )

    private fun clearFailures() {
        check(prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putInt(KEY_LOCKOUT_COUNT, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .commit()
        ) { "Could not reset the admin PIN cooldown." }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val LEGACY_PREFS = "sewa-admin"
        private const val ENCRYPTED_PREFS = "sewa-admin-secure"
        private const val LEGACY_KEY_HASH = "pin_hash"
        private const val LEGACY_KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_COUNT = "lockout_count"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val BCRYPT_COST = 12

        const val LENGTH = 6
        const val LEGACY_LENGTH = 4

        fun isPlausible(pin: String): Boolean =
            pin.length == LENGTH && pin.all { it in '0'..'9' }

        fun isLegacyPinPlausible(pin: String): Boolean =
            pin.length == LEGACY_LENGTH && pin.all { it in '0'..'9' }
    }
}

data class PinLockoutState(
    val failedAttempts: Int = 0,
    val lockoutCount: Int = 0,
    val lockedUntilMillis: Long = 0L,
)

object AdminPinLockout {
    private const val ATTEMPTS_BEFORE_LOCK = 3
    private const val FIRST_LOCK_MILLIS = 5 * 60 * 1000L
    private const val MAX_LOCK_MILLIS = 60 * 60 * 1000L

    fun remainingMillis(state: PinLockoutState, nowMillis: Long): Long =
        max(0L, state.lockedUntilMillis - nowMillis)

    fun recordFailure(state: PinLockoutState, nowMillis: Long): PinLockoutState {
        if (remainingMillis(state, nowMillis) > 0) return state
        val failures = state.failedAttempts + 1
        if (failures < ATTEMPTS_BEFORE_LOCK) return state.copy(failedAttempts = failures)

        val lockoutCount = state.lockoutCount + 1
        val duration = (FIRST_LOCK_MILLIS * (3.0.pow(lockoutCount - 1)))
            .toLong()
            .coerceAtMost(MAX_LOCK_MILLIS)
        return PinLockoutState(
            failedAttempts = 0,
            lockoutCount = lockoutCount,
            lockedUntilMillis = nowMillis + duration,
        )
    }
}
