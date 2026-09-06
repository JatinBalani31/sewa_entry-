package org.sewaentry.tablet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarWithStatus

@Composable
fun AddSewadarDialog(
    departmentName: String,
    onDismiss: () -> Unit,
    onAdd: (name: String, phone: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.width(460.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
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
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit) },
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = InkMuted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onAdd(name.trim(), phone.trim().ifBlank { null }) },
                        enabled = name.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                    ) { Text("Add") }
                }
            }
        }
    }
}

/**
 * Admin correction of a recorded time.
 *
 * Kept deliberately separate from the tap flow: a tap always takes the device
 * clock, and this is the only path that sets a time by hand. Anything set here
 * is flagged as edited so the register and the export both show it.
 */
@Composable
fun AdminTimeDialog(
    sewadar: SewadarWithStatus,
    onDismiss: () -> Unit,
    onSet: (arrival: Boolean, hour: Int, minute: Int) -> Unit,
    onClear: () -> Unit,
) {
    var editingArrival by remember { mutableStateOf(sewadar.departureAt == null) }
    val existing = if (editingArrival) sewadar.arrivalAt else sewadar.departureAt

    var hhmm by remember(editingArrival) {
        mutableStateOf(if (existing != null) SewaDays.formatTime(existing) else "")
    }

    val parsed = parseHhMm(hhmm)
    val canSave = parsed != null && (editingArrival || sewadar.arrivalAt != null)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.width(480.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Correct a time", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "${sewadar.name} - set by hand, and marked as an admin correction.",
                    fontSize = 14.sp,
                    color = InkMuted,
                )

                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth()) {
                    SegmentButton(
                        label = "Arrival",
                        selected = editingArrival,
                        accent = ArrivalGreen,
                        onClick = { editingArrival = true },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    SegmentButton(
                        label = "Departure",
                        selected = !editingArrival,
                        accent = DepartureRed,
                        onClick = { editingArrival = false },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = hhmm,
                    onValueChange = { input ->
                        // Accept digits and a colon only, so the field can never
                        // hold something that is not a time.
                        hhmm = input.filter { it.isDigit() || it == ':' }.take(5)
                    },
                    label = { Text("Time (HH:MM, 24 hour)") },
                    placeholder = { Text("09:30") },
                    singleLine = true,
                    isError = hhmm.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!editingArrival && sewadar.arrivalAt == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Record an arrival before setting a departure.",
                        fontSize = 13.sp,
                        color = DepartureRed,
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (sewadar.arrivalAt != null) {
                        TextButton(onClick = onClear) {
                            Text("Clear entry", color = DepartureRed)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = InkMuted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { parsed?.let { (h, m) -> onSet(editingArrival, h, m) } },
                        enabled = canSave,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                    ) { Text("Save time") }
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent else SurfaceAlt,
            contentColor = if (selected) androidx.compose.ui.graphics.Color.White else InkMuted,
        ),
        modifier = modifier.height(48.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

/** "9:30" and "09:30" both parse; anything out of range does not. */
private fun parseHhMm(value: String): Pair<Int, Int>? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (parts[1].length != 2) return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}
