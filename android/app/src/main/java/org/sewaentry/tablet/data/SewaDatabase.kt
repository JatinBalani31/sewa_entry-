package org.sewaentry.tablet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DepartmentEntity::class, SewadarEntity::class, AttendanceEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SewaDatabase : RoomDatabase() {
    abstract fun departments(): DepartmentDao
    abstract fun sewadars(): SewadarDao
    abstract fun attendance(): AttendanceDao

    companion object {
        @Volatile
        private var instance: SewaDatabase? = null

        fun get(context: Context): SewaDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

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

        private fun build(context: Context): SewaDatabase =
            Room.databaseBuilder(context, SewaDatabase::class.java, "sewa-entry.db")
                .addMigrations(MIGRATION_1_2)
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
