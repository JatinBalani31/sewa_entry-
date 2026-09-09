package org.sewaentry.tablet.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val dayFilter by vm.dayFilter.collectAsState()
    val period by vm.period.collectAsState()
    val departments by vm.departments.collectAsState()
    val reportDept by vm.reportDepartmentId.collectAsState()
    val periodLabel by vm.periodLabel.collectAsState()

    val compact = isCompactWidth()

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {

        val title: @Composable () -> Unit = {
            Text(
                "Quarterly summary - ${quarter.label}",
                fontSize = if (compact) 22.sp else 26.sp,
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

        val exportButton: @Composable (Modifier) -> Unit = { mod ->
            Button(
                onClick = { vm.exportExcel() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = mod.height(if (compact) 52.dp else 62.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Export Excel",
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 16.sp else 18.sp,
                    maxLines = 1,
                )
            }
        }

        if (compact) {
            Column { title() }
            Spacer(Modifier.height(10.dp))
            exportButton(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { title() }
                exportButton(Modifier.width(230.dp))
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- Filters ---------------------------------------------------------
        // Four dropdowns rather than five rows of chips: the summary itself is
        // what the screen is for, so the controls give the table back its room.
        // On a phone four across leaves each about eighty points wide — too
        // narrow to read the selected value — so they go two by two.
        val periodFilter: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Period",
                value = periodLabel,
                modifier = mod,
                options = periodOptions(),
                isSelected = { it.label == periodLabel },
                onSelect = { option -> option.apply(vm) },
                render = { it.label },
            )
        }
        val dayFilterDropdown: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Sewa days",
                value = dayFilter.label,
                modifier = mod,
                options = DayFilter.entries.toList(),
                isSelected = { it == dayFilter },
                onSelect = vm::setDayFilter,
                render = { it.label },
            )
        }
        val groupByFilter: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Group by",
                value = period.label,
                modifier = mod,
                options = ReportPeriod.entries.toList(),
                isSelected = { it == period },
                onSelect = vm::setPeriod,
                render = { it.label },
            )
        }
        val departmentFilter: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Department",
                value = departments.firstOrNull { it.id == reportDept }?.name ?: "All departments",
                modifier = mod,
                options = listOf<Long?>(null) + departments.map { it.id },
                isSelected = { it == reportDept },
                onSelect = vm::setReportDepartment,
                render = { id -> departments.firstOrNull { it.id == id }?.name ?: "All departments" },
            )
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    periodFilter(Modifier.weight(1f))
                    dayFilterDropdown(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    groupByFilter(Modifier.weight(1f))
                    departmentFilter(Modifier.weight(1f))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                periodFilter(Modifier.weight(1.2f))
                dayFilterDropdown(Modifier.weight(1f))
                groupByFilter(Modifier.weight(1f))
                departmentFilter(Modifier.weight(1.3f))
            }
        }

        Spacer(Modifier.height(14.dp))

        val totalHours = state.totals.sumOf { it.hours }
        val attended = state.totals.count { it.daysAttended > 0 }
        val open = state.totals.sumOf { it.openDays }

        val sewaDaysCounted = state.rows.map { it.sewaDate }.distinct().size.toString()

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("Sewadars with hours", attended.toString(), Modifier.weight(1f))
                    StatTile("Total hours", String.format("%.1f", totalHours), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile("Sewa days counted", sewaDaysCounted, Modifier.weight(1f))
                    StatTile(
                        "Days without sign-out",
                        open.toString(),
                        Modifier.weight(1f),
                        accent = if (open > 0) Amber else null,
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Sewadars with hours", attended.toString(), Modifier.weight(1f))
                StatTile("Total hours", String.format("%.1f", totalHours), Modifier.weight(1f))
                StatTile("Sewa days counted", sewaDaysCounted, Modifier.weight(1f))
                StatTile(
                    "Days without sign-out",
                    open.toString(),
                    Modifier.weight(1f),
                    accent = if (open > 0) Amber else null,
                )
            }
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

/**
 * A labelled dropdown. Generic over the option type so the four filters share
 * one control and therefore one set of behaviours — a tablet-sized touch
 * target, the current value visible without opening it, and a tick against
 * whatever is selected.
 */
@Composable
private fun <T> FilterDropdown(
    label: String,
    value: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    render: (T) -> String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = InkMuted,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            Button(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
                border = BorderStroke(1.dp, Line),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val selected = isSelected(option)
                    DropdownMenuItem(
                        text = {
                            Text(
                                render(option),
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Navy else Ink,
                            )
                        },
                        trailingIcon = {
                            if (selected) {
                                Icon(Icons.Filled.Check, null, tint = Navy, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One choice of reporting period.
 *
 * Quarters and spans live in the same list because they answer the same
 * question — how far back does this summary reach — and splitting them across
 * two controls only made the admin decide which control to look in first.
 */
private class PeriodOption(val label: String, val apply: (RegisterViewModel) -> Unit)

/**
 * The current quarter and the seven before it, then the spans. Eight quarters
 * is two years of summaries, which is further back than anyone reprints.
 */
@Composable
private fun periodOptions(): List<PeriodOption> {
    val today = LocalDate.now()
    val current = Quarter.current()

    val quarters = (0 until 8).map { back ->
        val index = current.year * 4 + (current.quarter - 1) - back
        Quarter(index / 4, index % 4 + 1)
    }

    return quarters.map { q -> PeriodOption(q.label) { vm -> vm.selectQuarter(q) } } + listOf(
        PeriodOption("This month") { vm ->
            vm.selectRange("This month", today.withDayOfMonth(1), today)
        },
        PeriodOption("Last 6 months") { vm ->
            vm.selectRange("Last 6 months", today.minusMonths(6), today)
        },
        PeriodOption("Last 1 year") { vm ->
            vm.selectRange("Last 1 year", today.minusYears(1), today)
        },
        PeriodOption("All time") { vm ->
            vm.selectRange("All time", LocalDate.of(2000, 1, 1), today)
        },
    )
}
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val compact = isCompactWidth()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = modifier,
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 18.dp)) {
            Text(
                label.uppercase(),
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                color = InkMuted,
            )
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            Text(
                value,
                fontSize = if (compact) 24.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                color = accent ?: Ink,
            )
        }
    }
}

@Composable
private fun TotalsHeader() {
    // Seven columns need a tablet. On a phone each one gets about fifty
    // points and the headings wrap mid-word, so the table becomes a list:
    // who, and the hours, with the rest as a line of detail underneath.
    if (isCompactWidth()) {
        Row(Modifier.fillMaxWidth().background(SurfaceAlt).padding(horizontal = 16.dp, vertical = 12.dp)) {
            HeaderCell("Sewadar", Modifier.weight(1f))
            HeaderCell("Hours", Modifier)
        }
        return
    }

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
    if (isCompactWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceAlt)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${group.departmentName} total",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    "${group.activeCount} active - ${group.daysAttended} " +
                        if (group.daysAttended == 1) "day" else "days",
                    fontSize = 12.sp,
                    color = InkMuted,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                String.format("%.1f", group.hours),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
            )
        }
        return
    }

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
    if (isCompactWidth()) {
        Row(
            Modifier.fillMaxWidth().background(Navy).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("GRAND TOTAL", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "${groups.sumOf { it.activeCount }} sewadars - " +
                        "${groups.sumOf { it.daysAttended }} days",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                String.format("%.1f", groups.sumOf { it.hours }),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        return
    }

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

    if (isCompactWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stat.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(stat.departmentName ?: "--")
                        append(" - ${stat.daysAttended} ${if (stat.daysAttended == 1) "day" else "days"}")
                        if (stat.daysComplete != stat.daysAttended) append(", ${stat.daysComplete} complete")
                        if (average > 0) append(" - avg ${String.format("%.1f", average)}")
                        stat.lastAttended?.let { append(" - last $it") }
                    },
                    fontSize = 12.sp,
                    color = InkMuted,
                )
                if (stat.openDays > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.background(AmberBg, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("${stat.openDays} open", fontSize = 11.sp, color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                String.format("%.1f", stat.hours),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        return
    }

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
