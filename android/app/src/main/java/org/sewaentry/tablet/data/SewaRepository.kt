package org.sewaentry.tablet.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.sewaentry.tablet.face.AttendanceDirection
import org.sewaentry.tablet.face.FaceAttendancePolicy
import org.sewaentry.tablet.roster.ParsedDepartment
import java.time.LocalDate
import java.util.UUID

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

    suspend fun addSewadar(
        name: String,
        departmentId: Long,
        phone: String?,
        organization: String? = null,
    ) {
        db.sewadars().insert(
            SewadarEntity(
                name = name.trim(),
                departmentId = departmentId,
                phone = phone?.trim()?.ifBlank { null },
                organization = organization?.trim()?.ifBlank { null },
            ),
        )
    }

    suspend fun updateSewadarDetails(
        sewadarId: Long,
        name: String,
        phone: String?,
        organization: String?,
        departmentId: Long,
    ) {
        require(name.isNotBlank()) { "Name cannot be empty." }
        val current = db.sewadars().findById(sewadarId)
            ?: throw IllegalArgumentException("This sewadar could not be found.")
        val department = db.departments().findById(departmentId)
            ?: throw IllegalArgumentException("The selected department could not be found.")
        require(department.isActive) { "The selected department is no longer active." }
        db.sewadars().update(
            current.copy(
                name = name.trim(),
                phone = phone?.trim()?.ifBlank { null },
                organization = organization?.trim()?.ifBlank { null },
                departmentId = departmentId,
            ),
        )
    }

    suspend fun addDepartment(name: String) {
        db.departments().insert(DepartmentEntity(name = name.trim(), sortOrder = 100))
    }

    suspend fun renameDepartment(departmentId: Long, name: String) {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "Department name cannot be empty." }
        val department = db.departments().findById(departmentId)
            ?: throw IllegalArgumentException("This department could not be found.")
        val duplicate = db.departments().findByName(normalizedName)
        require(duplicate == null || duplicate.id == departmentId) {
            "A department with this name already exists."
        }
        db.departments().update(department.copy(name = normalizedName))
    }

    suspend fun activeDepartments(): List<DepartmentEntity> = db.departments().all()
        .filter { it.isActive }
        .sortedBy { it.name.lowercase() }

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
                            isHod = false,
                            organization = organizationWithHod(person.organization, person.isHod),
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
                        organization = organizationWithHod(person.organization, person.isHod) ?: current.organization,
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

    private fun organizationWithHod(organization: String?, isHod: Boolean): String? {
        if (!isHod) return organization?.trim()?.ifBlank { null }
        val value = organization?.trim()?.ifBlank { null }
        return when {
            value == null -> "HOD"
            Regex("""(?i)(^|\W)HOD($|\W)""").containsMatchIn(value) -> value
            else -> "$value - HOD"
        }
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
        method: String? = null,
        adminId: String? = null,
        note: String? = null,
    ): Result<Unit> {
        val sewaDate = SewaDays.format(date)
        return try {
            db.withTransaction {
                val existing = db.attendance().find(sewadarId, sewaDate)
                val latestLog = db.attendanceLogs().latestForDay(sewadarId, sewaDate)
                val lastDirection = FaceAttendancePolicy.resolveLastDirection(
                    latestEventType = latestLog?.eventType,
                    legacyArrivalAt = existing?.arrivalAt,
                    legacyDepartureAt = existing?.departureAt,
                )
                if (!edited && !FaceAttendancePolicy.isValidTransition(lastDirection, AttendanceDirection.IN)) {
                    throw IllegalStateException("Record the departure before recording another arrival.")
                }
                if (edited && existing?.departureAt != null && at > existing.departureAt) {
                    throw IllegalStateException("Arrival cannot be after the recorded departure.")
                }
                val lastRecordedAt = latestLog?.deviceTime
                    ?: listOfNotNull(existing?.arrivalAt, existing?.departureAt).maxOrNull()
                if (!edited && lastRecordedAt != null && at < lastRecordedAt) {
                    throw IllegalStateException("Device time moved backwards; attendance was not recorded.")
                }
                val logId = writeManualLog(
                    existingLogId = existing?.arrivalLogId,
                    sewadarId = sewadarId,
                    sewaDate = sewaDate,
                    departmentId = departmentId,
                    eventType = AttendanceDirection.IN,
                    at = at,
                    signature = signature,
                    edited = edited,
                    method = method,
                    adminId = adminId,
                    note = note,
                )
                val row = (existing ?: AttendanceEntity(
                    sewadarId = sewadarId,
                    sewaDate = sewaDate,
                    departmentId = departmentId,
                )).copy(
                    departmentId = departmentId,
                    arrivalAt = at,
                    arrivalSignature = signature ?: existing?.arrivalSignature,
                    arrivalEdited = edited,
                    arrivalMethod = method ?: existing?.arrivalMethod,
                    arrivalAdminId = adminId?.trim()?.ifBlank { null } ?: existing?.arrivalAdminId,
                    arrivalNote = note?.trim()?.ifBlank { null } ?: existing?.arrivalNote,
                    arrivalLogId = if (edited) existing?.arrivalLogId ?: logId else logId,
                    departureAt = if (edited) existing?.departureAt else null,
                    departureSignature = if (edited) existing?.departureSignature else null,
                    departureEdited = if (edited) existing?.departureEdited ?: false else false,
                    departureMethod = if (edited) existing?.departureMethod else null,
                    departureAdminId = if (edited) existing?.departureAdminId else null,
                    departureNote = if (edited) existing?.departureNote else null,
                    departureLogId = if (edited) existing?.departureLogId else null,
                    updatedAt = System.currentTimeMillis(),
                )
                db.attendance().upsert(row)
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: IllegalStateException) {
            Result.failure(error)
        } catch (error: SQLiteException) {
            Result.failure(error)
        }
    }

    suspend fun recordDeparture(
        sewadarId: Long,
        date: LocalDate,
        at: Long,
        signature: String?,
        edited: Boolean = false,
        method: String? = null,
        adminId: String? = null,
        note: String? = null,
    ): Result<Unit> {
        val sewaDate = SewaDays.format(date)
        return try {
            db.withTransaction {
                val existing = db.attendance().find(sewadarId, sewaDate)
                    ?: throw IllegalStateException("Record the arrival first.")
                val arrival = existing.arrivalAt
                    ?: throw IllegalStateException("Record the arrival first.")
                val latestLog = db.attendanceLogs().latestForDay(sewadarId, sewaDate)
                val lastDirection = FaceAttendancePolicy.resolveLastDirection(
                    latestEventType = latestLog?.eventType,
                    legacyArrivalAt = existing.arrivalAt,
                    legacyDepartureAt = existing.departureAt,
                )
                if (!edited && !FaceAttendancePolicy.isValidTransition(lastDirection, AttendanceDirection.OUT)) {
                    throw IllegalStateException("Record a new arrival before recording departure.")
                }
                if (at < arrival) {
                    throw IllegalStateException("Departure cannot be before the arrival.")
                }
                val lastRecordedAt = latestLog?.deviceTime
                    ?: listOfNotNull(existing.arrivalAt, existing.departureAt).maxOrNull()
                if (!edited && lastRecordedAt != null && at < lastRecordedAt) {
                    throw IllegalStateException("Device time moved backwards; attendance was not recorded.")
                }
                val logId = writeManualLog(
                    existingLogId = existing.departureLogId,
                    sewadarId = sewadarId,
                    sewaDate = sewaDate,
                    departmentId = existing.departmentId,
                    eventType = AttendanceDirection.OUT,
                    at = at,
                    signature = signature,
                    edited = edited,
                    method = method,
                    adminId = adminId,
                    note = note,
                )
                db.attendance().upsert(
                    existing.copy(
                        departureAt = at,
                        departureSignature = signature ?: existing.departureSignature,
                        departureEdited = edited,
                        departureMethod = method ?: existing.departureMethod,
                        departureAdminId = adminId?.trim()?.ifBlank { null } ?: existing.departureAdminId,
                        departureNote = note?.trim()?.ifBlank { null } ?: existing.departureNote,
                        departureLogId = logId,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: IllegalStateException) {
            Result.failure(error)
        } catch (error: SQLiteException) {
            Result.failure(error)
        }
    }

    suspend fun recordFaceAttendance(
        sewadarId: Long,
        departmentId: Long?,
        date: LocalDate,
        at: Long,
        matchScore: Float,
        livenessPassed: Boolean?,
        forceDirection: AttendanceDirection? = null,
        adminId: String? = null,
        note: String? = null,
    ): Result<FaceAttendanceRecorded> {
        if (livenessPassed == false) {
            return Result.failure(IllegalArgumentException("Liveness must pass before attendance is recorded."))
        }
        if (!matchScore.isFinite() || matchScore !in FaceAttendancePolicy.MATCH_THRESHOLD..1f) {
            return Result.failure(IllegalArgumentException("Face match did not meet the required threshold."))
        }
        if (forceDirection != null && (adminId.isNullOrBlank() || note.isNullOrBlank())) {
            return Result.failure(IllegalArgumentException("Admin ID and a note are required for a direction override."))
        }

        val sewaDate = SewaDays.format(date)
        return try {
            val recorded = db.withTransaction {
                val latestLog = db.attendanceLogs().latestForDay(sewadarId, sewaDate)
                val existing = db.attendance().find(sewadarId, sewaDate)
                val lastDirection = FaceAttendancePolicy.resolveLastDirection(
                    latestEventType = latestLog?.eventType,
                    legacyArrivalAt = existing?.arrivalAt,
                    legacyDepartureAt = existing?.departureAt,
                )
                val direction = forceDirection ?: FaceAttendancePolicy.nextDirection(lastDirection)
                val lastRecordedAt = latestLog?.deviceTime
                    ?: listOfNotNull(existing?.arrivalAt, existing?.departureAt).maxOrNull()
                if (lastRecordedAt != null && at < lastRecordedAt) {
                    throw IllegalStateException("Device time moved backwards; face attendance was not recorded.")
                }
                if (forceDirection == null && lastRecordedAt != null && FaceAttendancePolicy.isCooldownActive(lastRecordedAt, at)) {
                    val remainingSec = FaceAttendancePolicy.remainingCooldownSeconds(lastRecordedAt, at)
                    val remainingMin = (remainingSec + 59) / 60
                    throw IllegalStateException("Please wait at least 5 minutes before scanning again ($remainingMin min remaining).")
                }

                val trimmedAdminId = adminId?.trim()?.ifBlank { null }
                val trimmedNote = note?.trim()?.ifBlank { null }
                val eventId = db.attendanceLogs().insert(
                    AttendanceLogEntity(
                        clientLogId = UUID.randomUUID().toString(),
                        sewadarId = sewadarId,
                        sewaDate = sewaDate,
                        departmentId = departmentId,
                        eventType = direction.name,
                        verificationMethod = FACE_METHOD,
                        deviceTime = at,
                        matchScore = matchScore,
                        livenessPassed = livenessPassed,
                        adminOverride = forceDirection != null,
                        adminId = trimmedAdminId,
                        note = trimmedNote,
                    ),
                )
                val current = existing ?: AttendanceEntity(
                    sewadarId = sewadarId,
                    sewaDate = sewaDate,
                    departmentId = departmentId,
                )
                val updated = when (direction) {
                    AttendanceDirection.IN -> current.copy(
                        departmentId = departmentId,
                        arrivalAt = at,
                        arrivalMethod = FACE_METHOD,
                        arrivalAdminId = trimmedAdminId,
                        arrivalNote = trimmedNote,
                        arrivalEdited = false,
                        arrivalLogId = eventId,
                        departureAt = null,
                        departureMethod = null,
                        departureAdminId = null,
                        departureNote = null,
                        departureSignature = null,
                        departureEdited = false,
                        departureLogId = null,
                        updatedAt = at,
                    )
                    AttendanceDirection.OUT -> current.copy(
                        departmentId = departmentId,
                        departureAt = at,
                        departureMethod = FACE_METHOD,
                        departureAdminId = trimmedAdminId,
                        departureNote = trimmedNote,
                        departureEdited = false,
                        departureLogId = eventId,
                        updatedAt = at,
                    )
                }
                db.attendance().upsert(updated)
                FaceAttendanceRecorded(direction = direction, deviceTime = at)
            }
            Result.success(recorded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: IllegalStateException) {
            Result.failure(error)
        } catch (error: SQLiteException) {
            Result.failure(error)
        }
    }

    /** Clears a day's record entirely — an admin fixing a wrong person tapped. */
    suspend fun clear(
        sewadarId: Long,
        date: LocalDate,
        arrival: Boolean? = null,
        adminId: String? = null,
        note: String? = null,
    ): Result<Unit> {
        val sewaDate = SewaDays.format(date)
        return runCatching {
            db.withTransaction {
                db.attendanceOverrideAudit().insert(
                    AttendanceOverrideAuditEntity(
                        sewadarId = sewadarId,
                        sewaDate = sewaDate,
                        action = when (arrival) {
                            true -> "CLEAR_ARRIVAL"
                            false -> "CLEAR_DEPARTURE"
                            null -> "CLEAR"
                        },
                        adminId = adminId?.trim()?.ifBlank { null } ?: "ADMIN",
                        note = note?.trim()?.ifBlank { null } ?: "Manual clear",
                    ),
                )
                val attendance = db.attendance().find(sewadarId, sewaDate)
                if (arrival == null) {
                    db.attendanceLogs().deleteForDay(sewadarId, sewaDate)
                    attendance?.let { db.attendance().delete(it.id) }
                } else if (attendance != null) {
                    val cleared = if (arrival) {
                        attendance.arrivalLogId?.let { db.attendanceLogs().deleteById(it) }
                        attendance.copy(
                            arrivalAt = null,
                            arrivalMethod = null,
                            arrivalAdminId = null,
                            arrivalNote = null,
                            arrivalSignature = null,
                            arrivalEdited = false,
                            arrivalLogId = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else {
                        attendance.departureLogId?.let { db.attendanceLogs().deleteById(it) }
                        attendance.copy(
                            departureAt = null,
                            departureMethod = null,
                            departureAdminId = null,
                            departureNote = null,
                            departureSignature = null,
                            departureEdited = false,
                            departureLogId = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    if (cleared.arrivalAt == null && cleared.departureAt == null) {
                        db.attendance().delete(cleared.id)
                    } else {
                        db.attendance().upsert(cleared)
                    }
                }
                Unit
            }
        }
    }

    suspend fun removeSewadar(sewadarId: Long) {
        db.sewadars().deactivate(listOf(sewadarId))
    }

    /** Identities for the roster, so a report can include people who did no sewa. */
    suspend fun rosterRefs(departmentId: Long?): List<SewadarRef> =
        if (departmentId == null) db.sewadars().rosterRefs() else db.sewadars().rosterRefs(departmentId)

    suspend fun exportRows(from: LocalDate, to: LocalDate): List<ExportRow> =
        db.attendance().exportRows(SewaDays.format(from), SewaDays.format(to))

    private suspend fun writeManualLog(
        existingLogId: Long?,
        sewadarId: Long,
        sewaDate: String,
        departmentId: Long?,
        eventType: AttendanceDirection,
        at: Long,
        signature: String?,
        edited: Boolean,
        method: String?,
        adminId: String?,
        note: String?,
    ): Long {
        val existingLog = if (edited && existingLogId != null) {
            db.attendanceLogs().find(existingLogId)
        } else {
            null
        }
        if (existingLog != null) {
            db.attendanceLogs().update(
                existingLog.copy(
                    departmentId = departmentId,
                    eventType = eventType.name,
                    verificationMethod = method ?: existingLog.verificationMethod,
                    deviceTime = at,
                    signature = signature ?: existingLog.signature,
                    adminOverride = existingLog.adminOverride || method == ADMIN_OVERRIDE,
                    adminId = adminId?.trim()?.ifBlank { null } ?: existingLog.adminId,
                    note = note?.trim()?.ifBlank { null } ?: existingLog.note,
                ),
            )
            return existingLog.id
        }
        return db.attendanceLogs().insert(
            AttendanceLogEntity(
                clientLogId = UUID.randomUUID().toString(),
                sewadarId = sewadarId,
                sewaDate = sewaDate,
                departmentId = departmentId,
                eventType = eventType.name,
                verificationMethod = method ?: MANUAL_METHOD,
                deviceTime = at,
                signature = signature,
                adminOverride = method == ADMIN_OVERRIDE,
                adminId = adminId?.trim()?.ifBlank { null },
                note = note?.trim()?.ifBlank { null },
            ),
        )
    }

    private companion object {
        const val ADMIN_OVERRIDE = "ADMIN_OVERRIDE"
        const val FACE_METHOD = "FACE"
        const val MANUAL_METHOD = "MANUAL"
    }
}

data class FaceAttendanceRecorded(
    val direction: AttendanceDirection,
    val deviceTime: Long,
)

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
