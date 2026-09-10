package org.sewaentry.tablet.data

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Turning raw attendance rows into the hours reports the sangat actually asks
 * for: how long an individual sewadar, or one department, did sewa over a
 * chosen period.
 *
 * Everything here is a pure function over a list of rows — no database, no
 * Android — so the arithmetic that ends up in someone's hours total can be
 * tested directly.
 */

enum class DayFilter(val label: String) {
    ALL("All sewa days"),
    WEDNESDAY("Wednesdays"),
    SUNDAY("Sundays"),
    ;

    fun matches(date: LocalDate): Boolean = when (this) {
        ALL -> true
        WEDNESDAY -> date.dayOfWeek == DayOfWeek.WEDNESDAY
        SUNDAY -> date.dayOfWeek == DayOfWeek.SUNDAY
    }
}

/** A sewadar's total across the whole filtered range. */
data class SewadarTotals(
    val sewadarId: Long,
    val name: String,
    val departmentName: String?,
    val daysAttended: Int,
    val daysComplete: Int,
    val minutes: Long,
    val lastAttended: String?,
) {
    val hours: Double get() = minutes / 60.0
    /** Days signed in but never signed out — hours cannot be counted for these. */
    val openDays: Int get() = daysAttended - daysComplete
}

object Reporting {

    /**
     * Only days with an arrival count as attendance. Minutes accrue solely
     * from days that were also signed out, so a forgotten departure shows up
     * as an open day rather than silently inflating someone's hours.
     */
    private fun minutesOf(row: ExportRow): Long =
        SewaDays.minutesBetween(row.arrivalAt, row.departureAt)?.coerceAtLeast(0) ?: 0

    fun filter(rows: List<ExportRow>, dayFilter: DayFilter): List<ExportRow> =
        rows.filter { it.arrivalAt != null && dayFilter.matches(SewaDays.parse(it.sewaDate)) }

    /** "yyyy-MM" for a date - the column key inside a quarterly summary. */
    fun monthKey(date: LocalDate): String = "%d-%02d".format(date.year, date.monthValue)

    /**
     * One row per sewadar across the whole range.
     *
     * `roster` is folded in so that somebody who did no sewa in the period
     * still appears with a zero — an absence is a reportable fact, and a
     * report that simply omits them hides it.
     */
    fun totals(
        rows: List<ExportRow>,
        roster: List<SewadarRef> = emptyList(),
    ): List<SewadarTotals> {
        val bySewadar = rows.groupBy { it.sewadarId }

        val recorded = bySewadar.map { (id, group) ->
            val first = group.first()
            SewadarTotals(
                sewadarId = id,
                name = first.name,
                departmentName = first.departmentName,
                daysAttended = group.size,
                daysComplete = group.count { it.departureAt != null },
                minutes = group.sumOf { minutesOf(it) },
                lastAttended = group.maxOf { it.sewaDate },
            )
        }

        val missing = roster
            .filter { it.id !in bySewadar.keys }
            .map {
                SewadarTotals(
                    sewadarId = it.id,
                    name = it.name,
                    departmentName = it.departmentName,
                    daysAttended = 0,
                    daysComplete = 0,
                    minutes = 0,
                    lastAttended = null,
                )
            }

        return (recorded + missing).sortedBy { it.name }
    }

    /** Group the totals department by department, the way the summary reads. */
    fun byDepartment(totals: List<SewadarTotals>): List<DepartmentGroup> =
        totals.groupBy { it.departmentName ?: "No department" }
            .map { (name, people) -> DepartmentGroup(name, people.sortedBy { it.name }) }
            .sortedBy { it.departmentName }

    /**
     * Hours per sewadar per month, keyed "yyyy-MM" — the three columns inside
     * a quarterly summary.
     */
    fun monthlyHours(rows: List<ExportRow>): Map<Pair<Long, String>, Double> =
        rows.groupBy { it.sewadarId to monthKey(SewaDays.parse(it.sewaDate)) }
            .mapValues { (_, group) -> group.sumOf { minutesOf(it) } / 60.0 }

    /** Distinct sewa dates present, so a summary can state how many days it covers. */
    fun sewaDatesIn(rows: List<ExportRow>): List<String> =
        rows.map { it.sewaDate }.distinct().sorted()
}

/** Minimal identity for a sewadar, used to include non-attenders in a report. */
data class SewadarRef(
    val id: Long,
    val name: String,
    val departmentName: String?,
)

/**
 * A calendar quarter — the unit the sangat's summary is actually prepared in.
 *
 * Selecting one sets the whole reporting range, so producing "the Q3 summary"
 * is a single choice rather than a date range someone has to get right.
 */
data class Quarter(val year: Int, val quarter: Int) {
    val start: LocalDate get() = LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
    val end: LocalDate get() = start.plusMonths(3).minusDays(1)

    val label: String get() = "$year Q$quarter"

    /** "Jan-Mar", "Jul-Sep" — the months a reader recognises the quarter by. */
    val monthsLabel: String
        get() {
            val first = java.time.Month.of(start.monthValue).name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val last = java.time.Month.of(end.monthValue).name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            return "$first-$last"
        }

    /** The three months inside this quarter, as "yyyy-MM" keys. */
    val monthKeys: List<String>
        get() = (0..2).map { "%d-%02d".format(year, start.plusMonths(it.toLong()).monthValue) }

    val monthLabels: List<String>
        get() = (0..2).map {
            java.time.Month.of(start.plusMonths(it.toLong()).monthValue)
                .name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }
        }

    companion object {
        fun of(date: LocalDate): Quarter = Quarter(date.year, (date.monthValue - 1) / 3 + 1)

        fun current(): Quarter = of(LocalDate.now())
    }
}

/**
 * One department's block in the summary: its people, then its subtotal.
 *
 * The paper summary is organised department by department with a total under
 * each, so the digital one is too — it has to be recognisable to whoever used
 * to prepare it by hand.
 */
data class DepartmentGroup(
    val departmentName: String,
    val sewadars: List<SewadarTotals>,
) {
    val daysAttended: Int get() = sewadars.sumOf { it.daysAttended }
    val daysComplete: Int get() = sewadars.sumOf { it.daysComplete }
    val minutes: Long get() = sewadars.sumOf { it.minutes }
    val hours: Double get() = minutes / 60.0
    val openDays: Int get() = sewadars.sumOf { it.openDays }
    /** People in this department who actually did some sewa in the period. */
    val activeCount: Int get() = sewadars.count { it.daysAttended > 0 }
}
