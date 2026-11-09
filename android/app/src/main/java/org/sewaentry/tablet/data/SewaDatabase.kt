package org.sewaentry.tablet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [
        DepartmentEntity::class,
        SewadarEntity::class,
        AttendanceEntity::class,
        AttendanceLogEntity::class,
        AttendanceOverrideAuditEntity::class,
        FaceTemplateEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class SewaDatabase : RoomDatabase() {
    abstract fun departments(): DepartmentDao
    abstract fun sewadars(): SewadarDao
    abstract fun attendance(): AttendanceDao
    abstract fun attendanceLogs(): AttendanceLogDao
    abstract fun attendanceOverrideAudit(): AttendanceOverrideAuditDao
    abstract fun faceTemplates(): FaceTemplateDao

    companion object {
        @Volatile
        private var instance: SewaDatabase? = null

        fun get(context: Context): SewaDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        fun close() = synchronized(this) {
            instance?.close()
            instance = null
        }

        fun openStaging(context: Context, source: File, databaseName: String): SewaDatabase =
            Room.databaseBuilder(context.applicationContext, SewaDatabase::class.java, databaseName)
                .createFromFile(source)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                .build()

        /**
         * Adds the two columns the roster import fills in. Written out rather
         * than falling back to a destructive migration, because by the time
         * anyone upgrades, the tablet holds attendance nobody can retype.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sewadars ADD COLUMN phone2 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sewadars ADD COLUMN isHod INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance ADD COLUMN arrivalMethod TEXT")
                db.execSQL("ALTER TABLE attendance ADD COLUMN departureMethod TEXT")
                db.execSQL("ALTER TABLE attendance ADD COLUMN arrivalAdminId TEXT")
                db.execSQL("ALTER TABLE attendance ADD COLUMN departureAdminId TEXT")
                db.execSQL("ALTER TABLE attendance ADD COLUMN arrivalNote TEXT")
                db.execSQL("ALTER TABLE attendance ADD COLUMN departureNote TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attendance_override_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sewadarId INTEGER NOT NULL,
                        sewaDate TEXT NOT NULL,
                        action TEXT NOT NULL,
                        adminId TEXT NOT NULL,
                        note TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attendance_override_audit_sewadarId_sewaDate " +
                        "ON attendance_override_audit (sewadarId, sewaDate)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS face_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sewadarId INTEGER NOT NULL,
                        sampleIndex INTEGER NOT NULL,
                        modelVersion TEXT NOT NULL,
                        ivBase64 TEXT NOT NULL,
                        encryptedEmbeddingBase64 TEXT NOT NULL,
                        consentVersion TEXT NOT NULL,
                        consentAt INTEGER NOT NULL,
                        enrolledAt INTEGER NOT NULL,
                        FOREIGN KEY(sewadarId) REFERENCES sewadars(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_face_templates_sewadarId_sampleIndex " +
                        "ON face_templates (sewadarId, sampleIndex)",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance ADD COLUMN arrivalLogId INTEGER")
                db.execSQL("ALTER TABLE attendance ADD COLUMN departureLogId INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attendance_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        clientLogId TEXT NOT NULL,
                        sewadarId INTEGER NOT NULL,
                        sewaDate TEXT NOT NULL,
                        departmentId INTEGER,
                        eventType TEXT NOT NULL,
                        verificationMethod TEXT NOT NULL,
                        deviceTime INTEGER NOT NULL,
                        signature TEXT,
                        matchScore REAL,
                        livenessPassed INTEGER,
                        adminOverride INTEGER NOT NULL,
                        adminId TEXT,
                        note TEXT,
                        syncedAt INTEGER,
                        FOREIGN KEY(sewadarId) REFERENCES sewadars(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_logs_clientLogId " +
                        "ON attendance_logs (clientLogId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attendance_logs_sewadarId_sewaDate_id " +
                        "ON attendance_logs (sewadarId, sewaDate, id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attendance_logs_sewadarId_verificationMethod_id " +
                        "ON attendance_logs (sewadarId, verificationMethod, id)",
                )
                db.execSQL(
                    """
                    INSERT INTO attendance_logs (
                        clientLogId, sewadarId, sewaDate, departmentId, eventType,
                        verificationMethod, deviceTime, signature, adminOverride, adminId, note
                    )
                    SELECT 'legacy-in-' || id, sewadarId, sewaDate, departmentId, 'IN',
                           COALESCE(arrivalMethod, 'MANUAL'), arrivalAt, arrivalSignature,
                           CASE WHEN arrivalMethod = 'ADMIN_OVERRIDE' THEN 1 ELSE 0 END,
                           arrivalAdminId, arrivalNote
                    FROM attendance
                    WHERE arrivalAt IS NOT NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO attendance_logs (
                        clientLogId, sewadarId, sewaDate, departmentId, eventType,
                        verificationMethod, deviceTime, signature, adminOverride, adminId, note
                    )
                    SELECT 'legacy-out-' || id, sewadarId, sewaDate, departmentId, 'OUT',
                           COALESCE(departureMethod, 'MANUAL'), departureAt, departureSignature,
                           CASE WHEN departureMethod = 'ADMIN_OVERRIDE' THEN 1 ELSE 0 END,
                           departureAdminId, departureNote
                    FROM attendance
                    WHERE departureAt IS NOT NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE attendance
                    SET arrivalLogId = (
                        SELECT id FROM attendance_logs
                        WHERE clientLogId = 'legacy-in-' || attendance.id
                    )
                    WHERE arrivalAt IS NOT NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE attendance
                    SET departureLogId = (
                        SELECT id FROM attendance_logs
                        WHERE clientLogId = 'legacy-out-' || attendance.id
                    )
                    WHERE departureAt IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sewadars ADD COLUMN organization TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sewadars ADD COLUMN pinHash TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE sewadars
                    SET organization = CASE
                        WHEN organization IS NULL OR TRIM(organization) = '' THEN 'HOD'
                        WHEN INSTR(' ' || UPPER(organization) || ' ', ' HOD ') > 0 THEN organization
                        ELSE organization || ' - HOD'
                    END
                    WHERE isHod = 1
                    """.trimIndent(),
                )
                db.execSQL("UPDATE sewadars SET isHod = 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sewadars SET pinHash = NULL")
            }
        }

        private fun build(context: Context): SewaDatabase =
            Room.databaseBuilder(context, SewaDatabase::class.java, "sewa-entry.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                // Foreign keys are off by default in SQLite and Room only
                // enables them when it created the connection itself.
                .build()

        /*
         * A new tablet starts empty on purpose.
         *
         * There used to be a seed here with nine guessed department names. Now
         * that the roster is imported from the sangat's own contact workbook,
         * that seed was actively harmful: it left "Safai" sitting next to the
         * real "Safai Group A" through "Safai Group J", and "Lost and Found"
         * next to the sheet's "Lost & Found" — near-duplicates that no import
         * can tidy up, because the app cannot know which one the counter means.
         *
         * The Roster tab is the first run: import the sheet, and every
         * department arrives named exactly as the sangat names it.
         */
    }
}
