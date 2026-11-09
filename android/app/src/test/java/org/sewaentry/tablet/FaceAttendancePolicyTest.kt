package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.face.AttendanceDirection
import org.sewaentry.tablet.face.FaceAttendancePolicy

class FaceAttendancePolicyTest {
    @Test
    fun firstAttendanceIsInAndEveryLaterScanUpdatesOut() {
        assertEquals(AttendanceDirection.IN, FaceAttendancePolicy.nextDirection(null))
        assertEquals(AttendanceDirection.OUT, FaceAttendancePolicy.nextDirection(AttendanceDirection.IN))
        assertEquals(AttendanceDirection.OUT, FaceAttendancePolicy.nextDirection(AttendanceDirection.OUT))
        val sequence = mutableListOf<AttendanceDirection>()
        repeat(6) {
            sequence += FaceAttendancePolicy.nextDirection(sequence.lastOrNull())
        }
        assertEquals(
            listOf(
                AttendanceDirection.IN,
                AttendanceDirection.OUT,
                AttendanceDirection.OUT,
                AttendanceDirection.OUT,
                AttendanceDirection.OUT,
                AttendanceDirection.OUT,
            ),
            sequence,
        )
    }

    @Test
    fun manualTransitionsMustAlternateInAndOut() {
        assertTrue(FaceAttendancePolicy.isValidTransition(null, AttendanceDirection.IN))
        assertFalse(FaceAttendancePolicy.isValidTransition(null, AttendanceDirection.OUT))
        assertFalse(FaceAttendancePolicy.isValidTransition(AttendanceDirection.IN, AttendanceDirection.IN))
        assertTrue(FaceAttendancePolicy.isValidTransition(AttendanceDirection.IN, AttendanceDirection.OUT))
        assertTrue(FaceAttendancePolicy.isValidTransition(AttendanceDirection.OUT, AttendanceDirection.IN))
        assertFalse(FaceAttendancePolicy.isValidTransition(AttendanceDirection.OUT, AttendanceDirection.OUT))
    }

    @Test
    fun derivesStateFromLegacySummaryWhenNoEventLogExists() {
        assertEquals(AttendanceDirection.IN, FaceAttendancePolicy.resolveLastDirection(null, 100, null))
        assertEquals(AttendanceDirection.OUT, FaceAttendancePolicy.resolveLastDirection(null, 100, 200))
        assertEquals(AttendanceDirection.IN, FaceAttendancePolicy.resolveLastDirection(null, 200, 100))
        assertEquals(AttendanceDirection.OUT, FaceAttendancePolicy.resolveLastDirection("OUT", 100, null))
    }

    @Test
    fun testFiveMinuteCooldownPolicy() {
        val now = 1_000_000L
        // No previous record -> no cooldown
        assertFalse(FaceAttendancePolicy.isCooldownActive(null, now))
        assertEquals(0L, FaceAttendancePolicy.remainingCooldownSeconds(null, now))

        // 1 minute ago -> cooldown active (4 minutes remaining)
        val oneMinAgo = now - 60_000L
        assertTrue(FaceAttendancePolicy.isCooldownActive(oneMinAgo, now))
        assertEquals(240L, FaceAttendancePolicy.remainingCooldownSeconds(oneMinAgo, now))

        // 4 minutes 59 seconds ago -> cooldown active (1 second remaining)
        val justUnderFiveMinAgo = now - (5 * 60 * 1000L - 1000L)
        assertTrue(FaceAttendancePolicy.isCooldownActive(justUnderFiveMinAgo, now))
        assertEquals(1L, FaceAttendancePolicy.remainingCooldownSeconds(justUnderFiveMinAgo, now))

        // 5 minutes ago -> cooldown expired
        val fiveMinAgo = now - 5 * 60 * 1000L
        assertFalse(FaceAttendancePolicy.isCooldownActive(fiveMinAgo, now))
        assertEquals(0L, FaceAttendancePolicy.remainingCooldownSeconds(fiveMinAgo, now))

        // 10 minutes ago -> cooldown expired
        val tenMinAgo = now - 10 * 60 * 1000L
        assertFalse(FaceAttendancePolicy.isCooldownActive(tenMinAgo, now))
        assertEquals(0L, FaceAttendancePolicy.remainingCooldownSeconds(tenMinAgo, now))
    }

}

