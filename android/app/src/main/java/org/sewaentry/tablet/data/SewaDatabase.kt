package org.sewaentry.tablet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [DepartmentEntity::class, SewadarEntity::class, AttendanceEntity::class],
    version = 1,
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

        private fun build(context: Context): SewaDatabase =
            Room.databaseBuilder(context, SewaDatabase::class.java, "sewa-entry.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Seeding runs off the Room callback thread; the
                        // database is usable by the time this fires.
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            seed(get(context))
                        }
                    }
                })
                // Foreign keys are off by default in SQLite and Room only
                // enables them when it created the connection itself.
                .build()

        /**
         * First-run content: the departments the sangat actually runs. No
         * sewadars — those get added by the admin on the tablet, because a
         * roster of invented names would be worse than an empty one.
         */
        private suspend fun seed(db: SewaDatabase) {
            if (db.departments().count() > 0) return
            val names = listOf(
                "Accounts", "BAV", "Safai", "Lost and Found",
                "Traffic", "Luggage", "Mobile", "Transport", "Langar",
            )
            names.forEachIndexed { index, name ->
                db.departments().insert(DepartmentEntity(name = name, sortOrder = index))
            }
        }
    }
}
