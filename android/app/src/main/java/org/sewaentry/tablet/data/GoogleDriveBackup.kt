package org.sewaentry.tablet.data

import android.accounts.Account
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class BackupFrequency(val label: String, val intervalDays: Long) {
    DAILY("Daily", 1),
    WEEKLY("Weekly", 7),
    MONTHLY("Monthly", 30),
    OFF("Off", 0),
}

class GoogleDriveBackupSettings(context: Context) {
    private val prefs = securePrefs(context)

    var frequency: BackupFrequency
        get() = runCatching {
            BackupFrequency.valueOf(prefs.getString(KEY_FREQUENCY, BackupFrequency.OFF.name)!!)
        }.getOrDefault(BackupFrequency.OFF)
        set(value) {
            check(prefs.edit().putString(KEY_FREQUENCY, value.name).commit()) {
                "Could not save the Google Drive backup schedule."
            }
        }

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) {
            check(prefs.edit().putString(KEY_ACCOUNT, value).commit()) {
                "Could not save the Google Drive account."
            }
        }

    var driveFileId: String?
        get() = prefs.getString(KEY_DRIVE_FILE_ID, null)
        set(value) {
            check(prefs.edit().putString(KEY_DRIVE_FILE_ID, value).commit()) {
                "Could not save the Google Drive backup file reference."
            }
        }

    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP, 0L)
        set(value) {
            check(prefs.edit().putLong(KEY_LAST_BACKUP, value).commit()) {
                "Could not save the last backup time."
            }
        }

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) {
            check(prefs.edit().putString(KEY_LAST_ERROR, value).commit()) {
                "Could not save the backup status."
            }
        }

    var statusMessage: String?
        get() = prefs.getString(KEY_STATUS_MESSAGE, null)
        set(value) {
            check(prefs.edit().putString(KEY_STATUS_MESSAGE, value).commit()) {
                "Could not save the backup status."
            }
        }

    companion object {
        private const val PREFS = "sewa-drive-backup"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_ACCOUNT = "account_email"
        private const val KEY_DRIVE_FILE_ID = "drive_file_id"
        private const val KEY_LAST_BACKUP = "last_backup_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_STATUS_MESSAGE = "status_message"

        private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

object GoogleDriveBackupScheduler {
    private const val UNIQUE_BACKUP = "sewa-google-drive-backup"
    private const val UNIQUE_BACKUP_NOW = "sewa-google-drive-backup-now"

    fun setFrequency(context: Context, frequency: BackupFrequency) {
        val manager = WorkManager.getInstance(context)
        if (frequency == BackupFrequency.OFF) {
            manager.cancelUniqueWork(UNIQUE_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<GoogleDriveBackupWorker>(
            frequency.intervalDays,
            TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        manager.enqueueUniquePeriodicWork(
            UNIQUE_BACKUP,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun backupNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<GoogleDriveBackupWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_BACKUP_NOW,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class GoogleDriveBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = GoogleDriveBackupSettings(applicationContext)
        val email = settings.accountEmail
            ?: return failure(settings, "Sign in to Google and connect a backup account first.")
        settings.lastError = null
        settings.statusMessage = "Automatic backup is running."
        return try {
            val token = GoogleAuthUtil.getToken(
                applicationContext,
                Account(email, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE),
                DRIVE_SCOPE,
            )
            val id = BackupManager.withSanitizedBackup(applicationContext) { file ->
                uploadBackup(token, settings.driveFileId, file)
            }
            settings.driveFileId = id
            settings.lastBackupAt = System.currentTimeMillis()
            settings.lastError = null
            settings.statusMessage = "Backup completed successfully."
            Result.success()
        } catch (error: UserRecoverableAuthException) {
            failure(settings, "Google Drive authorization expired. Reconnect the account in Settings.")
        } catch (error: GoogleAuthException) {
            failure(settings, "Google authorization failed. Reconnect the backup account in Settings and try again.")
        } catch (error: IOException) {
            val statusCode = Regex("""HTTP (\d{3})""")
                .find(error.message.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val retryable = statusCode == null || statusCode == 429 || statusCode >= 500
            if (retryable && runAttemptCount < 5) {
                settings.statusMessage = "Backup temporarily failed. It will retry when internet is available."
                Result.retry()
            } else {
                failure(settings, driveErrorMessage(error))
            }
        } catch (error: SecurityException) {
            failure(settings, "Google Drive access was denied. Reconnect the dedicated backup account in Settings.")
        } catch (error: Exception) {
            failure(settings, "Backup failed unexpectedly. Check the connection and Google Drive account, then try Back up now.")
        }
    }

    private fun failure(settings: GoogleDriveBackupSettings, message: String): Result {
        settings.lastError = message
        settings.statusMessage = message
        return Result.failure()
    }

    private fun driveErrorMessage(error: IOException): String {
        val details = error.message.orEmpty()
        return when {
            details.contains("HTTP 401") || details.contains("HTTP 403") ->
                "Google Drive did not authorize this backup. Reconnect the dedicated Google account in Settings."
            details.contains("HTTP 404") ->
                "The saved Google Drive backup file is no longer available. Reconnect the account and try Back up now to create a new backup."
            details.contains("HTTP 429") || details.contains("HTTP 5") ->
                "Google Drive is temporarily unavailable or rate-limiting backups. The backup will retry when possible."
            else ->
                "Could not upload the backup. Check that the tablet is connected to the internet and that Google Drive has available storage."
        }
    }

    private fun uploadBackup(accessToken: String, savedFileId: String?, file: File): String {
        val fileId = savedFileId ?: findBackupFile(accessToken)
        val metadata = JSONObject()
            .put("name", BACKUP_FILE_NAME)
            .put("mimeType", "application/vnd.sqlite3")
        val boundary = "sewa-${System.currentTimeMillis()}"
        val url = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (fileId == null) "POST" else "PATCH"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connection.outputStream.buffered().use { output ->
                output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
                output.write(metadata.toString().toByteArray())
                output.write("\r\n--$boundary\r\nContent-Type: application/vnd.sqlite3\r\n\r\n".toByteArray())
                file.inputStream().buffered().use { it.copyTo(output) }
                output.write("\r\n--$boundary--".toByteArray())
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw IOException("Drive returned HTTP $code: $response")
            return JSONObject(response).getString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun findBackupFile(accessToken: String): String? {
        val query = "name='$BACKUP_FILE_NAME' and trashed=false"
        val url = URL(
            "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}" +
                "&pageSize=1&orderBy=modifiedTime%20desc&fields=files(id)",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw IOException("Drive returned HTTP $code: $response")
            val files = JSONObject(response).getJSONArray("files")
            return if (files.length() == 0) null else files.getJSONObject(0).getString("id")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "sewa-entry-backup.db"
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
    }
}
