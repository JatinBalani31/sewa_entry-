package org.sewaentry.tablet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DepartmentDao {
    @Query("SELECT * FROM departments WHERE isActive = 1 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<DepartmentEntity>>

    @Query("SELECT * FROM departments ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<DepartmentEntity>>

    @Query("SELECT * FROM departments ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<DepartmentEntity>

    @Query("SELECT COUNT(*) FROM departments")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(department: DepartmentEntity): Long

    @Update
    suspend fun update(department: DepartmentEntity)

    /** Case-insensitive, so "Account" from the sheet finds an existing "account". */
    @Query("SELECT * FROM departments WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): DepartmentEntity?

    @Query("SELECT * FROM departments WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DepartmentEntity?

    @Query("SELECT MAX(sortOrder) FROM departments")
    suspend fun maxSortOrder(): Int?

}

@Dao
interface SewadarDao {
    @Query("SELECT COUNT(*) FROM sewadars")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sewadar: SewadarEntity): Long

    @Update
    suspend fun update(sewadar: SewadarEntity)

    @Query("SELECT * FROM sewadars WHERE isActive = 1 ORDER BY name")
    fun observeActive(): Flow<List<SewadarEntity>>

    @Query("SELECT * FROM sewadars ORDER BY isActive DESC, name")
    fun observeAll(): Flow<List<SewadarEntity>>

    @Query("SELECT * FROM sewadars ORDER BY isActive DESC, name")
    suspend fun all(): List<SewadarEntity>

    @Query("SELECT * FROM sewadars WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SewadarEntity?

    /**
     * Matched on name within a department, so re-importing an updated contact
     * sheet edits the person who is already there instead of creating a second
     * copy — which would strand every attendance record they have.
     */
    @Query("SELECT * FROM sewadars WHERE departmentId IS :departmentId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findInDepartment(departmentId: Long?, name: String): SewadarEntity?

    @Query("SELECT * FROM sewadars WHERE departmentId IS :departmentId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findForFaceTransfer(departmentId: Long?, name: String): SewadarEntity?

    @Query("SELECT * FROM sewadars WHERE departmentId = :departmentId")
    suspend fun inDepartment(departmentId: Long): List<SewadarEntity>

    @Query("UPDATE sewadars SET isActive = 0 WHERE id IN (:ids)")
    suspend fun deactivate(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM sewadars WHERE isActive = 1")
    suspend fun activeCount(): Int

    /**
     * The register grid: every active sewadar in a department, each carrying
     * whatever has been recorded for them on this date. A LEFT JOIN so someone
     * with nothing recorded yet still appears — that is the normal case at the
     * start of a sewa day.
     */
    @Query(
        """
        SELECT s.id            AS sewadarId,
               s.name          AS name,
               s.departmentId  AS departmentId,
               d.name          AS departmentName,
               s.phone         AS phone,
               s.organization  AS organization,
               a.id            AS attendanceId,
               a.arrivalAt     AS arrivalAt,
               a.departureAt   AS departureAt,
               COALESCE(a.arrivalEdited, 0)   AS arrivalEdited,
               COALESCE(a.departureEdited, 0) AS departureEdited
        FROM sewadars s
        LEFT JOIN departments d ON d.id = s.departmentId
        LEFT JOIN attendance a ON a.sewadarId = s.id AND a.sewaDate = :sewaDate
        WHERE s.isActive = 1 AND s.departmentId = :departmentId
        ORDER BY s.name
        """
    )
    fun observeRegister(departmentId: Long, sewaDate: String): Flow<List<SewadarWithStatus>>

    @Query(
        """
        SELECT s.id AS id, s.name AS name, d.name AS departmentName
        FROM sewadars s
        LEFT JOIN departments d ON d.id = s.departmentId
        WHERE s.isActive = 1
        ORDER BY s.name
        """
    )
    suspend fun rosterRefs(): List<SewadarRef>

    @Query(
        """
        SELECT s.id AS id, s.name AS name, d.name AS departmentName
        FROM sewadars s
        LEFT JOIN departments d ON d.id = s.departmentId
        WHERE s.isActive = 1 AND s.departmentId = :departmentId
        ORDER BY s.name
        """
    )
    suspend fun rosterRefs(departmentId: Long): List<SewadarRef>
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance")
    suspend fun all(): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE sewadarId = :sewadarId AND sewaDate = :sewaDate LIMIT 1")
    suspend fun find(sewadarId: Long, sewaDate: String): AttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attendance: AttendanceEntity): Long

    @Query("SELECT arrivalSignature FROM attendance WHERE id = :id")
    suspend fun arrivalSignature(id: Long): String?

    @Query("SELECT departureSignature FROM attendance WHERE id = :id")
    suspend fun departureSignature(id: Long): String?

    @Query("DELETE FROM attendance WHERE id = :id")
    suspend fun delete(id: Long)

    /** Distinct dates that have any attendance — drives the calendar's dots. */
    @Query("SELECT DISTINCT sewaDate FROM attendance ORDER BY sewaDate DESC")
    fun observeRecordedDates(): Flow<List<String>>

    /**
     * Per-sewadar analytics over a date range.
     *
     * `totalMinutes` only counts days where both ends were recorded — a day
     * someone forgot to sign out contributes to `daysAttended` but not to the
     * hours, so an unclosed day can never silently inflate the totals.
     */
    @Query(
        """
        SELECT s.id           AS sewadarId,
               s.name         AS name,
               COALESCE(attendanceDepartment.name, currentDepartment.name) AS departmentName,
               COUNT(a.id)    AS daysAttended,
               SUM(CASE WHEN a.departureAt IS NOT NULL THEN 1 ELSE 0 END) AS daysComplete,
               COALESCE(SUM(
                   CASE WHEN a.departureAt IS NOT NULL AND a.arrivalAt IS NOT NULL
                        THEN (a.departureAt - a.arrivalAt) / 60000
                        ELSE 0 END
               ), 0)          AS totalMinutes,
               MAX(a.sewaDate) AS lastAttendedDate
        FROM sewadars s
        LEFT JOIN attendance a
               ON a.sewadarId = s.id
              AND a.arrivalAt IS NOT NULL
              AND a.sewaDate BETWEEN :from AND :to
        LEFT JOIN departments attendanceDepartment ON attendanceDepartment.id = a.departmentId
        LEFT JOIN departments currentDepartment ON currentDepartment.id = s.departmentId
        WHERE s.isActive = 1
        GROUP BY s.id, s.name, COALESCE(attendanceDepartment.name, currentDepartment.name)
        ORDER BY s.name
        """
    )
    fun observeStats(from: String, to: String): Flow<List<SewadarStats>>

    /** Everything in a range, for the Excel export. */
    @Query(
        """
        SELECT s.id            AS sewadarId,
               s.name          AS name,
               s.departmentId  AS departmentId,
               d.name          AS departmentName,
               a.id            AS attendanceId,
               a.arrivalAt     AS arrivalAt,
               a.departureAt   AS departureAt,
               a.arrivalEdited AS arrivalEdited,
               a.departureEdited AS departureEdited,
               a.sewaDate      AS sewaDate
        FROM attendance a
        JOIN sewadars s ON s.id = a.sewadarId
        LEFT JOIN departments d ON d.id = a.departmentId
        WHERE a.sewaDate BETWEEN :from AND :to
        ORDER BY a.sewaDate, d.name, s.name
        """
    )
    suspend fun exportRows(from: String, to: String): List<ExportRow>
}

@Dao
interface AttendanceLogDao {
    @Query("SELECT * FROM attendance_logs")
    suspend fun all(): List<AttendanceLogEntity>

    @Query(
        "SELECT * FROM attendance_logs WHERE sewadarId = :sewadarId AND sewaDate = :sewaDate " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun latestForDay(sewadarId: Long, sewaDate: String): AttendanceLogEntity?

    @Query(
        "SELECT * FROM attendance_logs WHERE id = :id LIMIT 1",
    )
    suspend fun find(id: Long): AttendanceLogEntity?

    @Query("SELECT * FROM attendance_logs WHERE clientLogId = :clientLogId LIMIT 1")
    suspend fun findByClientLogId(clientLogId: String): AttendanceLogEntity?

    @Insert
    suspend fun insert(entry: AttendanceLogEntity): Long

    @Update
    suspend fun update(entry: AttendanceLogEntity)

    @Query("DELETE FROM attendance_logs WHERE sewadarId = :sewadarId AND sewaDate = :sewaDate")
    suspend fun deleteForDay(sewadarId: Long, sewaDate: String): Int

    @Query("DELETE FROM attendance_logs WHERE id = :id")
    suspend fun deleteById(id: Long): Int

}

@Dao
interface AttendanceOverrideAuditDao {
    @Query("SELECT * FROM attendance_override_audit")
    suspend fun all(): List<AttendanceOverrideAuditEntity>

    @Insert
    suspend fun insert(entry: AttendanceOverrideAuditEntity): Long

}

@Dao
interface FaceTemplateDao {
    @Query("SELECT sewadarId, COUNT(*) AS templateCount FROM face_templates GROUP BY sewadarId")
    fun observeCounts(): Flow<List<FaceTemplateCount>>

    @Query(
        """
        SELECT face_templates.*, sewadars.name AS sewadarName, sewadars.departmentId AS departmentId
        FROM face_templates
        JOIN sewadars ON sewadars.id = face_templates.sewadarId
        WHERE sewadars.isActive = 1
        ORDER BY sewadars.id, face_templates.sampleIndex
        """,
    )
    suspend fun activeTemplates(): List<ActiveFaceTemplate>

    @Query("SELECT face_templates.*, sewadars.name AS sewadarName, sewadars.departmentId AS departmentId FROM face_templates JOIN sewadars ON sewadars.id = face_templates.sewadarId ORDER BY sewadars.name, face_templates.sampleIndex")
    suspend fun allTemplatesForTransfer(): List<ActiveFaceTemplate>

    @Query("SELECT * FROM face_templates WHERE sewadarId = :sewadarId ORDER BY sampleIndex")
    suspend fun forSewadar(sewadarId: Long): List<FaceTemplateEntity>

    @Insert
    suspend fun insertAll(templates: List<FaceTemplateEntity>)

    @Query("DELETE FROM face_templates WHERE sewadarId = :sewadarId")
    suspend fun deleteForSewadar(sewadarId: Long): Int
}

data class ExportRow(
    val sewadarId: Long,
    val name: String,
    val departmentId: Long?,
    val departmentName: String?,
    val attendanceId: Long,
    val arrivalAt: Long?,
    val departureAt: Long?,
    val arrivalEdited: Boolean,
    val departureEdited: Boolean,
    val sewaDate: String,
)
