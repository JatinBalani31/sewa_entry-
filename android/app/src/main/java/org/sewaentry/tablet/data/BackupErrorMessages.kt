package org.sewaentry.tablet.data

import android.database.sqlite.SQLiteException
import java.io.IOException
import javax.crypto.AEADBadTagException

object BackupErrorMessages {
    fun describe(action: String, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val message = causes.firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        val details = causes.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString(" | ")
        val lower = details.lowercase()

        val explanation = when {
            causes.any { it is AEADBadTagException } ||
                lower.contains("password is incorrect") ||
                lower.contains("password is wrong") ->
                "The backup password is incorrect, or the backup file is damaged. Check the password and try again."

            causes.any { it is SecurityException } ->
                "Android could not access the selected file. Choose it again and allow access when prompted."

            causes.any { it is IOException } ->
                "The selected file could not be read or written. Check that the file location is available and has free space, then try again."

            listOf(
                    "migration",
                    "cannot verify data integrity",
                    "verify the data integrity",
                    "identity hash",
                    "no such table",
                    "no such column",
                ).any(lower::contains) ->
                "This backup was made with an incompatible app/database version. Update both tablets to the same app version, create a new backup, and try again."

            causes.any { it is SQLiteException } &&
                listOf("constraint", "foreign key", "unique").any(lower::contains) ->
                "The backup contains records that conflict with the destination data. Nothing was imported; check the roster on both tablets and try again."

            causes.any { it is SQLiteException } ->
                "The destination database could not merge this backup. No records were imported. Update both tablets to the latest app version and try again${message?.let { " (details: $it)" } ?: "."}"

            causes.any { it.javaClass.simpleName == "JSONException" } ||
                lower.contains("not a valid database") ||
                lower.contains("file is not a database") ||
                lower.contains("malformed database schema") ||
                lower.contains("unsupported local backup format") ||
                lower.contains("not a sewa face backup") ->
                "This file is not a valid full Sewa backup, or it is incomplete. Select the .sewabackup file created by the Backup option; Excel and face-only files cannot be restored here."

            message != null ->
                message

            else ->
                "No further technical details were provided. Check that the file is a complete .sewabackup and retry; if it continues, share this message with support."
        }

        return "$action: $explanation"
    }
}
