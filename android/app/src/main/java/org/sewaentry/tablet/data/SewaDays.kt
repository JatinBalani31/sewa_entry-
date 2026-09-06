package org.sewaentry.tablet.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sewa runs on Wednesday and Sunday. Everything here works on `LocalDate` in
 * the device's own timezone — a sewa date is a calendar date, not an instant,
 * so someone signing in at 9pm never lands on the wrong day.
 */
object SewaDays {

    val SEWA_DAYS: Set<DayOfWeek> = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun isSewaDay(date: LocalDate): Boolean = date.dayOfWeek in SEWA_DAYS

    fun format(date: LocalDate): String = date.format(ISO)

    fun parse(value: String): LocalDate = LocalDate.parse(value, ISO)

    fun today(): LocalDate = LocalDate.now()

    /** Today if it is a sewa day, otherwise the most recent one — what the register opens to. */
    fun currentOrPrevious(from: LocalDate = today()): LocalDate {
        var cursor = from
        repeat(14) {
            if (isSewaDay(cursor)) return cursor
            cursor = cursor.minusDays(1)
        }
        return from
    }

    /** Local-midnight-anchored formatting helpers for times stored as epoch millis. */
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatTime(epochMillis: Long?): String {
        if (epochMillis == null) return "--:--"
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(TIME)
    }

    /** Turns an "HH:mm" typed by an admin into an instant on the given sewa date. */
    fun timeOnDate(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun minutesBetween(from: Long?, to: Long?): Long? {
        if (from == null || to == null) return null
        return (to - from) / 60_000
    }

    /** "6h 45m", or "--" when a day was never closed. */
    fun formatDuration(minutes: Long?): String {
        if (minutes == null || minutes < 0) return "--"
        val hours = minutes / 60
        val mins = minutes % 60
        return if (mins == 0L) "${hours}h" else "${hours}h ${mins}m"
    }
}
