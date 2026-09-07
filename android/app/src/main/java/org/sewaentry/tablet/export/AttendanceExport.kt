package org.sewaentry.tablet.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.DepartmentGroup
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.PeriodTotals
import org.sewaentry.tablet.data.Quarter
import org.sewaentry.tablet.data.ReportPeriod
import org.sewaentry.tablet.data.Reporting
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarTotals
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The hours report.
 *
 * The point of the whole system is answering "how many hours did this person
 * do", so the workbook leads with per-sewadar totals, then breaks those down
 * by whichever period was asked for, and only then shows the raw register.
 */
object AttendanceExport {

    data class Result(val fileName: String, val location: String)

    fun build(
        quarter: Quarter,
        departments: List<DepartmentGroup>,
        monthlyHours: Map<Pair<Long, String>, Double>,
        sewaDates: List<String>,
        totals: List<SewadarTotals>,
        byPeriod: List<PeriodTotals>,
        periodKeys: List<String>,
        rows: List<ExportRow>,
        from: LocalDate,
        to: LocalDate,
        dayFilter: DayFilter,
        period: ReportPeriod,
        departmentName: String?,
    ): List<XlsxWriter.Sheet> {
        val sheets = mutableListOf<XlsxWriter.Sheet>()

        // --- 0. The summary --------------------------------------------------
        // This sheet is the deliverable: the document that used to be prepared
        // by hand from a stack of paper and a typed-up spreadsheet. It leads
        // the workbook, is organised department by department with a subtotal
        // under each, and carries the three months of the quarter as columns.
        sheets += quarterlySummarySheet(quarter, departments, monthlyHours, sewaDates, from, to)

        // --- 1. Hours per sewadar -------------------------------------------
        sheets += XlsxWriter.Sheet(
            name = "Hours by Sewadar",
            headers = listOf(
                "Sewadar", "Department", "Days attended", "Days complete",
                "Open days", "Total hours", "Average hours", "Last sewa",
            ),
            columnWidths = listOf(26, 20, 14, 14, 11, 12, 14, 13),
            rows = totals.map { stat ->
                listOf(
                    XlsxWriter.Cell.Text(stat.name),
                    XlsxWriter.Cell.Text(stat.departmentName ?: "--"),
                    XlsxWriter.Cell.Number(stat.daysAttended.toDouble()),
                    XlsxWriter.Cell.Number(stat.daysComplete.toDouble()),
                    XlsxWriter.Cell.Number(stat.openDays.toDouble()),
                    XlsxWriter.Cell.Number(round2(stat.hours)),
                    XlsxWriter.Cell.Number(
                        if (stat.daysComplete > 0) round2(stat.hours / stat.daysComplete) else 0.0,
                    ),
                    XlsxWriter.Cell.Text(stat.lastAttended ?: "--"),
                )
            },
        )

        // --- 2. The period breakdown ----------------------------------------
        // Sewadars down the side, periods across the top — the shape someone
        // actually reads when comparing week to week or quarter to quarter.
        if (period != ReportPeriod.OVERALL && periodKeys.isNotEmpty()) {
            val byKey = byPeriod.associateBy { it.sewadarId to it.periodKey }
            val people = totals.map { it.sewadarId to it.name }

            sheets += XlsxWriter.Sheet(
                name = "${period.label} hours",
                headers = listOf("Sewadar", "Department") +
                    periodKeys.map { Reporting.periodLabel(it, period) } +
                    listOf("Total"),
                columnWidths = listOf(26, 20) + periodKeys.map { 14 } + listOf(12),
                rows = people.map { (id, name) ->
                    val cells = mutableListOf<XlsxWriter.Cell>(
                        XlsxWriter.Cell.Text(name),
                        XlsxWriter.Cell.Text(totals.first { it.sewadarId == id }.departmentName ?: "--"),
                    )
                    var runningTotal = 0.0
                    periodKeys.forEach { key ->
                        val hours = byKey[id to key]?.hours ?: 0.0
                        runningTotal += hours
                        cells += XlsxWriter.Cell.Number(round2(hours))
                    }
                    cells += XlsxWriter.Cell.Number(round2(runningTotal))
                    cells
                },
            )

            // The same numbers long-form, which is what a pivot table wants.
            sheets += XlsxWriter.Sheet(
                name = "${period.label} detail",
                headers = listOf("Sewadar", "Department", period.label, "Days", "Complete", "Hours"),
                columnWidths = listOf(26, 20, 18, 10, 11, 11),
                rows = byPeriod.map {
                    listOf(
                        XlsxWriter.Cell.Text(it.name),
                        XlsxWriter.Cell.Text(it.departmentName ?: "--"),
                        XlsxWriter.Cell.Text(it.periodLabel),
                        XlsxWriter.Cell.Number(it.daysAttended.toDouble()),
                        XlsxWriter.Cell.Number(it.daysComplete.toDouble()),
                        XlsxWriter.Cell.Number(round2(it.hours)),
                    )
                },
            )
        }

        // --- 3. The register itself -----------------------------------------
        sheets += XlsxWriter.Sheet(
            name = "Register",
            headers = listOf(
                "Date", "Day", "Department", "Sewadar",
                "Arrival", "Departure", "Hours", "Entered by hand",
            ),
            columnWidths = listOf(13, 11, 20, 26, 10, 11, 10, 16),
            rows = rows.map { row ->
                val minutes = SewaDays.minutesBetween(row.arrivalAt, row.departureAt)
                val edits = buildList {
                    if (row.arrivalEdited) add("arrival")
                    if (row.departureEdited) add("departure")
                }
                listOf(
                    XlsxWriter.Cell.Text(row.sewaDate),
                    XlsxWriter.Cell.Text(dayName(row.sewaDate)),
                    XlsxWriter.Cell.Text(row.departmentName ?: "--"),
                    XlsxWriter.Cell.Text(row.name),
                    XlsxWriter.Cell.Text(SewaDays.formatTime(row.arrivalAt)),
                    XlsxWriter.Cell.Text(SewaDays.formatTime(row.departureAt)),
                    if (minutes != null) XlsxWriter.Cell.Number(round2(minutes / 60.0)) else XlsxWriter.Cell.Text("--"),
                    XlsxWriter.Cell.Text(if (edits.isEmpty()) "" else edits.joinToString(", ")),
                )
            },
        )

        // --- 4. What this report is -----------------------------------------
        sheets += XlsxWriter.Sheet(
            name = "Report Info",
            headers = listOf("Field", "Value"),
            columnWidths = listOf(24, 44),
            rows = listOf(
                row("Generated", LocalDateTime.now().toString()),
                row("Quarter", "${quarter.label} (${quarter.monthsLabel})"),
                row("Sewa days covered", sewaDates.size.toString()),
                row("Period start", SewaDays.format(from)),
                row("Period end", SewaDays.format(to)),
                row("Sewa days included", dayFilter.label),
                row("Grouping", period.label),
                row("Department", departmentName ?: "All departments"),
                row("Sewadars", totals.size.toString()),
                row("Attendance rows", rows.size.toString()),
                row("Total hours", round2(totals.sumOf { it.hours }).toString()),
                row(
                    "Note",
                    "Hours count only days with both an arrival and a departure. " +
                        "Days signed in but never signed out appear as open days.",
                ),
            ),
        )

        return sheets
    }

    /**
     * The quarterly summary, laid out the way the hand-prepared one is:
     * department by department, a line per sewadar, a subtotal under each
     * department, and a grand total at the bottom. The three months of the
     * quarter sit as columns so the shape of someone's attendance is visible
     * without opening another sheet.
     */
    private fun quarterlySummarySheet(
        quarter: Quarter,
        departments: List<DepartmentGroup>,
        monthlyHours: Map<Pair<Long, String>, Double>,
        sewaDates: List<String>,
        from: LocalDate,
        to: LocalDate,
    ): XlsxWriter.Sheet {
        val monthKeys = quarter.monthKeys
        val monthLabels = quarter.monthLabels

        val rows = mutableListOf<List<XlsxWriter.Cell>>()

        departments.forEach { group ->
            group.sewadars.forEach { person ->
                val cells = mutableListOf<XlsxWriter.Cell>(
                    XlsxWriter.Cell.Text(group.departmentName),
                    XlsxWriter.Cell.Text(person.name),
                    XlsxWriter.Cell.Number(person.daysAttended.toDouble()),
                )
                monthKeys.forEach { key ->
                    cells += XlsxWriter.Cell.Number(round2(monthlyHours[person.sewadarId to key] ?: 0.0))
                }
                cells += XlsxWriter.Cell.Number(round2(person.hours))
                cells += XlsxWriter.Cell.Number(
                    if (person.daysComplete > 0) round2(person.hours / person.daysComplete) else 0.0,
                )
                cells += if (person.openDays > 0) {
                    XlsxWriter.Cell.Number(person.openDays.toDouble())
                } else {
                    XlsxWriter.Cell.Text("")
                }
                rows += cells
            }

            // Subtotal line, so each department can be read on its own.
            val subtotal = mutableListOf<XlsxWriter.Cell>(
                XlsxWriter.Cell.Text(group.departmentName),
                XlsxWriter.Cell.Text("TOTAL - ${group.activeCount} sewadar(s)"),
                XlsxWriter.Cell.Number(group.daysAttended.toDouble()),
            )
            monthKeys.forEach { key ->
                val monthTotal = group.sewadars.sumOf { monthlyHours[it.sewadarId to key] ?: 0.0 }
                subtotal += XlsxWriter.Cell.Number(round2(monthTotal))
            }
            subtotal += XlsxWriter.Cell.Number(round2(group.hours))
            subtotal += XlsxWriter.Cell.Text("")
            subtotal += if (group.openDays > 0) XlsxWriter.Cell.Number(group.openDays.toDouble()) else XlsxWriter.Cell.Text("")
            rows += subtotal
        }

        if (departments.isNotEmpty()) {
            val grand = mutableListOf<XlsxWriter.Cell>(
                XlsxWriter.Cell.Text("ALL DEPARTMENTS"),
                XlsxWriter.Cell.Text("GRAND TOTAL"),
                XlsxWriter.Cell.Number(departments.sumOf { it.daysAttended }.toDouble()),
            )
            monthKeys.forEach { key ->
                val monthTotal = departments.sumOf { group ->
                    group.sewadars.sumOf { monthlyHours[it.sewadarId to key] ?: 0.0 }
                }
                grand += XlsxWriter.Cell.Number(round2(monthTotal))
            }
            grand += XlsxWriter.Cell.Number(round2(departments.sumOf { it.hours }))
            grand += XlsxWriter.Cell.Text("")
            grand += XlsxWriter.Cell.Number(departments.sumOf { it.openDays }.toDouble())
            rows += grand
        }

        return XlsxWriter.Sheet(
            name = "Summary ${quarter.label}",
            headers = listOf("Department", "Sewadar", "Days") +
                monthLabels.map { "$it hrs" } +
                listOf("Total hours", "Avg / day", "Open days"),
            columnWidths = listOf(22, 26, 8) + monthLabels.map { 10 } + listOf(12, 11, 11),
            rows = rows,
        )
    }

    private fun row(label: String, value: String) =
        listOf(XlsxWriter.Cell.Text(label), XlsxWriter.Cell.Text(value))

    private fun dayName(sewaDate: String): String =
        SewaDays.parse(sewaDate).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Writes to the device's Downloads folder. On Android 10+ that goes
     * through MediaStore, which needs no storage permission at all; older
     * versions fall back to the public directory.
     */
    fun save(context: Context, fileName: String, sheets: List<XlsxWriter.Sheet>): Result {
        val writer = XlsxWriter()
        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file in Downloads.")
            resolver.openOutputStream(uri)?.use { writer.write(it, sheets) }
                ?: error("Could not open the file for writing.")
            return Result(fileName, "Downloads")
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { writer.write(it, sheets) }
        return Result(fileName, file.absolutePath)
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
