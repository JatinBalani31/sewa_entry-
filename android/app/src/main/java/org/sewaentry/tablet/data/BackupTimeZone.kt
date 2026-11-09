package org.sewaentry.tablet.data

import java.time.Instant
import java.time.ZoneId

object BackupTimeZone {
    fun preserveLocalTime(
        timestamp: Long,
        sourceTimeZone: ZoneId,
        destinationTimeZone: ZoneId,
    ): Long {
        if (sourceTimeZone == destinationTimeZone) return timestamp
        val sourceLocalTime = Instant.ofEpochMilli(timestamp)
            .atZone(sourceTimeZone)
            .toLocalDateTime()
        return sourceLocalTime.atZone(destinationTimeZone).toInstant().toEpochMilli()
    }
}
