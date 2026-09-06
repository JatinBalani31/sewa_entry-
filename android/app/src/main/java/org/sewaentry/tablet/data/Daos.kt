package org.sewaentry.tablet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DepartmentDao {
    @Query("SELECT * FROM departments WHERE isActive = 1 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<DepartmentEntity>>

    @Query("SELECT * FROM departments ORDER BY sortOrder, name")
    suspend fun all(): List<DepartmentEntity>

    @Query("SELECT COUNT(*) FROM departments")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(department: DepartmentEntity): Long

    @Update
    suspend fun update(department: DepartmentEntity)
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
}

@Dao
interface AttendanceDao {
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
               d.name         AS departmentName,
               COUNT(a.id)    AS daysAttended,
               SUM(CASE WHEN a.departureAt IS NOT NULL THEN 1 ELSE 0 END) AS daysComplete,
               COALESCE(SUM(
                   CASE WHEN a.departureAt IS NOT NULL AND a.arrivalAt IS NOT NULL
                        THEN (a.departureAt - a.arrivalAt) / 60000
                        ELSE 0 END
               ), 0)          AS totalMinutes,
               MAX(a.sewaDate) AS lastAttendedDate
        FROM sewadars s
        LEFT JOIN departments d ON d.id = s.departmentId
        LEFT JOIN attendance a
               ON a.sewadarId = s.id
              AND a.arrivalAt IS NOT NULL
              AND a.sewaDate BETWEEN :from AND :to
        WHERE s.isActive = 1
        GROUP BY s.id, s.name, d.name
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
