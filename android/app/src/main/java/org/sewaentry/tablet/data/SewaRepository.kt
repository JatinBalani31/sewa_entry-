package org.sewaentry.tablet.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.sewaentry.tablet.roster.ParsedDepartment
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

    suspend fun sewadarCount(): Int = db.sewadars().activeCount()

    /**
     * Applies a roster import from the sangat's contact workbook.
     *
     * The one rule that matters: **nothing is ever deleted.** People are
     * matched by name within their department, so re-importing an updated sheet
     * edits the person already on file rather than creating a second copy —
     * a duplicate would strand every attendance record they have.
     *
     * Someone who has dropped off the sheet is deactivated, never removed, and
     * only when the admin asks: they vanish from the register but their past
     * sewa still counts in every report.
     */
    suspend fun applyImport(
        departments: List<ParsedDepartment>,
        deactivateMissing: Boolean = false,
    ): ImportResult = db.withTransaction {
        var departmentsAdded = 0
        var added = 0
        var updated = 0
        var unchanged = 0
        val deactivated = mutableListOf<String>()

        for (parsed in departments) {
            val existing = db.departments().findByName(parsed.name)
            val departmentId = when {
                existing == null -> {
                    departmentsAdded++
                    val order = (db.departments().maxSortOrder() ?: 0) + 1
                    db.departments().insert(DepartmentEntity(name = parsed.name, sortOrder = order))
                }
                // A department that was archived and is back on the sheet
                // should reappear rather than stay hidden.
                !existing.isActive -> {
                    db.departments().update(existing.copy(isActive = true))
                    existing.id
                }
                else -> existing.id
            }
            if (departmentId <= 0) continue

            val seen = mutableSetOf<Long>()

            for (person in parsed.sewadars) {
                val current = db.sewadars().findInDepartment(departmentId, person.name)
                if (current == null) {
                    val id = db.sewadars().insert(
                        SewadarEntity(
                            name = person.name,
                            departmentId = departmentId,
                            phone = person.phone,
                            phone2 = person.phone2,
                            isHod = person.isHod,
                        ),
                    )
                    if (id > 0) { added++; seen += id }
                } else {
                    seen += current.id
                    val merged = current.copy(
                        // A blank cell in the sheet is not an instruction to
                        // erase a number that is already on file.
                        phone = person.phone ?: current.phone,
                        phone2 = person.phone2 ?: current.phone2,
                        isHod = person.isHod,
                        isActive = true,
                    )
                    if (merged != current) {
                        db.sewadars().update(merged)
                        updated++
                    } else {
                        unchanged++
                    }
                }
            }

            if (deactivateMissing) {
                val stale = db.sewadars().inDepartment(departmentId)
                    .filter { it.isActive && it.id !in seen }
                if (stale.isNotEmpty()) {
                    db.sewadars().deactivate(stale.map { it.id })
                    deactivated += stale.map { it.name }
                }
            }
        }

        ImportResult(
            departmentsAdded = departmentsAdded,
            departmentsSeen = departments.size,
            sewadarsAdded = added,
            sewadarsUpdated = updated,
            sewadarsUnchanged = unchanged,
            deactivated = deactivated,
        )
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

    /** Identities for the roster, so a report can include people who did no sewa. */
    suspend fun rosterRefs(departmentId: Long?): List<SewadarRef> =
        if (departmentId == null) db.sewadars().rosterRefs() else db.sewadars().rosterRefs(departmentId)

    suspend fun exportRows(from: LocalDate, to: LocalDate): List<ExportRow> =
        db.attendance().exportRows(SewaDays.format(from), SewaDays.format(to))
}

/** What an import actually changed, reported back so nothing happens invisibly. */
data class ImportResult(
    val departmentsAdded: Int,
    val departmentsSeen: Int,
    val sewadarsAdded: Int,
    val sewadarsUpdated: Int,
    val sewadarsUnchanged: Int,
    val deactivated: List<String> = emptyList(),
) {
    val summary: String
        get() = buildString {
            append("$sewadarsAdded added")
            if (sewadarsUpdated > 0) append(", $sewadarsUpdated updated")
            if (sewadarsUnchanged > 0) append(", $sewadarsUnchanged unchanged")
            if (departmentsAdded > 0) append(", $departmentsAdded new department(s)")
            if (deactivated.isNotEmpty()) append(", ${deactivated.size} archived")
            append(".")
        }
}
