package org.sewaentry.tablet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.sewaentry.tablet.data.BackupFrequency
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.face.FaceScanMode

@Composable
fun SettingsScreen(
    backupFrequency: BackupFrequency,
    backupAccountEmail: String?,
    lastBackupAt: Long,
    backupError: String?,
    backupStatus: String?,
    pinOnFaceScan: Boolean,
    requireFaceEntryConfirmation: Boolean,
    faceScanMode: FaceScanMode,
    googleConfigured: Boolean,
    onSignInBackupAccount: () -> Unit,
    onFrequencyChange: (BackupFrequency) -> Unit,
    onBackupNow: () -> Unit,
    onPinChange: (Boolean) -> Unit,
    onFaceEntryConfirmationChange: (Boolean) -> Unit,
    onFaceScanModeChange: (FaceScanMode) -> Unit,
    onChangeAdminPin: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenRoster: () -> Unit,
    departments: List<DepartmentEntity>,
    onRenameDepartment: (Long, String) -> Unit,
) {
    val sortedDepartments = departments.sortedBy { it.name.lowercase() }
    var showDepartmentDialog by remember { mutableStateOf(false) }
    var departmentExpanded by remember { mutableStateOf(false) }
    var faceScanModeExpanded by remember { mutableStateOf(false) }
    var selectedDepartmentId by remember { mutableStateOf<Long?>(null) }
    var departmentName by remember { mutableStateOf("") }

    if (showDepartmentDialog) {
        AlertDialog(
            onDismissRequest = { showDepartmentDialog = false },
            title = { Text("Edit a Department") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Department", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(
                            onClick = { departmentExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = sortedDepartments.isNotEmpty(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            border = BorderStroke(1.5.dp, InkMuted),
                        ) {
                            Text(
                                sortedDepartments.firstOrNull { it.id == selectedDepartmentId }?.name
                                    ?: "Select department",
                            )
                        }
                        DropdownMenu(
                            expanded = departmentExpanded,
                            onDismissRequest = { departmentExpanded = false },
                        ) {
                            sortedDepartments.forEach { department ->
                                DropdownMenuItem(
                                    text = { Text(department.name) },
                                    onClick = {
                                        selectedDepartmentId = department.id
                                        departmentName = department.name
                                        departmentExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = departmentName,
                        onValueChange = { departmentName = it },
                        label = { Text("New department name") },
                        singleLine = true,
                        enabled = selectedDepartmentId != null,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedDepartmentId?.let { onRenameDepartment(it, departmentName.trim()) }
                        showDepartmentDialog = false
                    },
                    enabled = selectedDepartmentId != null && departmentName.isNotBlank(),
                ) { Text("Confirm and rename") }
            },
            dismissButton = {
                TextButton(onClick = { showDepartmentDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Admin pages are protected by the on-device PIN.", color = Color.Gray)

        SettingsCard(title = "Kiosk screen pinning") {
            Text(
                "Request Android screen pinning when Face Scan opens. Standard screen pinning can still be exited using the device's system gesture.",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = pinOnFaceScan, onClick = { onPinChange(!pinOnFaceScan) })
                Text(if (pinOnFaceScan) "Request pinning on Face Scan" else "Do not pin automatically")
            }
        }

        SettingsCard(title = "Face attendance confirmation") {
            Text(
                "When enabled, the scanner pauses after recognizing a person and shows Confirm and Cancel. " +
                    "Turn this off to record attendance directly.",
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (requireFaceEntryConfirmation) "Ask before recording attendance" else "Record directly",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = requireFaceEntryConfirmation,
                    onCheckedChange = onFaceEntryConfirmationChange,
                )
            }
        }

        SettingsCard(title = "Face scan mode") {
            Text(
                "Direct detection skips the head-turn liveness check. Liveness detection requires the current center-side-center challenge.",
            )
            Box {
                OutlinedButton(
                    onClick = { faceScanModeExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, InkMuted),
                ) {
                    Text(faceScanMode.label)
                }
                DropdownMenu(
                    expanded = faceScanModeExpanded,
                    onDismissRequest = { faceScanModeExpanded = false },
                ) {
                    FaceScanMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                faceScanModeExpanded = false
                                onFaceScanModeChange(option)
                            },
                        )
                    }
                }
            }
        }

        SettingsCard(title = "Admin PIN") {
            Text("Change the six-digit PIN used to protect admin pages and attendance edits.")
            Button(onClick = onChangeAdminPin) {
                Text("Change admin PIN")
            }
        }

        SettingsCard(title = "Analytics") {
            Text("Review attendance summaries and export reports saved on this tablet.")
            Button(onClick = onOpenAnalytics) {
                Text("Check analytics")
            }
        }

        SettingsCard(title = "Google Drive backup") {
            Text(
                "Automatic backups upload roster and attendance to the connected dedicated Google account. " +
                    "Face templates are excluded; transfer those separately using the password-protected face backup.",
            )
            Text(
                backupAccountEmail?.let { "Connected account: $it" } ?: "No Google Drive account connected.",
                color = Color.Gray,
            )
            Text("Automatic backup schedule (runs when internet is available)", fontWeight = FontWeight.SemiBold)
            BackupFrequency.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = backupFrequency == option,
                        onClick = { onFrequencyChange(option) },
                    )
                    Text(option.label)
                }
            }
            TextButton(onClick = onSignInBackupAccount, enabled = googleConfigured) {
                Text(if (backupAccountEmail == null) "Connect Google Drive" else "Change Google account")
            }
            Button(onClick = onBackupNow, enabled = backupAccountEmail != null) {
                Text("Back up now")
            }
            if (lastBackupAt > 0L) {
                Text("Last successful backup: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastBackupAt))}")
            }
            backupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ?: backupStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!googleConfigured) {
                Text(
                    "Google sign-in is not configured in this build. The developer must add the Google Web OAuth client ID before an account can be connected.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Drive backups are uploaded over the internet to the selected Google account. They do not include face images or encrypted face templates.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsCard(title = "Roster and data transfer") {
            Text(
                "Manage the roster spreadsheet, full backup, and separate face-data transfer. " +
                    "These options are kept together to reduce accidental changes.",
                color = Color.Gray,
            )
            Button(onClick = onOpenRoster, modifier = Modifier.fillMaxWidth()) {
                Text("Open roster and backup options")
            }
        }

        SettingsCard(title = "Departments") {
            Text(
                "Rename a department without changing its ID. Existing attendance remains assigned to its recorded department.",
                color = Color.Gray,
            )
            Button(
                onClick = {
                    selectedDepartmentId = sortedDepartments.firstOrNull()?.id
                    departmentName = sortedDepartments.firstOrNull()?.name.orEmpty()
                    showDepartmentDialog = true
                },
                enabled = sortedDepartments.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit a Department")
            }
        }

    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}
