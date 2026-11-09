package org.sewaentry.tablet.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backs up and restores the Room database through Android's Storage Access
 * Framework, so the admin can save to Google Drive, a USB stick, or anywhere
 * else the device's file picker can reach — no API keys, no cloud setup.
 *
 * The database is a single SQLite file. Before copying it out we checkpoint
 * the WAL so every committed transaction is in the main file, then copy.
 * On restore we close Room, overwrite the file, and reopen.
 */
object BackupManager {

    private const val DB_NAME = "sewa-entry.db"
    private const val CURRENT_DATABASE_VERSION = 6

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    fun suggestedFileName(): String =
        "sewa-entry-backup_${TIMESTAMP_FMT.format(LocalDateTime.now())}.db"

    /**
     * Writes the current database to [uri] via the content resolver.
     * Call from a background thread.
     */
    fun backupTo(context: Context, uri: Uri) {
        withSanitizedBackup(context) { backupFile ->
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(backupFile).use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open the chosen location for writing.")
        }
    }

    fun <T> withSanitizedBackup(context: Context, action: (File) -> T): T {
        val db = SewaDatabase.get(context)
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }

        val dbFile = context.getDatabasePath(DB_NAME)
        val backupFile = File.createTempFile("sewa-entry-backup-", ".db", context.cacheDir)
        try {
            FileInputStream(dbFile).use { input ->
                FileOutputStream(backupFile).use { output -> input.copyTo(output) }
            }
            removeFaceTemplates(backupFile)
            return action(backupFile)
        } finally {
            backupFile.delete()
            File(backupFile.path + "-wal").delete()
            File(backupFile.path + "-shm").delete()
        }
    }

    /**
     * Replaces the current database with the file at [uri].
     * Call from a background thread. The caller must restart the process
     * afterwards (or at minimum recreate the Room singleton) because Room
     * caches the open connection.
     */
    fun restoreFrom(context: Context, uri: Uri) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the backup file.")

        // Validate: the first 16 bytes of a SQLite file are "SQLite format 3\0".
        val header = ByteArray(16)
        val read = stream.read(header)
        if (read < 16 || !String(header, 0, 15).startsWith("SQLite format 3")) {
            stream.close()
            throw IllegalArgumentException("That file is not a valid database backup.")
        }

        val bytes = header + stream.use { it.readBytes() }
        restoreDatabaseBytes(context, bytes)
    }

    /** Replaces the Room file after the containing backup package is verified. */
    fun restoreDatabaseBytes(context: Context, bytes: ByteArray) {
        require(bytes.size >= 16 && String(bytes, 0, 15).startsWith("SQLite format 3")) {
            "That file does not contain a valid SQLite database."
        }
        val dbFile = context.getDatabasePath(DB_NAME)
        val candidate = File.createTempFile("sewa-restore-", ".db", context.cacheDir)
        try {
            FileOutputStream(candidate).use { it.write(bytes) }
            SQLiteDatabase.openDatabase(candidate.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { checkDb ->
                val integrity = checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
                require(integrity) { "The backup database is damaged and was not restored." }
                val version = checkDb.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                require(version in 1..CURRENT_DATABASE_VERSION) { "This backup database version is not supported by this app." }
            }
            SewaDatabase.close()
            try {
                Files.move(candidate.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(candidate.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            candidate.delete()
        }
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private fun removeFaceTemplates(backupFile: File) {
        val backupDb = SQLiteDatabase.openDatabase(
            backupFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        backupDb.use { db ->
            val hasFaceTemplates = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'face_templates'",
                null,
            ).use { it.moveToFirst() }
            if (hasFaceTemplates) {
                db.delete("face_templates", null, null)
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
    }
}
