package org.sewaentry.tablet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sewaentry.tablet.roster.ParsedSheet

@Composable
fun RosterScreen(vm: RegisterViewModel, onRestoreComplete: () -> Unit = {}) {
    val state by vm.importState.collectAsState()
    val departments by vm.departments.collectAsState()
    val backupBusy by vm.backupBusy.collectAsState()
    val needsRestart by vm.needsRestart.collectAsState()
    var showPinSetup by remember { mutableStateOf(false) }

    if (needsRestart) {
        onRestoreComplete()
        return
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.previewImport(it) } }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { vm.backupTo(it) } }

    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingRestoreUri = it } }

    if (showPinSetup) {
        Box(
            Modifier.fillMaxSize().background(Background),
            contentAlignment = Alignment.Center,
        ) {
            AdminGate(
                configured = vm.pinConfigured,
                onUnlock = { pin -> vm.unlock(pin).also { if (it) showPinSetup = false } },
                onSetPin = { pin -> vm.setPin(pin).also { if (it) showPinSetup = false } },
            )
        }
        return
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore from backup?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This replaces everything on the tablet — all departments, sewadars and " +
                        "attendance records — with whatever is in the backup file. The app " +
                        "will restart afterwards.\n\nThis cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingRestoreUri!!
                        pendingRestoreUri = null
                        vm.restoreFrom(uri)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DepartureRed),
                ) { Text("Restore", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            },
        )
    }

    val compact = isCompactWidth()

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {

        Column(Modifier.padding(bottom = 4.dp)) {
            Text("Roster", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "${departments.size} departments on this tablet",
                fontSize = 14.sp,
                color = InkMuted,
            )
        }

        Spacer(Modifier.height(10.dp))

        val restoreButton: @Composable (Modifier) -> Unit = { mod ->
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                enabled = !backupBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = mod.height(52.dp),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            }
        }

        val backupButton: @Composable (Modifier) -> Unit = { mod ->
            OutlinedButton(
                onClick = { backupLauncher.launch(vm.backupFileName) },
                enabled = !backupBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = mod.height(52.dp),
            ) {
                if (backupBusy) {
                    CircularProgressIndicator(color = Navy, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Backup", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            }
        }

        val importButton: @Composable (Modifier) -> Unit = { mod ->
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = mod.height(52.dp),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Import from Excel",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
        }

        if (compact) {
            // A phone has no room for three buttons on one line, and relying
            // on a sideways scroll to find "Import" turned out to hide it
            // entirely on some screens. Two full-width rows always fit.
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    restoreButton(Modifier.weight(1f))
                    backupButton(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                importButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                restoreButton(Modifier)
                Spacer(Modifier.width(10.dp))
                backupButton(Modifier)
                Spacer(Modifier.width(10.dp))
                importButton(Modifier.width(240.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            state.loading -> Centered { CircularProgressIndicator(color = Navy) }

            state.error != null -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, null, tint = DepartureRed, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(state.error!!, fontSize = 16.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = vm::clearImport) { Text("Try another file") }
                }
            }

            state.plan != null -> ImportPreview(vm)

            else -> Explainer(
                showPinPrompt = !vm.pinConfigured && departments.isNotEmpty(),
                onSetPin = { showPinSetup = true },
            )
        }
    }
}

@Composable
private fun Explainer(showPinPrompt: Boolean = false, onSetPin: () -> Unit = {}) {
    LazyColumn {
        if (showPinPrompt) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberBg),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "No admin PIN set",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                            )
                            Text(
                                "Anyone can access the Summary and Roster tabs. " +
                                    "Set a 4-digit PIN to protect them.",
                                fontSize = 14.sp,
                                color = InkMuted,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Button(
                            onClick = onSetPin,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Navy),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Icon(Icons.Filled.Lock, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Set PIN", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Import the contact sheet", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Pick the sangat's \"List of HOD & All Sewadar Contact Detail\" workbook. " +
                            "Every sheet is read as one department: the names, both contact numbers, " +
                            "and the bold names as that department's HOD.",
                        fontSize = 15.sp,
                        color = InkMuted,
                    )
                    Spacer(Modifier.height(16.dp))
                    listOf(
                        "You see exactly what will change before anything is saved.",
                        "Re-importing an updated sheet edits the people already here — it never makes a second copy.",
                        "Nobody is ever deleted, so recorded sewa can never be lost.",
                    ).forEach { line ->
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                null,
                                tint = ArrivalGreen,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontSize = 14.sp, color = Ink)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Backup & Restore", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Back up the entire database — all departments, sewadars and attendance " +
                            "records — to Google Drive or any other location. If the tablet is " +
                            "lost or replaced, restore from the backup on the new device.",
                        fontSize = 15.sp,
                        color = InkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "Tap Backup → pick Google Drive (or any folder) → the file is saved.",
                        "Tap Restore → pick a backup file → confirm → the tablet is restored.",
                        "Back up regularly so no recorded sewa is ever lost.",
                    ).forEach { line ->
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                null,
                                tint = ArrivalGreen,
                                modifier = Modifier.size(18.dp).padding(top = 2.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontSize = 14.sp, color = Ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportPreview(vm: RegisterViewModel) {
    val state by vm.importState.collectAsState()
    val plan = state.plan ?: return
    val selection = state.selection
    val compact = isCompactWidth()

    val departments = plan.departments(selection)
    val people = plan.sewadarCount(selection)
    val hods = plan.hodCount(selection)
    val missingHod = plan.withoutHod(selection)

    Column(Modifier.fillMaxSize()) {

        // Everything above the action bar scrolls as one list. The tiles and
        // the notices used to sit outside the scroll, and with a real workbook
        // — forty-odd sheets, a "no bold name in ..." line naming a dozen
        // departments — they filled the screen on their own and pushed the
        // Import button off the bottom, where nobody could reach it.
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {

            item {
                if (compact) {
                    // Four tiles on one line get unreadably narrow on a phone,
                    // so they wrap into a 2x2 grid instead.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("Departments", departments.size.toString(), Modifier.weight(1f))
                            StatTile("Sewadars", people.toString(), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("HODs found", hods.toString(), Modifier.weight(1f))
                            StatTile(
                                "Sheets skipped",
                                plan.skipped.size.toString(),
                                Modifier.weight(1f),
                                accent = if (plan.skipped.isNotEmpty()) Amber else null,
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        StatTile("Departments", departments.size.toString(), Modifier.weight(1f))
                        StatTile("Sewadars", people.toString(), Modifier.weight(1f))
                        StatTile("HODs found", hods.toString(), Modifier.weight(1f))
                        StatTile(
                            "Sheets skipped",
                            plan.skipped.size.toString(),
                            Modifier.weight(1f),
                            accent = if (plan.skipped.isNotEmpty()) Amber else null,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (plan.conflicts.isNotEmpty()) {
                item {
                    Notice(
                        "${plan.conflicts.size} sheet(s) name a department that another sheet already covers. " +
                            "They are left out — tick one only if it really is part of that department.",
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (missingHod.isNotEmpty()) {
                item {
                    Notice("No bold name in: ${missingHod.joinToString()}. They import with no HOD marked.")
                    Spacer(Modifier.height(10.dp))
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        plan.sheets.forEach { sheet ->
                            SheetRow(
                                sheet = sheet,
                                checked = sheet.sheet in selection,
                                onToggle = { vm.toggleImportSheet(sheet.sheet) },
                            )
                        }
                        plan.skipped.forEach { skipped ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    "${skipped.sheet} - skipped, ${skipped.reason}",
                                    fontSize = 13.sp,
                                    color = InkMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val archiveRow: @Composable () -> Unit = {
            Row(
                Modifier.clickable { vm.setDeactivateMissing(!state.deactivateMissing) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.deactivateMissing,
                    onCheckedChange = { vm.setDeactivateMissing(it) },
                    colors = CheckboxDefaults.colors(checkedColor = Navy),
                )
                Column {
                    Text(
                        "Archive anyone no longer on the sheet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    Text(
                        "They leave the register but keep every hour they have already done.",
                        fontSize = 12.sp,
                        color = InkMuted,
                    )
                }
            }
        }

        val actionButtons: @Composable () -> Unit = {
            OutlinedButton(
                onClick = vm::clearImport,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(58.dp),
            ) { Text("Cancel", fontSize = 16.sp) }

            Spacer(Modifier.width(10.dp))

            Button(
                onClick = vm::confirmImport,
                enabled = !state.applying && people > 0,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ArrivalGreen),
                modifier = Modifier.height(58.dp).let { if (compact) it.weight(1f) else it.width(250.dp) },
            ) {
                if (state.applying) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        "Import $people sewadars",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (compact) {
            // A phone has no room for the checkbox, Cancel and Import all on
            // one line — cramming them in was squeezing the checkbox's own
            // label down to almost nothing. They stack instead.
            Column {
                archiveRow()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) { actionButtons() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { archiveRow() }
                actionButtons()
            }
        }
    }
}

@Composable
private fun SheetRow(sheet: ParsedSheet, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Navy),
        )
        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f)) {
            Text(sheet.department, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            val detail = buildString {
                append("${sheet.sewadars.size} sewadars")
                if (sheet.hods.isNotEmpty()) append(" - HOD ${sheet.hods.joinToString { it.name }}")
                if (sheet.tables > 1) append(" - ${sheet.tables} tables")
                if (sheet.duplicatesMerged > 0) append(" - ${sheet.duplicatesMerged} repeat(s) merged")
                if (!sheet.sheet.equals(sheet.department, ignoreCase = true)) append(" - sheet \"${sheet.sheet}\"")
            }
            Text(detail, fontSize = 12.sp, color = InkMuted)
        }

        sheet.conflictsWith?.let { other ->
            Row(
                Modifier
                    .background(AmberBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("also \"$other\"", fontSize = 12.sp, color = Amber, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AmberBg, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = Ink)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
