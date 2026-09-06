package org.sewaentry.tablet.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The tablet's local database.
 *
 * Mirrors the server's shape (departments / sewadars / one attendance row per
 * sewadar per sewa day) so the two can be reconciled later, but stands
 * completely on its own — the register has to keep working with no network at
 * all, which is the whole point of the tablet.
 *
 * Times are stored as epoch milliseconds. A sewa date is a calendar date and
 * is stored as a plain "yyyy-MM-dd" string, so it can never drift a day
 * through a timezone conversion.
 */

@Entity(
    tableName = "departments",
    indices = [Index(value = ["name"], unique = true)],
)
data class DepartmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "sewadars",
    foreignKeys = [
        ForeignKey(
            entity = DepartmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["departmentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("departmentId"), Index("name")],
)
data class SewadarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val departmentId: Long?,
    val phone: String? = null,
    val isActive: Boolean = true,
)

@Entity(
    tableName = "attendance",
    foreignKeys = [
        ForeignKey(
            entity = SewadarEntity::class,
            parentColumns = ["id"],
            childColumns = ["sewadarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // One row per sewadar per sewa day: a sewa day has one arrival and one
    // departure, so the register's shape and the table's shape are the same.
    indices = [
        Index(value = ["sewadarId", "sewaDate"], unique = true),
        Index("sewaDate"),
    ],
)
data class AttendanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sewadarId: Long,
    /** "yyyy-MM-dd" in the device's local timezone. */
    val sewaDate: String,
    /** Snapshot of the department at the time of recording, so later moves don't rewrite history. */
    val departmentId: Long?,

    val arrivalAt: Long? = null,
    val departureAt: Long? = null,

    /** Base64 PNG of the hand-drawn signature, captured at the counter. */
    val arrivalSignature: String? = null,
    val departureSignature: String? = null,

    /**
     * True when an admin typed the time rather than it coming from the device
     * clock — surfaced in the register and in exports so a corrected entry is
     * never mistaken for a live one.
     */
    val arrivalEdited: Boolean = false,
    val departureEdited: Boolean = false,

    @ColumnInfo(defaultValue = "NULL")
    val note: String? = null,

    val updatedAt: Long = System.currentTimeMillis(),
)

/** A sewadar joined with their department name — what the register grid renders. */
data class SewadarWithStatus(
    val sewadarId: Long,
    val name: String,
    val departmentId: Long?,
    val departmentName: String?,
    val attendanceId: Long?,
    val arrivalAt: Long?,
    val departureAt: Long?,
    val arrivalEdited: Boolean,
    val departureEdited: Boolean,
)

/** One row of the analytics tab. */
data class SewadarStats(
    val sewadarId: Long,
    val name: String,
    val departmentName: String?,
    val daysAttended: Int,
    val daysComplete: Int,
    val totalMinutes: Long,
    val lastAttendedDate: String?,
)
