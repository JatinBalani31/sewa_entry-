package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.face.HeadTurnLiveness
import org.sewaentry.tablet.face.HeadTurnStage

class HeadTurnLivenessTest {
    @Test
    fun requiresCenterTurnAndReturnBeforePassing() {
        val liveness = HeadTurnLiveness()

        assertEquals(HeadTurnStage.TURN, liveness.update(1, 0f, 100).stage)
        assertEquals(HeadTurnStage.RETURN, liveness.update(1, 18f, 200).stage)
        val passed = liveness.update(1, 2f, 300)
        assertEquals(HeadTurnStage.PASSED, passed.stage)
        assertTrue(passed.passed)
    }

    @Test
    fun acceptsTurnToEitherSideButRequiresReturnToCenter() {
        listOf(-22f, 22f).forEach { sideYaw ->
            val liveness = HeadTurnLiveness()
            assertFalse(liveness.update(1, sideYaw, 100).passed) // A side pose cannot start the challenge.
            assertEquals(HeadTurnStage.TURN, liveness.update(1, 0f, 200).stage)
            assertEquals(HeadTurnStage.RETURN, liveness.update(1, sideYaw, 300).stage)
            assertTrue(liveness.update(1, 1f, 400).passed)
        }
    }

    @Test
    fun yawOutsideAllowedTurnRangeCannotCompleteChallenge() {
        val liveness = HeadTurnLiveness()
        liveness.update(1, 0f, 100)

        assertEquals(HeadTurnStage.TURN, liveness.update(1, 50f, 200).stage)
        assertFalse(liveness.update(1, 0f, 300).passed)
    }

    @Test
    fun invalidYawResetsProgress() {
        val liveness = HeadTurnLiveness()
        liveness.update(1, 0f, 100)
        liveness.update(1, 20f, 200)

        assertEquals(HeadTurnStage.CENTER, liveness.update(1, Float.NaN, 300).stage)
        assertEquals(HeadTurnStage.TURN, liveness.update(1, 0f, 400).stage)
    }

    @Test
    fun passedChallengeExpiresAndRequiresAnotherTurn() {
        val liveness = HeadTurnLiveness()
        liveness.update(1, 0f, 100)
        liveness.update(1, 20f, 200)
        assertTrue(liveness.update(1, 0f, 300).passed)

        val expired = liveness.update(1, 0f, 3_301)
        assertFalse(expired.passed)
        assertEquals(HeadTurnStage.TURN, expired.stage)
    }

    @Test
    fun missingOrMultipleFacesResetChallenge() {
        val liveness = HeadTurnLiveness()
        liveness.update(1, 0f, 100)
        liveness.update(1, 18f, 200)

        val progress = liveness.update(2, null, 250)

        assertEquals(HeadTurnStage.CENTER, progress.stage)
        assertFalse(progress.passed)
    }

    @Test
    fun challengeExpiresAndMustStartAgain() {
        val liveness = HeadTurnLiveness()
        liveness.update(1, 0f, 100)

        val expired = liveness.update(1, 18f, 12_101)

        assertEquals(HeadTurnStage.CENTER, expired.stage)
        assertFalse(expired.passed)
    }
}
