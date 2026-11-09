package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.AdminLock
import org.sewaentry.tablet.data.AdminPinLockout
import org.sewaentry.tablet.data.PinLockoutState

class AdminLockTest {

    @Test
    fun `six digits is a valid PIN`() {
        assertTrue(AdminLock.isPlausible("000000"))
        assertTrue(AdminLock.isPlausible("123456"))
        assertTrue(AdminLock.isPlausible("999999"))
    }

    @Test
    fun `anything other than six digits is not a new PIN`() {
        assertFalse(AdminLock.isPlausible(""))
        assertFalse(AdminLock.isPlausible("12345"))
        assertFalse(AdminLock.isPlausible("1234567"))
        assertFalse(AdminLock.isPlausible("abcdef"))
        assertFalse(AdminLock.isPlausible("12a456"))
        assertFalse(AdminLock.isPlausible("12 456"))
        assertFalse(AdminLock.isPlausible("-12345"))
    }

    @Test
    fun `four digit PINs are accepted only by the legacy migration flow`() {
        assertTrue(AdminLock.isLegacyPinPlausible("0000"))
        assertTrue(AdminLock.isLegacyPinPlausible("1234"))
        assertFalse(AdminLock.isLegacyPinPlausible("12345"))
        assertFalse(AdminLock.isPlausible("1234"))
    }

    @Test
    fun `third wrong attempt starts a five minute cooldown`() {
        val now = 10_000L
        val first = AdminPinLockout.recordFailure(PinLockoutState(), now)
        val second = AdminPinLockout.recordFailure(first, now)
        val third = AdminPinLockout.recordFailure(second, now)

        assertEquals(0, first.lockoutCount)
        assertEquals(0, second.lockoutCount)
        assertEquals(1, third.lockoutCount)
        assertEquals(5 * 60 * 1000L, AdminPinLockout.remainingMillis(third, now))
    }

    @Test
    fun `repeated lockouts last longer and failures during cooldown do not change them`() {
        val now = 10_000L
        var state = PinLockoutState()
        repeat(3) { state = AdminPinLockout.recordFailure(state, now) }
        val firstLockout = state

        assertEquals(firstLockout, AdminPinLockout.recordFailure(firstLockout, now + 1_000L))

        val unlockedAt = firstLockout.lockedUntilMillis
        repeat(3) { state = AdminPinLockout.recordFailure(state, unlockedAt) }

        assertEquals(2, state.lockoutCount)
        assertEquals(15 * 60 * 1000L, AdminPinLockout.remainingMillis(state, unlockedAt))
    }
}
