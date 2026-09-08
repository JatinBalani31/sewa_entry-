package org.sewaentry.tablet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sewaentry.tablet.roster.ParsedSheet

/**
 * Loading the sangat's roster from the contact workbook.
 *
 * The sangat already maintains "List of HOD & All Sewadar Contact Detail" — one
 * sheet per department, names down the side, the HOD in bold. Rather than
 * asking anyone to retype 400-odd names into a tablet, the app reads that file
 * as it is.
 *
 * Nothing is written until the admin has seen exactly what would happen, sheet
 * by sheet, and pressed the button.
 */
@Composable
fun RosterScreen(vm: RegisterViewModel) {
    val state by vm.importState.collectAsState()
    val departments by vm.departments.collectAsState()

    val picker = rememberLauncherForActivityResult(
        // Any file: some file managers hand back a generic MIME type for an
        // .xlsx, and a filter that hides the file the admin is looking at is
        // worse than one that lets them pick the wrong thing and be told.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.previewImport(it) } }

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Roster", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "${departments.size} departments on this tablet",
                    fontSize = 14.sp,
                    color = InkMuted,
                )
            }
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = Modifier.height(62.dp).width(260.dp),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Import from Excel", fontWeight = FontWeight.Bold, fontSize = 17.sp)
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

            else -> Explainer()
        }
    }
}

@Composable
private fun Explainer() {
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

@Composable
private fun ImportPreview(vm: RegisterViewModel) {
    val state by vm.importState.collectAsState()
    val plan = state.plan ?: return
    val selection = state.selection

    val departments = plan.departments(selection)
    val people = plan.sewadarCount(selection)
    val hods = plan.hodCount(selection)
    val missingHod = plan.withoutHod(selection)

    Column(Modifier.fillMaxSize()) {

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

        Spacer(Modifier.height(12.dp))

        if (plan.conflicts.isNotEmpty()) {
            Notice(
                "${plan.conflicts.size} sheet(s) name a department that another sheet already covers. " +
                    "They are left out — tick one only if it really is part of that department.",
            )
            Spacer(Modifier.height(10.dp))
        }
        if (missingHod.isNotEmpty()) {
            Notice("No bold name in: ${missingHod.joinToString()}. They import with no HOD marked.")
            Spacer(Modifier.height(10.dp))
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn {
                items(plan.sheets, key = { it.sheet }) { sheet ->
                    SheetRow(
                        sheet = sheet,
                        checked = sheet.sheet in selection,
                        onToggle = { vm.toggleImportSheet(sheet.sheet) },
                    )
                }
                if (plan.skipped.isNotEmpty()) {
                    items(plan.skipped, key = { "skip-${it.sheet}" }) { skipped ->
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

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { vm.setDeactivateMissing(!state.deactivateMissing) },
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
                modifier = Modifier.height(58.dp).width(250.dp),
            ) {
                if (state.applying) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text("Import $people sewadars", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
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

