package org.sewaentry.tablet.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    fun suggestedFileName(): String =
        "sewa-entry-backup_${TIMESTAMP_FMT.format(LocalDateTime.now())}.db"

    /**
     * Writes the current database to [uri] via the content resolver.
     * Call from a background thread.
     */
    fun backupTo(context: Context, uri: Uri) {
        val db = SewaDatabase.get(context)
        // Checkpoint: flush the WAL into the main database file so the copy
        // is self-contained. Room keeps the connection open, so this is safe.
        // execSQL refuses anything that returns rows, and a checkpoint pragma
        // reports how much it moved - so it has to be run as a query.
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }

        val dbFile = context.getDatabasePath(DB_NAME)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(dbFile).use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open the chosen location for writing.")
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

        // Close Room so we can overwrite the file.
        SewaDatabase.close()

        val dbFile = context.getDatabasePath(DB_NAME)
        FileOutputStream(dbFile).use { out ->
            out.write(header, 0, read)
            stream.use { it.copyTo(out) }
        }

        // Delete WAL and SHM so Room opens the restored file cleanly.
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }
}
