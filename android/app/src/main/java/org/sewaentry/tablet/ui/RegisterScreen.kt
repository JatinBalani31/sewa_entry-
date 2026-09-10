package org.sewaentry.tablet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

// Sized for a finger on a 10" tablet, not a mouse.
private val ACTION_BUTTON_HEIGHT = 76.dp
private val ACTION_BUTTON_WIDTH = 210.dp

// A phone screen has nowhere near 210dp x 3 to spare, so the same buttons
// shrink rather than push each other off the edge. Wide enough that "Manual"
// and "Arrival" each stay on one line rather than wrapping mid-word.
private val COMPACT_ACTION_BUTTON_HEIGHT = 60.dp
private val COMPACT_ACTION_BUTTON_WIDTH = 156.dp

@Composable
fun RegisterScreen(vm: RegisterViewModel) {
    val date by vm.date.collectAsState()
    val departments by vm.departments.collectAsState()
    val departmentId by vm.departmentId.collectAsState()
    val roster by vm.roster.collectAsState()
    val selected by vm.selected.collectAsState()
    val recordedDates by vm.recordedDates.collectAsState()
    val isToday by vm.isToday.collectAsState()

    var capture by remember { mutableStateOf<Capture?>(null) }
    // The action bar floats over the grid, so the grid needs to know how tall
    // it is - otherwise the last row of tiles sits underneath it, unreadable
    // and untappable.
    var actionBarHeightPx by remember { mutableIntStateOf(0) }
    var addingSewadar by remember { mutableStateOf(false) }
    var manualFor by remember { mutableStateOf<SewadarWithStatus?>(null) }

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {

            SewaDateBar(
                selected = date,
                recordedDates = recordedDates,
                onSelect = vm::selectDate,
            )

            Spacer(Modifier.height(12.dp))

            val present = roster.count { it.arrivalAt != null }
            val complete = roster.count { it.departureAt != null }
            val compact = isCompactWidth()

            if (compact) {
                // A phone has no room for the dropdown, the summary pills and
                // "Add sewadar" on one line, so the row splits into two.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DepartmentDropdown(
                        departments = departments.map { it.id to it.name },
                        selectedId = departmentId,
                        onSelect = vm::selectDepartment,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { addingSewadar = true },
                        enabled = departmentId != null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Summary(present = present, complete = complete, total = roster.size)
                    if (!isToday) {
                        Spacer(Modifier.width(12.dp))
                        Pill("Past day - manual entry only", AmberBg, Amber)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DepartmentDropdown(
                        departments = departments.map { it.id to it.name },
                        selectedId = departmentId,
                        onSelect = vm::selectDepartment,
                        modifier = Modifier.width(340.dp),
                    )

                    Spacer(Modifier.width(12.dp))

                    Summary(present = present, complete = complete, total = roster.size)

                    if (!isToday) {
                        Spacer(Modifier.width(12.dp))
                        Pill("Past day - manual entry only", AmberBg, Amber)
                    }

                    Spacer(Modifier.weight(1f))

                    OutlinedButton(
                        onClick = { addingSewadar = true },
                        enabled = departmentId != null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(58.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add sewadar", fontSize = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (roster.isEmpty()) {
                EmptyRoster(hasDepartment = departmentId != null)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    // Leave room underneath for the floating action bar, so the
                    // last tile can be scrolled clear of it rather than sitting
                    // behind it.
                    contentPadding = PaddingValues(
                        bottom = with(LocalDensity.current) { actionBarHeightPx.toDp() },
                    ),
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

        if (selected == null && actionBarHeightPx != 0) actionBarHeightPx = 0

        selected?.let { sewadar ->
            val compact = isCompactWidth()
            ActionBar(
                sewadar = sewadar,
                // On a past day the device clock is meaningless, so the live
                // buttons are withheld entirely and Manual is the only way in.
                allowLiveCapture = isToday,
                compact = compact,
                onArrival = { capture = Capture.Arrival(sewadar) },
                onDeparture = { capture = Capture.Departure(sewadar) },
                onManual = { manualFor = sewadar },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Outside the padding, so the grid is told the whole
                    // footprint the bar occupies, gap included.
                    .onSizeChanged { actionBarHeightPx = it.height }
                    .padding(if (compact) 12.dp else 20.dp)
                    .let { if (compact) it.fillMaxWidth() else it },
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

    manualFor?.let { sewadar ->
        ManualTimeDialog(
            sewadar = sewadar,
            date = date,
            isToday = isToday,
            onDismiss = { manualFor = null },
            onSet = { arrival, hour, minute ->
                vm.manualSetTime(sewadar.sewadarId, arrival, hour, minute)
                manualFor = null
            },
            onClear = {
                vm.clearRecord(sewadar.sewadarId)
                manualFor = null
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = foreground)
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
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Department", fontSize = 12.sp, color = InkMuted, fontWeight = FontWeight.SemiBold)
                    Text(label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = "Choose department", tint = Navy)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            departments.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 18.sp, modifier = Modifier.padding(vertical = 6.dp)) },
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
 * One name in the grid — a large rounded tile showing at a glance whether this
 * person has arrived and left.
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
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier
            .height(178.dp)
            .border(if (isSelected) 4.dp else 1.dp, borderColor, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text(
                sewadar.name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // The contact sheet marks the HOD in bold; carrying that onto the
            // tile means whoever is on the counter can see who leads a team
            // without having to know the department.
            if (sewadar.isHod) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "HOD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                    modifier = Modifier
                        .background(SurfaceAlt, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = if (arrived) SewaDays.formatTime(sewadar.arrivalAt) else "In",
                    filled = arrived,
                    accent = ArrivalGreen,
                    edited = sewadar.arrivalEdited,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = if (departed) SewaDays.formatTime(sewadar.departureAt) else "Out",
                    filled = departed,
                    accent = DepartureRed,
                    edited = sewadar.departureEdited,
                )
            }

            if (arrived && departed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    SewaDays.formatDuration(SewaDays.minutesBetween(sewadar.arrivalAt, sewadar.departureAt)),
                    fontSize = 15.sp,
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
    edited: Boolean,
) {
    Box(
        Modifier
            .background(if (filled) accent else Color.Transparent, RoundedCornerShape(999.dp))
            .then(if (!filled) Modifier.border(2.dp, Line, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            // A trailing asterisk marks a time an admin typed rather than one
            // the device recorded.
            if (edited) "$text*" else text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.White else InkMuted,
        )
    }
}

/**
 * Bottom-right action bar for the picked sewadar.
 *
 * On today: Arrival first, then Departure once there is an arrival to close,
 * with Manual always available alongside. On any past day the live buttons are
 * absent entirely — the device clock would record the wrong thing.
 */
@Composable
private fun ActionBar(
    sewadar: SewadarWithStatus,
    allowLiveCapture: Boolean,
    onArrival: () -> Unit,
    onDeparture: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val arrived = sewadar.arrivalAt != null
    val departed = sewadar.departureAt != null

    val nameBlock: @Composable () -> Unit = {
        Column(if (compact) Modifier else Modifier.padding(end = 20.dp)) {
            Text(sewadar.name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                when {
                    departed -> "Complete"
                    arrived -> "Signed in at ${SewaDays.formatTime(sewadar.arrivalAt)}"
                    else -> "Not yet arrived"
                },
                fontSize = 14.sp,
                color = InkMuted,
            )
        }
    }

    val controls: @Composable () -> Unit = {
        ManualButton(onClick = onManual, compact = compact)

        if (allowLiveCapture) {
            Spacer(Modifier.width(12.dp))

            if (!arrived) {
                LiveButton(
                    label = "Arrival",
                    icon = Icons.Filled.Login,
                    accent = ArrivalGreen,
                    onClick = onArrival,
                    compact = compact,
                )
            } else {
                FilledChipButton("Arrived ${SewaDays.formatTime(sewadar.arrivalAt)}", ArrivalGreen, compact)
                Spacer(Modifier.width(12.dp))
                if (!departed) {
                    LiveButton(
                        label = "Departure",
                        icon = Icons.Filled.Logout,
                        accent = DepartureRed,
                        onClick = onDeparture,
                        compact = compact,
                    )
                } else {
                    FilledChipButton("Left ${SewaDays.formatTime(sewadar.departureAt)}", DepartureRed, compact)
                }
            }
        } else {
            // Past day: show what is on record, with no way to capture live.
            Spacer(Modifier.width(12.dp))
            RecordedChip(
                text = if (arrived) "In ${SewaDays.formatTime(sewadar.arrivalAt)}" else "No arrival",
                accent = if (arrived) ArrivalGreen else InkMuted,
                compact = compact,
            )
            Spacer(Modifier.width(12.dp))
            RecordedChip(
                text = if (departed) "Out ${SewaDays.formatTime(sewadar.departureAt)}" else "No departure",
                accent = if (departed) DepartureRed else InkMuted,
                compact = compact,
            )
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = modifier,
    ) {
        if (compact) {
            // Splitting the name onto its own line, above the buttons, frees
            // the whole card width for the controls — the thing most likely
            // to otherwise run out of room on a phone. A horizontal scroll
            // stays as a last-resort fallback, never the primary path.
            Column(Modifier.padding(14.dp)) {
                nameBlock()
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) { controls() }
            }
        } else {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                nameBlock()
                controls()
            }
        }
    }
}

/** Grey, and the same size as Arrival so the bar reads as one row of controls. */
@Composable
private fun ManualButton(onClick: () -> Unit, compact: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .height(if (compact) COMPACT_ACTION_BUTTON_HEIGHT else ACTION_BUTTON_HEIGHT)
            .width(if (compact) COMPACT_ACTION_BUTTON_WIDTH else ACTION_BUTTON_WIDTH),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = InkMuted),
        border = BorderStroke(2.dp, Line),
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Manual",
            fontSize = if (compact) 16.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LiveButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .height(if (compact) COMPACT_ACTION_BUTTON_HEIGHT else ACTION_BUTTON_HEIGHT)
            .width(if (compact) COMPACT_ACTION_BUTTON_WIDTH else ACTION_BUTTON_WIDTH),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        border = BorderStroke(3.dp, accent),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = if (compact) 16.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun FilledChipButton(label: String, color: Color, compact: Boolean = false) {
    Box(
        Modifier
            .height(if (compact) COMPACT_ACTION_BUTTON_HEIGHT else ACTION_BUTTON_HEIGHT)
            .background(color, RoundedCornerShape(16.dp))
            .padding(horizontal = if (compact) 16.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (compact) 18.dp else 24.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
            Text(
                label,
                color = Color.White,
                fontSize = if (compact) 14.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun RecordedChip(text: String, accent: Color, compact: Boolean = false) {
    Box(
        Modifier
            .height(if (compact) COMPACT_ACTION_BUTTON_HEIGHT else ACTION_BUTTON_HEIGHT)
            .background(SurfaceAlt, RoundedCornerShape(16.dp))
            .border(2.dp, Line, RoundedCornerShape(16.dp))
            .padding(horizontal = if (compact) 14.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = accent,
            fontSize = if (compact) 14.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun EmptyRoster(hasDepartment: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                // With no departments at all this is a tablet that has not been
                // set up yet, so it points at the import rather than at a
                // department picker with nothing in it.
                if (hasDepartment) "No sewadars in this department yet" else "No departments yet",
                fontSize = 20.sp,
                color = InkMuted,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (hasDepartment) {
                    "Use \"Add sewadar\", or import the contact sheet from the Roster tab."
                } else {
                    "Open the Roster tab and import the sangat's contact sheet - " +
                        "every department and sewadar arrives at once."
                },
                fontSize = 16.sp,
                color = InkMuted,
            )
        }
    }
}
