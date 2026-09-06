package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.SewaDays
import java.time.LocalDate

/**
 * 2026-09-06 is a Sunday. The dates around it are used throughout:
 *   Wed 2026-09-02, Sun 2026-09-06, Mon 07, Tue 08, Wed 09
 */
class SewaDaysTest {

    @Test
    fun `sewa days are wednesday and sunday`() {
        assertTrue(SewaDays.isSewaDay(LocalDate.of(2026, 9, 6)))  // Sunday
        assertTrue(SewaDays.isSewaDay(LocalDate.of(2026, 9, 9)))  // Wednesday
        assertFalse(SewaDays.isSewaDay(LocalDate.of(2026, 9, 7))) // Monday
        assertFalse(SewaDays.isSewaDay(LocalDate.of(2026, 9, 5))) // Saturday
    }

    @Test
    fun `current sewa day is today when today is one`() {
        assertEquals(
            LocalDate.of(2026, 9, 6),
            SewaDays.currentOrPrevious(LocalDate.of(2026, 9, 6)),
        )
    }

    @Test
    fun `current sewa day falls back to the most recent one`() {
        // Tuesday falls back to the preceding Sunday, not forward to Wednesday.
        assertEquals(
            LocalDate.of(2026, 9, 6),
            SewaDays.currentOrPrevious(LocalDate.of(2026, 9, 8)),
        )
    }

    @Test
    fun `dates round-trip through the stored string form`() {
        val date = LocalDate.of(2026, 9, 6)
        assertEquals("2026-09-06", SewaDays.format(date))
        assertEquals(date, SewaDays.parse(SewaDays.format(date)))
    }

    @Test
    fun `a time set on a date reads back as that same clock time`() {
        val date = LocalDate.of(2026, 9, 6)
        val instant = SewaDays.timeOnDate(date, 9, 30)
        // Goes through the device's timezone in both directions, so this also
        // guards against an off-by-one-day drift.
        assertEquals("09:30", SewaDays.formatTime(instant))
    }

    @Test
    fun `duration is computed from the two ends`() {
        val date = LocalDate.of(2026, 9, 6)
        val arrival = SewaDays.timeOnDate(date, 9, 30)
        val departure = SewaDays.timeOnDate(date, 16, 15)

        assertEquals(405L, SewaDays.minutesBetween(arrival, departure))
        assertEquals("6h 45m", SewaDays.formatDuration(405L))
    }

    @Test
    fun `an unfinished day has no duration rather than a wrong one`() {
        val arrival = SewaDays.timeOnDate(LocalDate.of(2026, 9, 6), 9, 30)
        assertEquals(null, SewaDays.minutesBetween(arrival, null))
        assertEquals("--", SewaDays.formatDuration(null))
    }

    @Test
    fun `a whole number of hours drops the minutes`() {
        assertEquals("7h", SewaDays.formatDuration(420L))
    }

    @Test
    fun `a missing time renders as blank rather than epoch zero`() {
        assertEquals("--:--", SewaDays.formatTime(null))
    }
}
