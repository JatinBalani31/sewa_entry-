package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.Quarter
import org.sewaentry.tablet.data.ExportRow
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
    fun `a departure before the arrival cannot produce negative hours`() {
        // Should not happen — the repository refuses it — but a report must
        // never emit a negative total even if a row somehow gets through.
        val bad = row("2026-09-02", arrivalHour = 16, departureHour = 9)
        val totals = Reporting.totals(Reporting.filter(listOf(bad), DayFilter.ALL))

        assertTrue("hours must never go negative", totals[0].hours >= 0.0)
    }

    // --- Quarters: the unit the summary is actually prepared in -------------

    @Test
    fun `a quarter spans its three months exactly`() {
        val q3 = Quarter(2026, 3)

        assertEquals(LocalDate.of(2026, 7, 1), q3.start)
        assertEquals(LocalDate.of(2026, 9, 30), q3.end)
        assertEquals("2026 Q3", q3.label)
        assertEquals("Jul-Sep", q3.monthsLabel)
    }

    @Test
    fun `the fourth quarter ends on the last day of the year`() {
        val q4 = Quarter(2026, 4)

        assertEquals(LocalDate.of(2026, 10, 1), q4.start)
        assertEquals(LocalDate.of(2026, 12, 31), q4.end)
    }

    @Test
    fun `a date resolves to the quarter containing it`() {
        assertEquals(Quarter(2026, 1), Quarter.of(LocalDate.of(2026, 3, 31)))
        assertEquals(Quarter(2026, 2), Quarter.of(LocalDate.of(2026, 4, 1)))
        assertEquals(Quarter(2026, 4), Quarter.of(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `a quarter names its three month columns`() {
        val q1 = Quarter(2026, 1)

        assertEquals(listOf("2026-01", "2026-02", "2026-03"), q1.monthKeys)
        assertEquals(listOf("Jan", "Feb", "Mar"), q1.monthLabels)
    }

    // --- Department grouping, the shape of the paper summary ----------------

    @Test
    fun `departments group with their own subtotals`() {
        val rows = listOf(
            row("2026-09-02", name = "Harpreet Singh", sewadarId = 1, department = "Accounts"),
            row("2026-09-06", name = "Harpreet Singh", sewadarId = 1, department = "Accounts"),
            row("2026-09-02", name = "Manjit Kaur", sewadarId = 2, department = "Accounts", departureHour = 12),
            row("2026-09-02", name = "Lata Kamble", sewadarId = 3, department = "Safai"),
        )
        val groups = Reporting.byDepartment(Reporting.totals(Reporting.filter(rows, DayFilter.ALL)))

        assertEquals(2, groups.size)
        assertEquals("Accounts", groups[0].departmentName)
        assertEquals("Safai", groups[1].departmentName)

        // Accounts: Harpreet 7+7, Manjit 3
        assertEquals(17.0, groups[0].hours, 0.001)
        assertEquals(3, groups[0].daysAttended)
        assertEquals(2, groups[0].activeCount)

        assertEquals(7.0, groups[1].hours, 0.001)
    }

    @Test
    fun `a sewadar with no department still lands in a named group`() {
        val rows = listOf(row("2026-09-02", department = null))
        val groups = Reporting.byDepartment(Reporting.totals(Reporting.filter(rows, DayFilter.ALL)))

        assertEquals("No department", groups[0].departmentName)
    }

    @Test
    fun `a department subtotal excludes people who did no sewa from its active count`() {
        val rows = listOf(row("2026-09-02", name = "Harpreet Singh", sewadarId = 1))
        val roster = listOf(
            SewadarRef(1, "Harpreet Singh", "Accounts"),
            SewadarRef(2, "Absent Person", "Accounts"),
        )
        val groups = Reporting.byDepartment(Reporting.totals(Reporting.filter(rows, DayFilter.ALL), roster))

        assertEquals("both appear on the sheet", 2, groups[0].sewadars.size)
        assertEquals("but only one actually attended", 1, groups[0].activeCount)
    }

    @Test
    fun `monthly hours are keyed for the quarter's month columns`() {
        val rows = listOf(
            row("2026-07-01", sewadarId = 1),
            row("2026-08-05", sewadarId = 1),
            row("2026-08-12", sewadarId = 1),
        )
        val monthly = Reporting.monthlyHours(Reporting.filter(rows, DayFilter.ALL))

        assertEquals(7.0, monthly[1L to "2026-07"] ?: 0.0, 0.001)
        assertEquals(14.0, monthly[1L to "2026-08"] ?: 0.0, 0.001)
        assertEquals("a month with no sewa is simply absent", null, monthly[1L to "2026-09"])
    }

    @Test
    fun `sewa dates in a period are distinct and ordered`() {
        val rows = listOf(
            row("2026-09-06", sewadarId = 1),
            row("2026-09-06", sewadarId = 2),
            row("2026-09-02", sewadarId = 1),
        )
        assertEquals(
            listOf("2026-09-02", "2026-09-06"),
            Reporting.sewaDatesIn(Reporting.filter(rows, DayFilter.ALL)),
        )
    }
}
