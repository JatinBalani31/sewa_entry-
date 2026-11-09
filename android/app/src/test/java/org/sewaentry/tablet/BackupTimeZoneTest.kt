package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sewaentry.tablet.data.BackupTimeZone
import java.time.Instant
import java.time.ZoneId

class BackupTimeZoneTest {
    @Test
    fun preservesTheSourceWallClockTimeWhenImportedIntoAnotherZone() {
        val source = ZoneId.of("UTC")
        val destination = ZoneId.of("Asia/Kolkata")
        val original = Instant.parse("2026-03-10T09:15:00Z").toEpochMilli()

        val imported = BackupTimeZone.preserveLocalTime(original, source, destination)

        assertEquals(
            Instant.parse("2026-03-10T03:45:00Z").toEpochMilli(),
            imported,
        )
        assertEquals(
            Instant.ofEpochMilli(original).atZone(source).toLocalDateTime(),
            Instant.ofEpochMilli(imported).atZone(destination).toLocalDateTime(),
        )
    }

    @Test
    fun accountsForDaylightSavingOffsetsAtTheRecordedDate() {
        val source = ZoneId.of("America/New_York")
        val destination = ZoneId.of("Europe/London")
        val original = Instant.parse("2026-07-10T12:00:00Z").toEpochMilli()

        val imported = BackupTimeZone.preserveLocalTime(original, source, destination)

        assertEquals(
            Instant.ofEpochMilli(original).atZone(source).toLocalDateTime(),
            Instant.ofEpochMilli(imported).atZone(destination).toLocalDateTime(),
        )
    }

    @Test
    fun keepsTheExactInstantWhenTheTimezoneIsUnchangedDuringAnOverlap() {
        val zone = ZoneId.of("America/New_York")
        val original = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()

        assertEquals(original, BackupTimeZone.preserveLocalTime(original, zone, zone))
    }
}
