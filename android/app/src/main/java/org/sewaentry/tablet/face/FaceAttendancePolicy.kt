package org.sewaentry.tablet.face

enum class AttendanceDirection {
    IN,
    OUT,
}

object FaceAttendancePolicy {
    const val MATCH_THRESHOLD = 0.62f
    const val REQUIRED_CONSECUTIVE_HITS = 2
    const val NAME_DISPLAY_MS = 2_000L
    const val SCAN_COOLDOWN_MS = 5 * 60 * 1_000L

    fun isCooldownActive(lastRecordedAt: Long?, currentTime: Long): Boolean {
        if (lastRecordedAt == null) return false
        return (currentTime - lastRecordedAt) in 0 until SCAN_COOLDOWN_MS
    }

    fun remainingCooldownSeconds(lastRecordedAt: Long?, currentTime: Long): Long {
        if (lastRecordedAt == null) return 0L
        val diff = currentTime - lastRecordedAt
        if (diff < 0L || diff >= SCAN_COOLDOWN_MS) return 0L
        return ((SCAN_COOLDOWN_MS - diff) + 999L) / 1_000L
    }

    fun nextDirection(lastDirection: AttendanceDirection?): AttendanceDirection =
        if (lastDirection == null) AttendanceDirection.IN else AttendanceDirection.OUT

    fun isValidTransition(
        lastDirection: AttendanceDirection?,
        requestedDirection: AttendanceDirection,
    ): Boolean =
        when (requestedDirection) {
            AttendanceDirection.IN -> lastDirection != AttendanceDirection.IN
            AttendanceDirection.OUT -> lastDirection == AttendanceDirection.IN
        }

    fun resolveLastDirection(
        latestEventType: String?,
        legacyArrivalAt: Long?,
        legacyDepartureAt: Long?,
    ): AttendanceDirection? {
        if (latestEventType != null) {
            return when (latestEventType) {
                AttendanceDirection.IN.name -> AttendanceDirection.IN
                AttendanceDirection.OUT.name -> AttendanceDirection.OUT
                else -> throw IllegalStateException("Attendance history contains an invalid direction.")
            }
        }
        if (legacyArrivalAt == null) {
            return if (legacyDepartureAt == null) null else AttendanceDirection.OUT
        }
        if (legacyDepartureAt == null) return AttendanceDirection.IN
        return if (legacyDepartureAt >= legacyArrivalAt) AttendanceDirection.OUT else AttendanceDirection.IN
    }

}
