package org.sewaentry.tablet.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * Turning raw attendance rows into the hours reports the sangat actually asks
 * for: how long each individual did sewa, sliced by sewa day, week, month or
 * quarter.
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

enum class ReportPeriod(val label: String) {
    OVERALL("Overall"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
}

/** One sewadar's totals within one period bucket. */
data class PeriodTotals(
    val sewadarId: Long,
    val name: String,
    val departmentName: String?,
    val periodKey: String,
    val periodLabel: String,
    val daysAttended: Int,
    val daysComplete: Int,
    val minutes: Long,
) {
    val hours: Double get() = minutes / 60.0
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

    /** "2026-Q3", "2026-W36", "2026-09", or a single bucket for OVERALL. */
    fun periodKey(date: LocalDate, period: ReportPeriod): String = when (period) {
        ReportPeriod.OVERALL -> "all"
        ReportPeriod.WEEKLY -> {
            // ISO weeks, so a week that straddles New Year keeps one identity
            // rather than splitting across two years.
            val fields = WeekFields.ISO
            val week = date.get(fields.weekOfWeekBasedYear())
            val year = date.get(fields.weekBasedYear())
            "%d-W%02d".format(year, week)
        }
        ReportPeriod.MONTHLY -> "%d-%02d".format(date.year, date.monthValue)
        ReportPeriod.QUARTERLY -> "%d-Q%d".format(date.year, (date.monthValue - 1) / 3 + 1)
    }

    fun periodLabel(key: String, period: ReportPeriod): String = when (period) {
        ReportPeriod.OVERALL -> "Overall"
        ReportPeriod.WEEKLY -> key.replace("-W", " week ")
        ReportPeriod.MONTHLY -> {
            val (year, month) = key.split("-")
            val name = java.time.Month.of(month.toInt()).name.lowercase().replaceFirstChar { it.uppercase() }
            "$name $year"
        }
        ReportPeriod.QUARTERLY -> key.replace("-", " ")
    }

    /** Every period bucket present in the data, oldest first. */
    fun periodKeys(rows: List<ExportRow>, period: ReportPeriod): List<String> =
        rows.map { periodKey(SewaDays.parse(it.sewaDate), period) }.distinct().sorted()

    /** One row per sewadar per period bucket. */
    fun byPeriod(rows: List<ExportRow>, period: ReportPeriod): List<PeriodTotals> =
        rows.groupBy { it.sewadarId to periodKey(SewaDays.parse(it.sewaDate), period) }
            .map { (key, group) ->
                val first = group.first()
                PeriodTotals(
                    sewadarId = key.first,
                    name = first.name,
                    departmentName = first.departmentName,
                    periodKey = key.second,
                    periodLabel = periodLabel(key.second, period),
                    daysAttended = group.size,
                    daysComplete = group.count { it.departureAt != null },
                    minutes = group.sumOf { minutesOf(it) },
                )
            }
            .sortedWith(compareBy({ it.name }, { it.periodKey }))

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
        byPeriod(rows, ReportPeriod.MONTHLY).associate { (it.sewadarId to it.periodKey) to it.hours }

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
