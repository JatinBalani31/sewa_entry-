package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.sewaentry.tablet.face.ConsecutiveFaceMatchConfirmation
import org.sewaentry.tablet.face.FaceAttendancePolicy
import org.sewaentry.tablet.face.FaceGalleryTemplate
import org.sewaentry.tablet.face.FaceMatch
import org.sewaentry.tablet.face.FaceModelContract
import org.sewaentry.tablet.face.FaceRecognitionMatcher
import kotlin.math.sqrt

class FaceRecognitionMatcherTest {
    private val matcher = FaceRecognitionMatcher()

    @Test
    fun choosesBestOfMultipleTemplatesForTheSamePerson() {
        val probe = vector(1f, 0f)
        val gallery = listOf(
            template(11, "Asha", vector(0.70f, sqrt(1f - 0.70f * 0.70f))),
            template(11, "Asha", vector(0.90f, sqrt(1f - 0.90f * 0.90f))),
            template(22, "Bala", vector(0.65f, sqrt(1f - 0.65f * 0.65f))),
        )

        val match = matcher.findMatch(probe, gallery)

        assertNotNull(match)
        assertEquals(11L, match?.sewadarId)
        assertEquals(0.90f, match?.score ?: 0f, 0.0001f)
    }

    @Test
    fun rejectsScoresBelowKioskThreshold() {
        val gallery = listOf(
            template(11, "Asha", vector(0.61f, sqrt(1f - 0.61f * 0.61f))),
        )

        assertNull(matcher.findMatch(vector(1f, 0f), gallery))
        assertEquals(0.62f, FaceAttendancePolicy.MATCH_THRESHOLD)
    }

    @Test
    fun confirmsOnlyTwoConsecutiveHitsForOnePerson() {
        val confirmation = ConsecutiveFaceMatchConfirmation()
        val first = FaceMatch(11, "Asha", null, 0.75f)
        val switch = FaceMatch(22, "Bala", null, 0.80f)

        assertNull(confirmation.accept(first))
        assertNull(confirmation.accept(switch))
        assertNull(confirmation.accept(first))
        assertNull(confirmation.accept(switch))
        assertEquals(22L, confirmation.accept(switch)?.sewadarId)
    }

    @Test
    fun aNoMatchBreaksConsecutiveConfirmation() {
        val confirmation = ConsecutiveFaceMatchConfirmation()
        val match = FaceMatch(11, "Asha", null, 0.75f)

        assertNull(confirmation.accept(match))
        assertNull(confirmation.accept(null))
        assertNull(confirmation.accept(match))
    }

    private fun template(id: Long, name: String, embedding: FloatArray) =
        FaceGalleryTemplate(id, name, null, embedding)

    private fun vector(first: Float, second: Float): FloatArray =
        FloatArray(FaceModelContract.EMBEDDING_SIZE).apply {
            this[0] = first
            this[1] = second
        }
}
