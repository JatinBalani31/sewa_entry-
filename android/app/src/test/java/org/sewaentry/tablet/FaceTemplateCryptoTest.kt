package org.sewaentry.tablet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.sewaentry.tablet.data.EncryptedFaceEmbedding
import org.sewaentry.tablet.data.FaceEmbeddingCipher
import org.sewaentry.tablet.data.FaceTemplateMetadata
import org.sewaentry.tablet.face.FaceModelContract
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class FaceTemplateCryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val cipher = FaceEmbeddingCipher { key }
    private val embedding = FloatArray(FaceModelContract.EMBEDDING_SIZE) { (it - 90) / 100f }
    private val metadata = FaceTemplateMetadata(
        sewadarId = 42,
        sampleIndex = 1,
        modelVersion = FaceModelContract.MODEL_VERSION,
        consentVersion = FaceModelContract.CONSENT_VERSION,
        consentAt = 1_700_000_000_000,
        enrolledAt = 1_700_000_000_100,
    )

    @Test
    fun encryptedEmbeddingRoundTripsWithoutChangingValues() {
        val encrypted = cipher.encrypt(embedding, metadata)

        assertArrayEquals(embedding, cipher.decrypt(encrypted, metadata), 0f)
    }

    @Test
    fun encryptingSameEmbeddingUsesDifferentIv() {
        val first = cipher.encrypt(embedding, metadata)
        val second = cipher.encrypt(embedding, metadata)

        assertNotEquals(first.ivBase64, second.ivBase64)
        assertNotEquals(first.ciphertextBase64, second.ciphertextBase64)
    }

    @Test
    fun modifiedCiphertextCannotBeDecrypted() {
        val encrypted = cipher.encrypt(embedding, metadata)
        val modifiedBytes = Base64.getDecoder().decode(encrypted.ciphertextBase64)
        modifiedBytes[0] = (modifiedBytes[0].toInt() xor 1).toByte()
        val modified = encrypted.copy(
            ciphertextBase64 = Base64.getEncoder().encodeToString(modifiedBytes),
        )

        try {
            cipher.decrypt(modified, metadata)
            fail("Modified face template should fail GCM authentication")
        } catch (_: AEADBadTagException) {
        }
    }

    @Test
    fun decryptionRejectsAnUnknownModelVersion() {
        val encrypted: EncryptedFaceEmbedding = cipher.encrypt(embedding, metadata)
        val wrongMetadata = metadata.copy(modelVersion = "unknown_model")

        try {
            cipher.decrypt(encrypted, wrongMetadata)
            fail("Unknown model version should be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun encryptedTemplateIsBoundToItsMetadata() {
        val encrypted = cipher.encrypt(embedding, metadata)
        val reassignedMetadata = metadata.copy(sewadarId = metadata.sewadarId + 1)

        try {
            cipher.decrypt(encrypted, reassignedMetadata)
            fail("Template must not decrypt when its sewadar metadata changes")
        } catch (_: AEADBadTagException) {
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptionRejectsAnUnexpectedEmbeddingSize() {
        cipher.encrypt(FloatArray(FaceModelContract.EMBEDDING_SIZE - 1), metadata)
    }
}
