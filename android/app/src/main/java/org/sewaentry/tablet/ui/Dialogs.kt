package org.sewaentry.tablet.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.sewaentry.tablet.data.SewadarWithStatus
import org.sewaentry.tablet.data.PinAttemptResult
import org.sewaentry.tablet.data.AdminLock
import kotlin.math.ceil

@Composable
fun AddSewadarDialog(
    departmentName: String,
    onDismiss: () -> Unit,
    onVerifyPin: (String) -> PinAttemptResult,
    onAdd: (name: String, phone: String?, organization: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 480.dp).heightIn(max = 750.dp),
        ) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text("Add sewadar", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("They will join $departmentName.", fontSize = 14.sp, color = InkMuted)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (isCompactWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Contact (optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Contact (optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Confirmation: Are you sure you want to add this sewadar to $departmentName?",
                    fontSize = 13.sp,
                    color = InkMuted,
                )

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(AdminLock.LENGTH)
                        error = null
                    },
                    label = { Text("Six-digit Admin PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = DepartureRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = InkMuted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val result = onVerifyPin(pin)
                            if (result.accepted) {
                                onAdd(
                                    name.trim(),
                                    phone.trim().ifBlank { null },
                                    organization.trim().ifBlank { null },
                                )
                            } else {
                                error = pinError(result)
                            }
                        },
                        enabled = name.isNotBlank() && pin.length == AdminLock.LENGTH,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                    ) { Text("Confirm & Add") }
                }
            }
        }
    }
}

@Composable
fun RemoveSewadarDialog(
    sewadarName: String,
    departmentName: String,
    onDismiss: () -> Unit,
    onVerifyPin: (String) -> PinAttemptResult,
    onConfirmRemove: () -> Unit,
) {
    var confirmAgain by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmAgain) "Confirm removal" else "Remove sewadar?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (confirmAgain) {
                        "Remove $sewadarName from $departmentName now? This cannot be undone here. " +
                            "Their attendance history and face data will be preserved."
                    } else {
                        "Are you sure you want to remove $sewadarName from $departmentName? " +
                            "They will be hidden from the active register. Their saved records will remain."
                    },
                )
                if (confirmAgain) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(AdminLock.LENGTH); error = null },
                        label = { Text("Six-digit Admin PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(it, color = DepartureRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!confirmAgain) {
                        confirmAgain = true
                    } else {
                        val result = onVerifyPin(pin)
                        if (result.accepted) {
                            onConfirmRemove()
                        } else {
                            error = pinError(result)
                        }
                    }
                },
                enabled = !confirmAgain || pin.length == AdminLock.LENGTH,
                colors = ButtonDefaults.buttonColors(containerColor = DepartureRed),
            ) { Text(if (confirmAgain) "Confirm & Remove" else "Continue") }
        },
        dismissButton = {
            TextButton(onClick = if (confirmAgain) ({ confirmAgain = false }) else onDismiss) {
                Text(if (confirmAgain) "Back" else "Cancel")
            }
        },
    )
}

@Composable
fun EditSewadarDialog(
    sewadar: SewadarWithStatus,
    departments: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onVerifyPin: (String) -> PinAttemptResult,
    onSave: (name: String, phone: String?, organization: String?, departmentId: Long) -> Unit,
) {
    var name by remember(sewadar.sewadarId) { mutableStateOf(sewadar.name) }
    var phone by remember(sewadar.sewadarId) { mutableStateOf(sewadar.phone.orEmpty()) }
    var organization by remember(sewadar.sewadarId) { mutableStateOf(sewadar.organization.orEmpty()) }
    var departmentId by remember(sewadar.sewadarId) {
        mutableStateOf(sewadar.departmentId ?: departments.firstOrNull()?.first)
    }
    var departmentExpanded by remember { mutableStateOf(false) }
    var pendingDepartmentId by remember { mutableStateOf<Long?>(null) }
    var pin by remember(sewadar.sewadarId) { mutableStateOf("") }
    var error by remember(sewadar.sewadarId) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit sewadar details", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCompactWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Contact") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Contact") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Department :", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Box {
                        OutlinedButton(
                            onClick = { departmentExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.5.dp, InkMuted),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                departments.firstOrNull { it.first == departmentId }?.second
                                    ?: "Select department",
                            )
                        }
                        DropdownMenu(
                            expanded = departmentExpanded,
                            onDismissRequest = { departmentExpanded = false },
                        ) {
                            departments.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        if (id != departmentId) {
                                            pendingDepartmentId = id
                                        } else {
                                            departmentExpanded = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(AdminLock.LENGTH); error = null },
                    label = { Text("Six-digit Admin PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = DepartureRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = onVerifyPin(pin)
                    if (result.accepted) {
                        departmentId?.let { onSave(name, phone, organization, it) }
                    } else {
                        error = pinError(result)
                    }
                },
                enabled = name.isNotBlank() && pin.length == AdminLock.LENGTH && departmentId != null,
            ) {
                Text("Confirm & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    pendingDepartmentId?.let { newDepartmentId ->
        val currentName = departments.firstOrNull { it.first == departmentId }?.second.orEmpty()
        val newName = departments.firstOrNull { it.first == newDepartmentId }?.second.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDepartmentId = null },
            title = { Text("Change department?") },
            text = {
                Text(
                    "This will change the department of ${sewadar.name} from $currentName to $newName. " +
                        "Existing records will be preserved. Do you wish to proceed?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        departmentId = newDepartmentId
                        pendingDepartmentId = null
                        departmentExpanded = false
                    },
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDepartmentId = null }) { Text("Cancel") }
            },
        )
    }
}

private fun pinError(result: PinAttemptResult): String =
    if (result.retryAfterMillis > 0) {
        val minutes = ceil(result.retryAfterMillis / 60_000.0).toInt().coerceAtLeast(1)
        "Too many incorrect attempts. Try again in $minutes min."
    } else {
        "Incorrect Admin PIN. Try again."
    }
