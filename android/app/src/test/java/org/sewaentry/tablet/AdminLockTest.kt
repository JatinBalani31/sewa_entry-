package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.AdminLock

/**
 * The PIN rules. Storage and hashing need a device, so what is checked here is
 * the part that decides whether something counts as a PIN at all — the rule
 * that stops "12" or "abcd" being set as one and quietly locking the admin out.
 */
class AdminLockTest {

    @Test
    fun `four digits is a PIN`() {
        assertTrue(AdminLock.isPlausible("0000"))
        assertTrue(AdminLock.isPlausible("1234"))
        assertTrue(AdminLock.isPlausible("9999"))
    }

    @Test
    fun `anything that is not four digits is not`() {
        assertFalse(AdminLock.isPlausible(""))
        assertFalse(AdminLock.isPlausible("123"))
        assertFalse(AdminLock.isPlausible("12345"))
        assertFalse(AdminLock.isPlausible("abcd"))
        assertFalse(AdminLock.isPlausible("12a4"))
        assertFalse(AdminLock.isPlausible("12 4"))
        assertFalse(AdminLock.isPlausible("-123"))
    }

    @Test
    fun `the PIN is four digits long`() {
        // The keypad submits as soon as this many digits are entered, so the
        // two have to agree.
        assertEquals(4, AdminLock.LENGTH)
    }

}
