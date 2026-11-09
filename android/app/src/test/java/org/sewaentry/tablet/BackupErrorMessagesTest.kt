package org.sewaentry.tablet

import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.BackupErrorMessages
import java.io.IOException

class BackupErrorMessagesTest {
    @Test
    fun `wrong backup password has a clear recovery instruction`() {
        val message = BackupErrorMessages.describe(
            "Backup import failed",
            IllegalArgumentException("The transfer password is incorrect or the file is damaged."),
        )

        assertTrue(message.contains("password is incorrect"))
        assertTrue(message.contains("Check the password"))
    }

    @Test
    fun `unreadable backup location explains what to check`() {
        val message = BackupErrorMessages.describe(
            "Backup import failed",
            IOException("Permission denied"),
        )

        assertTrue(message.contains("file could not be read or written"))
        assertTrue(message.contains("Check that the file location"))
    }

    @Test
    fun `invalid backup file tells user which file type to select`() {
        val message = BackupErrorMessages.describe(
            "Backup import failed",
            IllegalArgumentException("This is not a Sewa face backup."),
        )

        assertTrue(message.contains(".sewabackup"))
        assertTrue(message.contains("face-only files"))
    }
}
