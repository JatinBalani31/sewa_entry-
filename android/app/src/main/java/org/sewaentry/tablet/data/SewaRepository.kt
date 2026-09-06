package org.sewaentry.tablet.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The one place attendance is written.
 *
 * Rules enforced here rather than in the UI, so they hold no matter which
 * screen calls in:
 *  - arrival is always recorded before departure
 *  - departure can never land before arrival
 *  - a live tap takes the device clock; only an admin edit sets a time by hand,
 *    and that is recorded as edited
 */
class SewaRepository(private val db: SewaDatabase) {

    fun departments(): Flow<List<DepartmentEntity>> = db.departments().observeActive()

    fun register(departmentId: Long, date: LocalDate): Flow<List<SewadarWithStatus>> =
        db.sewadars().observeRegister(departmentId, SewaDays.format(date))

    fun stats(from: LocalDate, to: LocalDate): Flow<List<SewadarStats>> =
        db.attendance().observeStats(SewaDays.format(from), SewaDays.format(to))

    fun recordedDates(): Flow<List<String>> = db.attendance().observeRecordedDates()

    suspend fun addSewadar(name: String, departmentId: Long, phone: String?) {
        db.sewadars().insert(
            SewadarEntity(name = name.trim(), departmentId = departmentId, phone = phone?.trim()?.ifBlank { null }),
        )
    }

    suspend fun addDepartment(name: String) {
        db.departments().insert(DepartmentEntity(name = name.trim(), sortOrder = 100))
    }

    suspend fun signatureFor(attendanceId: Long, arrival: Boolean): String? =
        if (arrival) db.attendance().arrivalSignature(attendanceId)
        else db.attendance().departureSignature(attendanceId)

    /**
     * Record an arrival. `at` is the device clock for a live tap; an admin
     * correction passes its own instant and is flagged as edited.
     */
    suspend fun recordArrival(
        sewadarId: Long,
        departmentId: Long?,
        date: LocalDate,
        at: Long,
        signature: String?,
        edited: Boolean = false,
    ): Result<Unit> {
        val sewaDate = SewaDays.format(date)
        val existing = db.attendance().find(sewadarId, sewaDate)

        if (existing?.departureAt != null && at > existing.departureAt) {
            return Result.failure(IllegalStateException("Arrival cannot be after the recorded departure."))
        }

        val row = (existing ?: AttendanceEntity(sewadarId = sewadarId, sewaDate = sewaDate, departmentId = departmentId))
            .copy(
                departmentId = departmentId,
                arrivalAt = at,
                arrivalSignature = signature ?: existing?.arrivalSignature,
                arrivalEdited = edited,
                updatedAt = System.currentTimeMillis(),
            )
        db.attendance().upsert(row)
        return Result.success(Unit)
    }

    suspend fun recordDeparture(
        sewadarId: Long,
        departmentId: Long?,
        date: LocalDate,
        at: Long,
        signature: String?,
        edited: Boolean = false,
    ): Result<Unit> {
        val sewaDate = SewaDays.format(date)
        val existing = db.attendance().find(sewadarId, sewaDate)
            ?: return Result.failure(IllegalStateException("Record the arrival first."))

        val arrival = existing.arrivalAt
            ?: return Result.failure(IllegalStateException("Record the arrival first."))

        if (at < arrival) {
            return Result.failure(IllegalStateException("Departure cannot be before the arrival."))
        }

        db.attendance().upsert(
            existing.copy(
                departureAt = at,
                departureSignature = signature ?: existing.departureSignature,
                departureEdited = edited,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return Result.success(Unit)
    }

    /** Clears a day's record entirely — an admin fixing a wrong person tapped. */
    suspend fun clear(sewadarId: Long, date: LocalDate) {
        db.attendance().find(sewadarId, SewaDays.format(date))?.let { db.attendance().delete(it.id) }
    }

    suspend fun exportRows(from: LocalDate, to: LocalDate): List<ExportRow> =
        db.attendance().exportRows(SewaDays.format(from), SewaDays.format(to))
}
