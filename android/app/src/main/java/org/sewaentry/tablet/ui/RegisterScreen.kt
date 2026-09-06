package org.sewaentry.tablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarWithStatus

/** Which signature dialog is open, if any. */
private sealed interface Capture {
    data class Arrival(val sewadar: SewadarWithStatus) : Capture
    data class Departure(val sewadar: SewadarWithStatus) : Capture
}

@Composable
fun RegisterScreen(vm: RegisterViewModel) {
    val date by vm.date.collectAsState()
    val departments by vm.departments.collectAsState()
    val departmentId by vm.departmentId.collectAsState()
    val roster by vm.roster.collectAsState()
    val selected by vm.selected.collectAsState()
    val recordedDates by vm.recordedDates.collectAsState()

    var capture by remember { mutableStateOf<Capture?>(null) }
    var addingSewadar by remember { mutableStateOf(false) }
    var adminEditing by remember { mutableStateOf<SewadarWithStatus?>(null) }

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {

            SewaDateBar(
                selected = date,
                recordedDates = recordedDates,
                onSelect = vm::selectDate,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                DepartmentDropdown(
                    departments = departments.map { it.id to it.name },
                    selectedId = departmentId,
                    onSelect = vm::selectDepartment,
                    modifier = Modifier.width(320.dp),
                )

                Spacer(Modifier.width(12.dp))

                val present = roster.count { it.arrivalAt != null }
                val complete = roster.count { it.departureAt != null }
                Summary(present = present, complete = complete, total = roster.size)

                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    onClick = { addingSewadar = true },
                    enabled = departmentId != null,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add sewadar")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (roster.isEmpty()) {
                EmptyRoster(hasDepartment = departmentId != null)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 190.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(roster, key = { it.sewadarId }) { sewadar ->
                        SewadarTile(
                            sewadar = sewadar,
                            isSelected = sewadar.sewadarId == selected?.sewadarId,
                            onClick = {
                                vm.selectSewadar(
                                    if (sewadar.sewadarId == selected?.sewadarId) null else sewadar.sewadarId,
                                )
                            },
                        )
                    }
                }
            }
        }

        // The action bar only exists once somebody is picked — before that
        // there is nothing to act on, and an always-present disabled button
        // would just be noise.
        selected?.let { sewadar ->
            ActionBar(
                sewadar = sewadar,
                onArrival = { capture = Capture.Arrival(sewadar) },
                onDeparture = { capture = Capture.Departure(sewadar) },
                onAdminEdit = { adminEditing = sewadar },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    when (val current = capture) {
        is Capture.Arrival -> SignatureDialog(
            title = current.sewadar.name,
            subtitle = "Sign to record arrival at ${SewaDays.formatTime(System.currentTimeMillis())}",
            accent = ArrivalGreen,
            onDismiss = { capture = null },
            onConfirm = { signature ->
                vm.recordArrival(current.sewadar.sewadarId, signature)
                capture = null
            },
        )

        is Capture.Departure -> SignatureDialog(
            title = current.sewadar.name,
            subtitle = "Sign to record departure at ${SewaDays.formatTime(System.currentTimeMillis())}",
            accent = DepartureRed,
            onDismiss = { capture = null },
            onConfirm = { signature ->
                vm.recordDeparture(current.sewadar.sewadarId, signature)
                capture = null
            },
        )

        null -> Unit
    }

    if (addingSewadar) {
        AddSewadarDialog(
            departmentName = departments.firstOrNull { it.id == departmentId }?.name ?: "",
            onDismiss = { addingSewadar = false },
            onAdd = { name, phone ->
                vm.addSewadar(name, phone)
                addingSewadar = false
            },
        )
    }

    adminEditing?.let { sewadar ->
        AdminTimeDialog(
            sewadar = sewadar,
            onDismiss = { adminEditing = null },
            onSet = { arrival, hour, minute ->
                vm.adminSetTime(sewadar.sewadarId, arrival, hour, minute)
                adminEditing = null
            },
            onClear = {
                vm.clearRecord(sewadar.sewadarId)
                adminEditing = null
            },
        )
    }
}

@Composable
private fun Summary(present: Int, complete: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("$present of $total arrived", ArrivalGreenBg, ArrivalGreen)
        Spacer(Modifier.width(8.dp))
        Pill("$complete departed", if (complete > 0) AmberBg else SurfaceAlt, if (complete > 0) Amber else InkMuted)
    }
}

@Composable
private fun Pill(text: String, background: Color, foreground: Color) {
    Box(
        Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = foreground)
    }
}

@Composable
private fun DepartmentDropdown(
    departments: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = departments.firstOrNull { it.first == selectedId }?.second ?: "Select department"

    Box(modifier) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Department", fontSize = 11.sp, color = InkMuted, fontWeight = FontWeight.SemiBold)
                    Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = "Choose department", tint = Navy)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            departments.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 16.sp) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                    trailingIcon = {
                        if (id == selectedId) Icon(Icons.Filled.Check, null, tint = ArrivalGreen)
                    },
                )
            }
        }
    }
}

/**
 * One name in the grid — a big rounded tile, sized for a finger, showing at a
 * glance whether this person has arrived and left.
 */
@Composable
private fun SewadarTile(
    sewadar: SewadarWithStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val arrived = sewadar.arrivalAt != null
    val departed = sewadar.departureAt != null

    val container = when {
        departed -> SurfaceAlt
        arrived -> ArrivalGreenBg
        else -> Surface
    }
    val borderColor = when {
        isSelected -> Navy
        departed -> Line
        arrived -> ArrivalGreen
        else -> Line
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .height(132.dp)
            .border(if (isSelected) 3.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text(
                sewadar.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = if (arrived) SewaDays.formatTime(sewadar.arrivalAt) else "In",
                    filled = arrived,
                    accent = ArrivalGreen,
                    background = ArrivalGreenBg,
                    edited = sewadar.arrivalEdited,
                )
                Spacer(Modifier.width(6.dp))
                StatusChip(
                    text = if (departed) SewaDays.formatTime(sewadar.departureAt) else "Out",
                    filled = departed,
                    accent = DepartureRed,
                    background = DepartureRedBg,
                    edited = sewadar.departureEdited,
                )
            }

            if (arrived && departed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    SewaDays.formatDuration(SewaDays.minutesBetween(sewadar.arrivalAt, sewadar.departureAt)),
                    fontSize = 12.sp,
                    color = InkMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    filled: Boolean,
    accent: Color,
    background: Color,
    edited: Boolean,
) {
    Box(
        Modifier
            .background(if (filled) accent else Color.Transparent, RoundedCornerShape(999.dp))
            .then(if (!filled) Modifier.border(1.dp, Line, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            if (edited) "$text*" else text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.White else InkMuted,
        )
    }
}

/**
 * Bottom-right action bar for the picked sewadar: arrival first, and departure
 * only appears once there is an arrival to close.
 */
@Composable
private fun ActionBar(
    sewadar: SewadarWithStatus,
    onArrival: () -> Unit,
    onDeparture: () -> Unit,
    onAdminEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrived = sewadar.arrivalAt != null
    val departed = sewadar.departureAt != null

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 16.dp)) {
                Text(sewadar.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    when {
                        departed -> "Complete"
                        arrived -> "Signed in at ${SewaDays.formatTime(sewadar.arrivalAt)}"
                        else -> "Not yet arrived"
                    },
                    fontSize = 12.sp,
                    color = InkMuted,
                )
            }

            TextButton(onClick = onAdminEdit) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = InkMuted)
                Spacer(Modifier.width(4.dp))
                Text("Admin", color = InkMuted, fontSize = 13.sp)
            }

            Spacer(Modifier.width(8.dp))

            if (!arrived) {
                OutlinedButton(
                    onClick = onArrival,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(64.dp).width(190.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ArrivalGreen),
                    border = androidx.compose.foundation.BorderStroke(2.dp, ArrivalGreen),
                ) {
                    Icon(Icons.Filled.Login, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Arrival", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                FilledChipButton(
                    label = "Arrived ${SewaDays.formatTime(sewadar.arrivalAt)}",
                    color = ArrivalGreen,
                )

                Spacer(Modifier.width(10.dp))

                if (!departed) {
                    OutlinedButton(
                        onClick = onDeparture,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(64.dp).width(190.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DepartureRed),
                        border = androidx.compose.foundation.BorderStroke(2.dp, DepartureRed),
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Departure", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    FilledChipButton(
                        label = "Left ${SewaDays.formatTime(sewadar.departureAt)}",
                        color = DepartureRed,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilledChipButton(label: String, color: Color) {
    Box(
        Modifier
            .height(64.dp)
            .background(color, RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyRoster(hasDepartment: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (hasDepartment) "No sewadars in this department yet" else "Choose a department to begin",
                fontSize = 18.sp,
                color = InkMuted,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasDepartment) {
                Spacer(Modifier.height(6.dp))
                Text("Use \"Add sewadar\" to build the roster.", fontSize = 14.sp, color = InkMuted)
            }
        }
    }
}
