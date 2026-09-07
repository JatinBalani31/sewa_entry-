package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.ReportPeriod
import org.sewaentry.tablet.data.Reporting
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarRef
import java.time.LocalDate

/**
 * The hours arithmetic. This is the number that ends up on someone's report,
 * so the edge cases — a forgotten sign-out, a day filter, a week that spans
 * New Year — are pinned down here rather than discovered in a spreadsheet.
 */
class ReportingTest {

    private var nextId = 1L

    private fun row(
        date: String,
        name: String = "Harpreet Singh",
        arrivalHour: Int? = 9,
        departureHour: Int? = 16,
        sewadarId: Long = 1L,
        department: String? = "Accounts",
    ): ExportRow {
        val day = LocalDate.parse(date)
        return ExportRow(
            sewadarId = sewadarId,
            name = name,
            departmentId = 1L,
            departmentName = department,
            attendanceId = nextId++,
            arrivalAt = arrivalHour?.let { SewaDays.timeOnDate(day, it, 0) },
            departureAt = departureHour?.let { SewaDays.timeOnDate(day, it, 0) },
            arrivalEdited = false,
            departureEdited = false,
            sewaDate = date,
        )
    }

    // 2026-09-02 Wed, 2026-09-06 Sun, 2026-09-09 Wed, 2026-09-13 Sun

    @Test
    fun `hours come from paired arrival and departure`() {
        val totals = Reporting.totals(Reporting.filter(listOf(row("2026-09-02")), DayFilter.ALL))

        assertEquals(1, totals.size)
        assertEquals(7.0, totals[0].hours, 0.001)
        assertEquals(1, totals[0].daysAttended)
        assertEquals(1, totals[0].daysComplete)
    }

    @Test
    fun `a day with no sign-out counts as attended but contributes no hours`() {
        val rows = listOf(
            row("2026-09-02"),                              // 7h
            row("2026-09-06", departureHour = null),        // open
        )
        val totals = Reporting.totals(Reporting.filter(rows, DayFilter.ALL))

        assertEquals(2, totals[0].daysAttended)
        assertEquals(1, totals[0].daysComplete)
        assertEquals(1, totals[0].openDays)
        assertEquals("an unclosed day must not inflate the total", 7.0, totals[0].hours, 0.001)
    }

    @Test
    fun `a day never signed in is not attendance at all`() {
        val rows = listOf(row("2026-09-02", arrivalHour = null, departureHour = null))
        assertTrue(Reporting.filter(rows, DayFilter.ALL).isEmpty())
    }

    @Test
    fun `the wednesday filter keeps only wednesdays`() {
        val rows = listOf(row("2026-09-02"), row("2026-09-06"), row("2026-09-09"))
        val filtered = Reporting.filter(rows, DayFilter.WEDNESDAY)

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { LocalDate.parse(it.sewaDate).dayOfWeek.value == 3 })
    }

    @Test
    fun `the sunday filter keeps only sundays`() {
        val rows = listOf(row("2026-09-02"), row("2026-09-06"), row("2026-09-13"))
        val filtered = Reporting.filter(rows, DayFilter.SUNDAY)

        assertEquals(2, filtered.size)
        assertEquals(14.0, Reporting.totals(filtered)[0].hours, 0.001)
    }

    @Test
    fun `weekly buckets split across calendar weeks`() {
        // 02 Sep and 06 Sep are the same ISO week; 09 Sep is the next.
        val rows = listOf(row("2026-09-02"), row("2026-09-06"), row("2026-09-09"))
        val byPeriod = Reporting.byPeriod(Reporting.filter(rows, DayFilter.ALL), ReportPeriod.WEEKLY)

        assertEquals(2, byPeriod.size)
        assertEquals(14.0, byPeriod.first { it.periodKey.endsWith("W36") }.hours, 0.001)
        assertEquals(7.0, byPeriod.first { it.periodKey.endsWith("W37") }.hours, 0.001)
    }

    @Test
    fun `an iso week spanning new year keeps one identity`() {
        // 2026-12-30 (Wed) and 2027-01-03 (Sun) fall in the same ISO week.
        val rows = listOf(row("2026-12-30"), row("2027-01-03"))
        val keys = Reporting.periodKeys(Reporting.filter(rows, DayFilter.ALL), ReportPeriod.WEEKLY)

        assertEquals("the week must not split across two years", 1, keys.size)
    }

    @Test
    fun `quarterly buckets follow calendar quarters`() {
        val rows = listOf(row("2026-03-29"), row("2026-09-06"), row("2026-12-30"))
        val keys = Reporting.periodKeys(Reporting.filter(rows, DayFilter.ALL), ReportPeriod.QUARTERLY)

        assertEquals(listOf("2026-Q1", "2026-Q3", "2026-Q4"), keys)
    }

    @Test
    fun `monthly buckets are labelled readably`() {
        assertEquals("September 2026", Reporting.periodLabel("2026-09", ReportPeriod.MONTHLY))
        assertEquals("2026 Q3", Reporting.periodLabel("2026-Q3", ReportPeriod.QUARTERLY))
        assertEquals("2026 week 36", Reporting.periodLabel("2026-W36", ReportPeriod.WEEKLY))
    }

    @Test
    fun `totals are kept per sewadar`() {
        val rows = listOf(
            row("2026-09-02", name = "Harpreet Singh", sewadarId = 1),
            row("2026-09-06", name = "Harpreet Singh", sewadarId = 1),
            row("2026-09-02", name = "Manjit Kaur", sewadarId = 2, departureHour = 12),
        )
        val totals = Reporting.totals(Reporting.filter(rows, DayFilter.ALL))

        assertEquals(2, totals.size)
        assertEquals(3.0, totals.first { it.name == "Manjit Kaur" }.hours, 0.001)
        assertEquals(14.0, totals.first { it.name == "Harpreet Singh" }.hours, 0.001)
    }

    @Test
    fun `someone who did no sewa still appears with zero`() {
        val rows = listOf(row("2026-09-02", name = "Harpreet Singh", sewadarId = 1))
        val roster = listOf(
            SewadarRef(1, "Harpreet Singh", "Accounts"),
            SewadarRef(2, "Absent Person", "Accounts"),
        )
        val totals = Reporting.totals(Reporting.filter(rows, DayFilter.ALL), roster)

        assertEquals(2, totals.size)
        val absent = totals.first { it.name == "Absent Person" }
        assertEquals(0, absent.daysAttended)
        assertEquals(0.0, absent.hours, 0.001)
        assertEquals(null, absent.lastAttended)
    }

    @Test
    fun `last attended is the most recent day in range`() {
        val rows = listOf(row("2026-09-02"), row("2026-09-13"), row("2026-09-06"))
        val totals = Reporting.totals(Reporting.filter(rows, DayFilter.ALL))

        assertEquals("2026-09-13", totals[0].lastAttended)
    }

    @Test
    fun `overall grouping collapses everything into one bucket`() {
        val rows = listOf(row("2026-03-29"), row("2026-09-06"))
        val keys = Reporting.periodKeys(Reporting.filter(rows, DayFilter.ALL), ReportPeriod.OVERALL)

        assertEquals(1, keys.size)
    }

    @Test
    fun `a departure before the arrival cannot produce negative hours`() {
        // Should not happen — the repository refuses it — but a report must
        // never emit a negative total even if a row somehow gets through.
        val bad = row("2026-09-02", arrivalHour = 16, departureHour = 9)
        val totals = Reporting.totals(Reporting.filter(listOf(bad), DayFilter.ALL))

        assertTrue("hours must never go negative", totals[0].hours >= 0.0)
    }
}
