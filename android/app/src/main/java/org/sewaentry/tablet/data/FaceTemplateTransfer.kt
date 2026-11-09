package org.sewaentry.tablet.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.AEADBadTagException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password protected, device-independent face-template transfer container. */
object FaceTemplateTransfer {
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    fun seal(payload: String, password: CharArray): String {
        require(password.size >= 10) { "Use a transfer password with at least 10 characters." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD("sewa-face-transfer-v1".toByteArray())
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", "sewa-face-transfer-v1")
            .put("iterations", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString()
    }

    fun open(container: String, password: CharArray): String {
        val json = JSONObject(container)
        require(json.optString("format") == "sewa-face-transfer-v1") { "This is not a Sewa face backup." }
        require(json.getInt("iterations") == ITERATIONS) { "Unsupported face backup format." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, Base64.decode(json.getString("salt"), Base64.DEFAULT)), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.DEFAULT)))
        cipher.updateAAD("sewa-face-transfer-v1".toByteArray())
        val plaintext = try {
            cipher.doFinal(Base64.decode(json.getString("data"), Base64.DEFAULT))
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("The transfer password is incorrect or the file is damaged.", error)
        }
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
}
