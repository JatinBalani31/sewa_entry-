package org.sewaentry.tablet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.face.FaceEnrollmentPolicy

class FaceEnrollmentPolicyTest {
    @Test
    fun enrollmentAcceptsOnlyThreeThroughFiveSamples() {
        assertFalse(FaceEnrollmentPolicy.isValidSampleCount(2))
        assertTrue(FaceEnrollmentPolicy.isValidSampleCount(3))
        assertTrue(FaceEnrollmentPolicy.isValidSampleCount(4))
        assertTrue(FaceEnrollmentPolicy.isValidSampleCount(5))
        assertFalse(FaceEnrollmentPolicy.isValidSampleCount(6))
    }

    @Test
    fun newEnrollmentsDefaultToFiveSamples() {
        assertEquals(5, FaceEnrollmentPolicy.DEFAULT_SAMPLES)
        assertTrue(FaceEnrollmentPolicy.isValidSampleCount(FaceEnrollmentPolicy.DEFAULT_SAMPLES))
    }
}
