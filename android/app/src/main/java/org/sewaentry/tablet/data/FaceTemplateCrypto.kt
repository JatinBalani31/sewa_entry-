package org.sewaentry.tablet.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.sewaentry.tablet.face.FaceModelContract
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedFaceEmbedding(
    val ivBase64: String,
    val ciphertextBase64: String,
)

data class FaceTemplateMetadata(
    val sewadarId: Long,
    val sampleIndex: Int,
    val modelVersion: String,
    val consentVersion: String,
    val consentAt: Long,
    val enrolledAt: Long,
) {
    fun toAssociatedData(): ByteArray =
        listOf(sewadarId, sampleIndex, modelVersion, consentVersion, consentAt, enrolledAt)
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8)
}

class FaceEmbeddingCipher(private val keyProvider: () -> SecretKey) {
    fun encrypt(embedding: FloatArray, metadata: FaceTemplateMetadata): EncryptedFaceEmbedding {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(metadata.toAssociatedData())
        val encrypted = cipher.doFinal(encodeEmbedding(embedding))
        return EncryptedFaceEmbedding(
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(encrypted),
        )
    }

    fun decrypt(encrypted: EncryptedFaceEmbedding, metadata: FaceTemplateMetadata): FloatArray {
        require(metadata.modelVersion == FaceModelContract.MODEL_VERSION) {
            "Face template model version is not supported"
        }
        val iv = Base64.getDecoder().decode(encrypted.ivBase64)
        val ciphertext = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(metadata.toAssociatedData())
        return decodeEmbedding(cipher.doFinal(ciphertext))
    }

    private fun encodeEmbedding(embedding: FloatArray): ByteArray {
        require(embedding.size == FaceModelContract.EMBEDDING_SIZE) {
            "Face embedding must contain ${FaceModelContract.EMBEDDING_SIZE} values"
        }
        require(embedding.all { it.isFinite() }) { "Face embedding contains a non-finite value" }
        val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun decodeEmbedding(bytes: ByteArray): FloatArray {
        require(bytes.size == FaceModelContract.EMBEDDING_SIZE * Float.SIZE_BYTES) {
            "Encrypted face embedding has an invalid size"
        }
        val embedding = FloatArray(FaceModelContract.EMBEDDING_SIZE)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(embedding)
        require(embedding.all { it.isFinite() }) { "Decrypted face embedding is invalid" }
        return embedding
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

object FaceTemplateCrypto {
    private val cipher = FaceEmbeddingCipher(::getOrCreateKey)

    fun encrypt(embedding: FloatArray, metadata: FaceTemplateMetadata): EncryptedFaceEmbedding =
        cipher.encrypt(embedding, metadata)

    fun decrypt(encrypted: EncryptedFaceEmbedding, metadata: FaceTemplateMetadata): FloatArray =
        cipher.decrypt(encrypted, metadata)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sewa_face_templates_aes_v1"
}
