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
    /** The contact sheet carries up to two numbers per sewadar. */
    @ColumnInfo(defaultValue = "NULL")
    val phone2: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val organization: String? = null,
    /** Retained for schema compatibility; migration 7-8 moves this marker into organization. */
    @ColumnInfo(defaultValue = "0")
    @Deprecated("HOD status is represented as organization text.")
    val isHod: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    @Deprecated("Sewadar-level PINs are no longer used.")
    val pinHash: String? = null,
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
    // A compatibility summary row per sewadar/day. Every attendance transition
    // is also retained in attendance_logs.
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

    val arrivalMethod: String? = null,
    val departureMethod: String? = null,
    val arrivalAdminId: String? = null,
    val departureAdminId: String? = null,
    val arrivalNote: String? = null,
    val departureNote: String? = null,

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

    val arrivalLogId: Long? = null,
    val departureLogId: Long? = null,

    @ColumnInfo(defaultValue = "NULL")
    val note: String? = null,

    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "attendance_override_audit",
    indices = [Index(value = ["sewadarId", "sewaDate"])],
)
data class AttendanceOverrideAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sewadarId: Long,
    val sewaDate: String,
    val action: String,
    val adminId: String,
    val note: String,
    val occurredAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "attendance_logs",
    foreignKeys = [
        ForeignKey(
            entity = SewadarEntity::class,
            parentColumns = ["id"],
            childColumns = ["sewadarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["clientLogId"], unique = true),
        Index(value = ["sewadarId", "sewaDate", "id"]),
        Index(value = ["sewadarId", "verificationMethod", "id"]),
    ],
)
data class AttendanceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientLogId: String,
    val sewadarId: Long,
    val sewaDate: String,
    val departmentId: Long?,
    val eventType: String,
    val verificationMethod: String,
    val deviceTime: Long,
    val signature: String? = null,
    val matchScore: Float? = null,
    val livenessPassed: Boolean? = null,
    val adminOverride: Boolean = false,
    val adminId: String? = null,
    val note: String? = null,
    val syncedAt: Long? = null,
)

@Entity(
    tableName = "face_templates",
    foreignKeys = [
        ForeignKey(
            entity = SewadarEntity::class,
            parentColumns = ["id"],
            childColumns = ["sewadarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sewadarId", "sampleIndex"], unique = true)],
)
data class FaceTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sewadarId: Long,
    val sampleIndex: Int,
    val modelVersion: String,
    val ivBase64: String,
    val encryptedEmbeddingBase64: String,
    val consentVersion: String,
    val consentAt: Long,
    val enrolledAt: Long,
)

/** A sewadar joined with their department name — what the register grid renders. */
data class SewadarWithStatus(
    val sewadarId: Long,
    val name: String,
    val departmentId: Long?,
    val departmentName: String?,
    val phone: String?,
    val organization: String?,
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

data class FaceTemplateCount(
    val sewadarId: Long,
    val templateCount: Int,
)

data class ActiveFaceTemplate(
    @androidx.room.Embedded val template: FaceTemplateEntity,
    val sewadarName: String,
    val departmentId: Long?,
)
