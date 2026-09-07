package org.sewaentry.tablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.DepartmentGroup
import org.sewaentry.tablet.data.Quarter
import org.sewaentry.tablet.data.ReportPeriod
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarTotals
import java.time.LocalDate

/**
 * Individual hours, which is what the whole system exists to report.
 *
 * Three independent controls: how far back to look, which sewa days count
 * (all / Wednesdays / Sundays), and how to group the result (overall, weekly,
 * monthly, quarterly). The Excel export carries exactly the same selection, so
 * what is on screen is what lands in the file.
 */
@Composable
fun StatsScreen(vm: RegisterViewModel) {
    val state by vm.report.collectAsState()
    val quarter by vm.quarter.collectAsState()
    val from by vm.from.collectAsState()
    val to by vm.to.collectAsState()
    val dayFilter by vm.dayFilter.collectAsState()
    val period by vm.period.collectAsState()
    val departments by vm.departments.collectAsState()
    val reportDept by vm.reportDepartmentId.collectAsState()

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Quarterly summary - ${quarter.label}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    "${quarter.monthsLabel} - ${state.sewaDates.size} sewa days recorded" +
                        if (dayFilter != org.sewaentry.tablet.data.DayFilter.ALL) " - ${dayFilter.label}" else "",
                    fontSize = 14.sp,
                    color = InkMuted,
                )
            }

            Button(
                onClick = { vm.exportExcel() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = Modifier.height(62.dp).width(230.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Export Excel", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- Filters ---------------------------------------------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                // The quarter is the primary control: picking one sets the
                // whole reporting period, because "the Q3 summary" is the
                // thing being produced.
                FilterRow("Quarter") {
                    QuarterStepper(
                        quarter = quarter,
                        onSelect = vm::selectQuarter,
                    )
                }

                Spacer(Modifier.height(12.dp))

                FilterRow("Or a range") {
                    RangeChip("This month", from == LocalDate.now().withDayOfMonth(1)) {
                        vm.setRange(LocalDate.now().withDayOfMonth(1), LocalDate.now())
                    }
                    RangeChip("6 months", from == LocalDate.now().minusMonths(6)) {
                        vm.setRange(LocalDate.now().minusMonths(6), LocalDate.now())
                    }
                    RangeChip("1 year", from == LocalDate.now().minusYears(1)) {
                        vm.setRange(LocalDate.now().minusYears(1), LocalDate.now())
                    }
                    RangeChip("All time", from.year <= 2000) {
                        vm.setRange(LocalDate.of(2000, 1, 1), LocalDate.now())
                    }
                }

                Spacer(Modifier.height(12.dp))

                FilterRow("Sewa days") {
                    DayFilter.entries.forEach { option ->
                        RangeChip(option.label, dayFilter == option) { vm.setDayFilter(option) }
                    }
                }

                Spacer(Modifier.height(12.dp))

                FilterRow("Report") {
                    ReportPeriod.entries.forEach { option ->
                        RangeChip(option.label, period == option) { vm.setPeriod(option) }
                    }
                }

                Spacer(Modifier.height(12.dp))

                FilterRow("Department") {
                    DepartmentPicker(
                        departments = departments.map { it.id to it.name },
                        selectedId = reportDept,
                        onSelect = vm::setReportDepartment,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        val totalHours = state.totals.sumOf { it.hours }
        val attended = state.totals.count { it.daysAttended > 0 }
        val open = state.totals.sumOf { it.openDays }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Sewadars with hours", attended.toString(), Modifier.weight(1f))
            StatTile("Total hours", String.format("%.1f", totalHours), Modifier.weight(1f))
            StatTile("Sewa days counted", state.rows.map { it.sewaDate }.distinct().size.toString(), Modifier.weight(1f))
            StatTile(
                "Days without sign-out",
                open.toString(),
                Modifier.weight(1f),
                accent = if (open > 0) Amber else null,
            )
        }

        Spacer(Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (period == ReportPeriod.OVERALL) {
                Column {
                    TotalsHeader()
                    if (state.totals.isEmpty()) {
                        EmptyState(state.loading)
                    } else {
                        // Department by department with a subtotal under each —
                        // the shape of the summary that used to be typed up by
                        // hand, so it is recognisable to whoever checks it.
                        LazyColumn {
                            state.departments.forEach { group ->
                                item(key = "head-${group.departmentName}") {
                                    DepartmentHeading(group.departmentName)
                                }
                                items(group.sewadars, key = { it.sewadarId }) { TotalsRow(it) }
                                item(key = "sub-${group.departmentName}") {
                                    DepartmentSubtotal(group)
                                }
                            }
                            if (state.departments.size > 1) {
                                item(key = "grand") { GrandTotal(state.departments) }
                            }
                        }
                    }
                }
            } else {
                PeriodMatrix(vm)
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            Modifier.width(120.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = InkMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Year stepper plus the four quarters — two taps to any summary. */
@Composable
private fun QuarterStepper(quarter: Quarter, onSelect: (Quarter) -> Unit) {
    val thisQuarter = Quarter.current()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onSelect(Quarter(quarter.year - 1, quarter.quarter)) },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Navy),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(50.dp),
        ) { Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Text(
            quarter.year.toString(),
            Modifier.padding(horizontal = 14.dp),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )

        Button(
            onClick = { onSelect(Quarter(quarter.year + 1, quarter.quarter)) },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Navy),
            contentPadding = PaddingValues(horizontal = 14.dp),
            // A quarter that has not started yet has nothing to summarise.
            enabled = quarter.year < thisQuarter.year,
            modifier = Modifier.height(50.dp),
        ) { Text(">", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.width(16.dp))

        (1..4).forEach { q ->
            val candidate = Quarter(quarter.year, q)
            val future = candidate.start.isAfter(LocalDate.now())
            Row {
                Button(
                    onClick = { onSelect(candidate) },
                    enabled = !future,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (quarter.quarter == q) Navy else SurfaceAlt,
                        contentColor = if (quarter.quarter == q) Color.White else InkMuted,
                        disabledContainerColor = SurfaceAlt,
                        disabledContentColor = Line,
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp),
                    modifier = Modifier.height(50.dp),
                ) {
                    Text("Q$q", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        if (quarter != thisQuarter) {
            Button(
                onClick = { onSelect(thisQuarter) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ArrivalGreenBg, contentColor = ArrivalGreen),
                contentPadding = PaddingValues(horizontal = 18.dp),
                modifier = Modifier.height(50.dp),
            ) { Text("This quarter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Navy else SurfaceAlt,
            contentColor = if (selected) Color.White else InkMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.height(50.dp),
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun DepartmentPicker(
    departments: List<Pair<Long, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = departments.firstOrNull { it.first == selectedId }?.second ?: "All departments"

    Box {
        Button(
            onClick = { expanded = true },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Ink),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.height(50.dp),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All departments", fontSize = 17.sp) },
                onClick = { onSelect(null); expanded = false },
            )
            departments.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 17.sp) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = modifier,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkMuted)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent ?: Ink)
        }
    }
}

@Composable
private fun TotalsHeader() {
    Row(Modifier.fillMaxWidth().background(SurfaceAlt).padding(horizontal = 18.dp, vertical = 14.dp)) {
        HeaderCell("Sewadar", Modifier.weight(2.2f))
        HeaderCell("Department", Modifier.weight(1.4f))
        HeaderCell("Days", Modifier.weight(0.8f))
        HeaderCell("Complete", Modifier.weight(1f))
        HeaderCell("Total hours", Modifier.weight(1f))
        HeaderCell("Avg / day", Modifier.weight(1f))
        HeaderCell("Last sewa", Modifier.weight(1.2f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text.uppercase(), modifier, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkMuted)
}

@Composable
private fun DepartmentHeading(name: String) {
    Row(
        Modifier.fillMaxWidth().background(Background).padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(name.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun DepartmentSubtotal(group: DepartmentGroup) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceAlt)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${group.departmentName} total",
            Modifier.weight(2.2f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            "${group.activeCount} active",
            Modifier.weight(1.4f),
            fontSize = 13.sp,
            color = InkMuted,
        )
        Text(group.daysAttended.toString(), Modifier.weight(0.8f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(group.daysComplete.toString(), Modifier.weight(1f), fontSize = 15.sp, color = Ink)
        Text(
            String.format("%.1f", group.hours),
            Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Navy,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1.2f))
    }
}

@Composable
private fun GrandTotal(groups: List<DepartmentGroup>) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("GRAND TOTAL", Modifier.weight(2.2f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "${groups.sumOf { it.activeCount }} sewadars",
            Modifier.weight(1.4f),
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.75f),
        )
        Text(
            groups.sumOf { it.daysAttended }.toString(),
            Modifier.weight(0.8f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            groups.sumOf { it.daysComplete }.toString(),
            Modifier.weight(1f),
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.85f),
        )
        Text(
            String.format("%.1f", groups.sumOf { it.hours }),
            Modifier.weight(1f),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1.2f))
    }
}

@Composable
private fun TotalsRow(stat: SewadarTotals) {
    val average = if (stat.daysComplete > 0) stat.hours / stat.daysComplete else 0.0

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stat.name, Modifier.weight(2.2f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(stat.departmentName ?: "--", Modifier.weight(1.4f), fontSize = 15.sp, color = InkMuted)
        Text(stat.daysAttended.toString(), Modifier.weight(0.8f), fontSize = 15.sp, color = Ink)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stat.daysComplete.toString(), fontSize = 15.sp, color = Ink)
            if (stat.openDays > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.background(AmberBg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("${stat.openDays} open", fontSize = 11.sp, color = Amber, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            String.format("%.1f", stat.hours),
            Modifier.weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(String.format("%.1f", average), Modifier.weight(1f), fontSize = 15.sp, color = InkMuted)
        Text(stat.lastAttended ?: "--", Modifier.weight(1.2f), fontSize = 14.sp, color = InkMuted)
    }
}

/**
 * Sewadars down the side, periods across the top. Scrolls horizontally
 * because a year of weeks is far wider than any tablet.
 */
@Composable
private fun PeriodMatrix(vm: RegisterViewModel) {
    val state by vm.report.collectAsState()
    val period by vm.period.collectAsState()
    val scroll = rememberScrollState()

    val byKey = remember(state.byPeriod) { state.byPeriod.associateBy { it.sewadarId to it.periodKey } }

    if (state.totals.isEmpty()) {
        EmptyState(state.loading)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(SurfaceAlt).padding(vertical = 14.dp)) {
            Text(
                "SEWADAR",
                Modifier.width(240.dp).padding(start = 18.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = InkMuted,
            )
            Row(Modifier.horizontalScroll(scroll)) {
                state.periodKeys.forEach { key ->
                    Text(
                        org.sewaentry.tablet.data.Reporting.periodLabel(key, period),
                        Modifier.width(130.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkMuted,
                    )
                }
                Text("TOTAL", Modifier.width(110.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Navy)
            }
        }

        LazyColumn {
            items(state.totals, key = { it.sewadarId }) { stat ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(240.dp).padding(start = 18.dp)) {
                        Text(stat.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(stat.departmentName ?: "--", fontSize = 12.sp, color = InkMuted)
                    }
                    Row(Modifier.horizontalScroll(scroll)) {
                        state.periodKeys.forEach { key ->
                            val hours = byKey[stat.sewadarId to key]?.hours ?: 0.0
                            Text(
                                if (hours > 0) String.format("%.1f", hours) else "-",
                                Modifier.width(130.dp),
                                fontSize = 16.sp,
                                color = if (hours > 0) Ink else Line,
                            )
                        }
                        Text(
                            String.format("%.1f", stat.hours),
                            Modifier.width(110.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Navy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (loading) "Working it out..." else "No sewa recorded in this range.",
            color = InkMuted,
            fontSize = 17.sp,
        )
    }
}
