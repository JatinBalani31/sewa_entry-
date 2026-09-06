package org.sewaentry.tablet.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarStats
import java.io.File
import java.time.LocalDate

/**
 * Builds the attendance workbook and saves it somewhere the tablet's own Files
 * app can reach, so an admin can mail it on without a cable.
 */
object AttendanceExport {

    data class Result(val fileName: String, val location: String)

    fun build(
        stats: List<SewadarStats>,
        rows: List<ExportRow>,
        from: LocalDate,
        to: LocalDate,
    ): List<XlsxWriter.Sheet> {
        val summary = XlsxWriter.Sheet(
            name = "Summary",
            headers = listOf(
                "Sewadar", "Department", "Days attended", "Days complete",
                "Total hours", "Average hours", "Last attended",
            ),
            columnWidths = listOf(26, 20, 14, 14, 12, 14, 14),
            rows = stats.map { stat ->
                val hours = stat.totalMinutes / 60.0
                listOf(
                    XlsxWriter.Cell.Text(stat.name),
                    XlsxWriter.Cell.Text(stat.departmentName ?: "--"),
                    XlsxWriter.Cell.Number(stat.daysAttended.toDouble()),
                    XlsxWriter.Cell.Number(stat.daysComplete.toDouble()),
                    XlsxWriter.Cell.Number(round2(hours)),
                    XlsxWriter.Cell.Number(
                        if (stat.daysComplete > 0) round2(hours / stat.daysComplete) else 0.0,
                    ),
                    XlsxWriter.Cell.Text(stat.lastAttendedDate ?: "--"),
                )
            },
        )

        val register = XlsxWriter.Sheet(
            name = "Register",
            headers = listOf(
                "Date", "Day", "Department", "Sewadar",
                "Arrival", "Departure", "Hours", "Edited",
            ),
            columnWidths = listOf(13, 11, 20, 26, 10, 11, 10, 10),
            rows = rows.map { row ->
                val minutes = SewaDays.minutesBetween(row.arrivalAt, row.departureAt)
                val edits = buildList {
                    if (row.arrivalEdited) add("arrival")
                    if (row.departureEdited) add("departure")
                }
                listOf(
                    XlsxWriter.Cell.Text(row.sewaDate),
                    XlsxWriter.Cell.Text(SewaDays.parse(row.sewaDate).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }),
                    XlsxWriter.Cell.Text(row.departmentName ?: "--"),
                    XlsxWriter.Cell.Text(row.name),
                    XlsxWriter.Cell.Text(SewaDays.formatTime(row.arrivalAt)),
                    XlsxWriter.Cell.Text(SewaDays.formatTime(row.departureAt)),
                    if (minutes != null) XlsxWriter.Cell.Number(round2(minutes / 60.0)) else XlsxWriter.Cell.Text("--"),
                    XlsxWriter.Cell.Text(if (edits.isEmpty()) "" else edits.joinToString(", ")),
                )
            },
        )

        val info = XlsxWriter.Sheet(
            name = "Report Info",
            headers = listOf("Field", "Value"),
            columnWidths = listOf(24, 40),
            rows = listOf(
                listOf(XlsxWriter.Cell.Text("Generated"), XlsxWriter.Cell.Text(java.time.LocalDateTime.now().toString())),
                listOf(XlsxWriter.Cell.Text("Period start"), XlsxWriter.Cell.Text(SewaDays.format(from))),
                listOf(XlsxWriter.Cell.Text("Period end"), XlsxWriter.Cell.Text(SewaDays.format(to))),
                listOf(XlsxWriter.Cell.Text("Sewadars"), XlsxWriter.Cell.Number(stats.size.toDouble())),
                listOf(XlsxWriter.Cell.Text("Attendance rows"), XlsxWriter.Cell.Number(rows.size.toDouble())),
            ),
        )

        return listOf(summary, register, info)
    }

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
