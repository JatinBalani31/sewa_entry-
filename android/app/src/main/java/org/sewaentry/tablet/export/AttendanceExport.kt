package org.sewaentry.tablet.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.Quarter
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
 * do", so the workbook leads with the summary that was asked for - one
 * department, or one sewadar - and only then shows the raw register behind it.
 */
object AttendanceExport {

    data class Result(val fileName: String, val location: String)

    fun build(
        quarter: Quarter,
        departmentName: String,
        /** Set when the report is about one person rather than the whole department. */
        sewadarName: String?,
        periodLabel: String,
        totals: List<SewadarTotals>,
        monthlyHours: Map<Pair<Long, String>, Double>,
        sewaDates: List<String>,
        rows: List<ExportRow>,
        from: LocalDate,
        to: LocalDate,
        dayFilter: DayFilter,
    ): List<XlsxWriter.Sheet> {
        val sheets = mutableListOf<XlsxWriter.Sheet>()

        // --- 0. The summary --------------------------------------------------
        // One report, one subject: either a department's sewadars or a single
        // sewadar's days. Whichever it is leads the workbook, because it is the
        // thing somebody actually asked for.
        sheets += if (sewadarName != null) {
            sewadarSheet(sewadarName, departmentName, periodLabel, rows, totals)
        } else {
            departmentSheet(departmentName, periodLabel, quarter, totals, monthlyHours)
        }

        // --- 1. The register itself -----------------------------------------
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
                    XlsxWriter.Cell.Text(SewaDays.formatTime(row.arrivalAt) + if (row.arrivalEdited) "*" else ""),
                    XlsxWriter.Cell.Text(if (row.departureAt != null) SewaDays.formatTime(row.departureAt) + if (row.departureEdited) "*" else "" else "--"),
                    if (minutes != null) XlsxWriter.Cell.Number(round2(minutes / 60.0)) else XlsxWriter.Cell.Text("--"),
                    XlsxWriter.Cell.Text(if (edits.isEmpty()) "" else edits.joinToString(", ")),
                )
            },
        )

        // --- 2. What this report is -----------------------------------------
        sheets += XlsxWriter.Sheet(
            name = "Report Info",
            headers = listOf("Field", "Value"),
            columnWidths = listOf(24, 44),
            rows = listOfNotNull(
                row("Generated", LocalDateTime.now().toString()),
                row("Department", departmentName),
                sewadarName?.let { row("Sewadar", it) },
                row("Period", periodLabel),
                row("Period start", SewaDays.format(from)),
                row("Period end", SewaDays.format(to)),
                row("Sewa days included", dayFilter.label),
                row("Sewa days covered", sewaDates.size.toString()),
                row("Sewadars in report", totals.size.toString()),
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
     * One department over the chosen period: a line per sewadar, the quarter's
     * months as columns, and one total underneath. The same shape as the
     * summary that used to be prepared by hand, narrowed to the single
     * department being asked about.
     */
    private fun departmentSheet(
        departmentName: String,
        periodLabel: String,
        quarter: Quarter,
        totals: List<SewadarTotals>,
        monthlyHours: Map<Pair<Long, String>, Double>,
    ): XlsxWriter.Sheet {
        val monthKeys = quarter.monthKeys
        val body = mutableListOf<List<XlsxWriter.Cell>>()

        totals.sortedBy { it.name }.forEach { person ->
            val cells = mutableListOf<XlsxWriter.Cell>(
                XlsxWriter.Cell.Text(person.name),
                XlsxWriter.Cell.Number(person.daysAttended.toDouble()),
                XlsxWriter.Cell.Number(person.daysComplete.toDouble()),
            )
            monthKeys.forEach { key ->
                cells += XlsxWriter.Cell.Number(round2(monthlyHours[person.sewadarId to key] ?: 0.0))
            }
            cells += XlsxWriter.Cell.Number(round2(person.hours))
            cells += XlsxWriter.Cell.Text(person.lastAttended ?: "--")
            body += cells
        }

        // The department's own total - the number the report exists to state.
        val totalCells = mutableListOf<XlsxWriter.Cell>(
            XlsxWriter.Cell.Text("$departmentName total"),
            XlsxWriter.Cell.Number(totals.sumOf { it.daysAttended }.toDouble()),
            XlsxWriter.Cell.Number(totals.sumOf { it.daysComplete }.toDouble()),
        )
        monthKeys.forEach { key ->
            totalCells += XlsxWriter.Cell.Number(
                round2(totals.sumOf { monthlyHours[it.sewadarId to key] ?: 0.0 }),
            )
        }
        totalCells += XlsxWriter.Cell.Number(round2(totals.sumOf { it.hours }))
        totalCells += XlsxWriter.Cell.Text("")
        body += totalCells

        return XlsxWriter.Sheet(
            name = sheetName(departmentName),
            headers = listOf("Sewadar ($periodLabel)", "Days attended", "Days complete") +
                quarter.monthLabels + listOf("Total hours", "Last sewa"),
            columnWidths = listOf(28, 14, 15) + monthKeys.map { 12 } + listOf(12, 13),
            rows = body,
        )
    }

    /**
     * One sewadar over the chosen period, day by day. This is the answer to
     * "how many hours has this person done", with the days that produced the
     * number listed under it rather than summarised away.
     */
    private fun sewadarSheet(
        sewadarName: String,
        departmentName: String,
        periodLabel: String,
        rows: List<ExportRow>,
        totals: List<SewadarTotals>,
    ): XlsxWriter.Sheet {
        val body = mutableListOf<List<XlsxWriter.Cell>>()

        rows.sortedBy { it.sewaDate }.forEach { row ->
            val minutes = SewaDays.minutesBetween(row.arrivalAt, row.departureAt)
            body += listOf(
                XlsxWriter.Cell.Text(row.sewaDate),
                XlsxWriter.Cell.Text(dayName(row.sewaDate)),
                XlsxWriter.Cell.Text(SewaDays.formatTime(row.arrivalAt)),
                XlsxWriter.Cell.Text(SewaDays.formatTime(row.departureAt)),
                if (minutes != null) XlsxWriter.Cell.Number(round2(minutes / 60.0))
                else XlsxWriter.Cell.Text("no sign-out"),
            )
        }

        val total = totals.firstOrNull()
        val days = total?.daysAttended ?: 0
        body += listOf(
            XlsxWriter.Cell.Text("Total"),
            XlsxWriter.Cell.Text("$days ${if (days == 1) "day" else "days"}"),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Text(""),
            XlsxWriter.Cell.Number(round2(total?.hours ?: 0.0)),
        )

        return XlsxWriter.Sheet(
            name = sheetName(sewadarName),
            headers = listOf(
                "$sewadarName - $departmentName ($periodLabel)",
                "Day", "Arrival", "Departure", "Hours",
            ),
            columnWidths = listOf(30, 11, 10, 11, 10),
            rows = body,
        )
    }

    /** Excel refuses some characters in a tab name, and caps it at 31. */
    private fun sheetName(subject: String): String =
        subject.replace(Regex("[\\\\/*?\\[\\]:]"), " ").trim().take(28).ifEmpty { "Summary" }


    /**
     * The quarterly summary, laid out the way the hand-prepared one is:
     * department by department, a line per sewadar, a subtotal under each
     * department, and a grand total at the bottom. The three months of the
     * quarter sit as columns so the shape of someone's attendance is visible
     * without opening another sheet.
     */

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
