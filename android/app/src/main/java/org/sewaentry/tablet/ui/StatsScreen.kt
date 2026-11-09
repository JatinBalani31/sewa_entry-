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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.Quarter
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
    val dayFilter by vm.dayFilter.collectAsState()
    val departments by vm.departments.collectAsState()
    val reportDept by vm.reportDepartmentId.collectAsState()
    val reportSewadar by vm.reportSewadarId.collectAsState()
    val periodLabel by vm.periodLabel.collectAsState()

    val departmentName = departments.firstOrNull { it.id == reportDept }?.name ?: "No department"
    // Everyone on the department roster, whether or not they did any sewa in
    // the period - somebody who did none is exactly who a report is checked for.
    val roster = state.totals.sortedBy { it.name }
    val sewadarName = roster.firstOrNull { it.sewadarId == reportSewadar }?.name

    val compact = isCompactWidth()

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {

        val title: @Composable () -> Unit = {
            Text(
                sewadarName ?: departmentName,
                fontSize = if (compact) 22.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                buildString {
                    if (sewadarName != null) append("$departmentName - ")
                    append(periodLabel)
                    val days = state.sewaDates.size
                    append(" - $days sewa ${if (days == 1) "day" else "days"}")
                    if (dayFilter != DayFilter.ALL) append(" - ${dayFilter.label}")
                },
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
        val departmentFilter: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Department",
                value = departmentName,
                modifier = mod,
                options = departments.map { it.id },
                isSelected = { it == reportDept },
                onSelect = vm::setReportDepartment,
                render = { id -> departments.firstOrNull { it.id == id }?.name ?: "--" },
            )
        }
        val sewadarFilter: @Composable (Modifier) -> Unit = { mod ->
            FilterDropdown(
                label = "Sewadar",
                value = sewadarName ?: EVERYONE,
                modifier = mod,
                options = listOf<Long?>(null) + roster.map { it.sewadarId },
                isSelected = { it == reportSewadar },
                onSelect = vm::setReportSewadar,
                render = { id ->
                    if (id == null) EVERYONE
                    else roster.firstOrNull { it.sewadarId == id }?.name ?: "--"
                },
            )
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    periodFilter(Modifier.weight(1f))
                    dayFilterDropdown(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    departmentFilter(Modifier.weight(1f))
                    sewadarFilter(Modifier.weight(1f))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                periodFilter(Modifier.weight(1.1f))
                dayFilterDropdown(Modifier.weight(1f))
                departmentFilter(Modifier.weight(1.3f))
                sewadarFilter(Modifier.weight(1.3f))
            }
        }

        Spacer(Modifier.height(14.dp))

        // Every figure here is about the chosen scope and nothing wider: this
        // department, or this one person. A total across the whole sangat is
        // not a number anybody reports on.
        val subject = state.totals.firstOrNull().takeIf { reportSewadar != null }
        val totalHours = state.totals.sumOf { it.hours }
        val open = state.totals.sumOf { it.openDays }
        val daysCounted = state.rows.map { it.sewaDate }.distinct().size

        val tiles: List<Triple<String, String, Color?>> = if (subject != null) {
            val average = if (subject.daysComplete > 0) subject.hours / subject.daysComplete else 0.0
            listOf(
                Triple("Days attended", subject.daysAttended.toString(), null),
                Triple("Total hours", String.format("%.1f", subject.hours), null),
                Triple("Average per day", String.format("%.1f", average), null),
                Triple(
                    "Days without sign-out",
                    subject.openDays.toString(),
                    if (subject.openDays > 0) Amber else null,
                ),
            )
        } else {
            listOf(
                Triple("Sewadars with hours", state.totals.count { it.daysAttended > 0 }.toString(), null),
                Triple("Total hours", String.format("%.1f", totalHours), null),
                Triple("Sewa days counted", daysCounted.toString(), null),
                Triple("Days without sign-out", open.toString(), if (open > 0) Amber else null),
            )
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { (label, value, accent) ->
                            StatTile(label, value, Modifier.weight(1f), accent = accent)
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                tiles.forEach { (label, value, accent) ->
                    StatTile(label, value, Modifier.weight(1f), accent = accent)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            when {
                // One person: the days that make up their total, listed, so the
                // number can be checked rather than taken on trust.
                subject != null -> Column {
                    SewadarDaysHeader()
                    if (state.rows.isEmpty()) {
                        EmptyState(state.loading)
                    } else {
                        state.rows.sortedBy { it.sewaDate }.forEach { row ->
                            SewadarDayRow(row)
                        }
                        SubjectTotal(subject.daysAttended, subject.hours)
                    }
                }

                else -> Column {
                    TotalsHeader()
                    if (state.totals.isEmpty()) {
                        EmptyState(state.loading)
                    } else {
                        state.totals.sortedBy { it.name }.forEach { stat ->
                            TotalsRow(stat)
                        }
                        SubjectTotal(
                            state.totals.sumOf { it.daysAttended },
                            totalHours,
                            label = "$departmentName total",
                        )
                    }
                }
            }
        }
    }
}

private const val EVERYONE = "Whole department"
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
 * Concise reporting periods: monthly, quarterly, last year, etc. (8 options max).
 */
@Composable
private fun periodOptions(): List<PeriodOption> {
    val today = LocalDate.now()
    val current = Quarter.current()
    val prevQuarterIndex = current.year * 4 + (current.quarter - 1) - 1
    val prevQuarter = Quarter(prevQuarterIndex / 4, prevQuarterIndex % 4 + 1)
    val prev2QuarterIndex = current.year * 4 + (current.quarter - 1) - 2
    val prev2Quarter = Quarter(prev2QuarterIndex / 4, prev2QuarterIndex % 4 + 1)
    val firstOfThisMonth = today.withDayOfMonth(1)
    val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
    val lastOfLastMonth = firstOfThisMonth.minusDays(1)

    return listOf(
        PeriodOption("This month") { vm ->
            vm.selectRange("This month", firstOfThisMonth, today)
        },
        PeriodOption("Last month") { vm ->
            vm.selectRange("Last month", firstOfLastMonth, lastOfLastMonth)
        },
        PeriodOption(current.label) { vm ->
            vm.selectQuarter(current)
        },
        PeriodOption(prevQuarter.label) { vm ->
            vm.selectQuarter(prevQuarter)
        },
        PeriodOption(prev2Quarter.label) { vm ->
            vm.selectQuarter(prev2Quarter)
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

/** The header over one sewadar's day-by-day list. */
@Composable
private fun SewadarDaysHeader() {
    val compact = isCompactWidth()
    Row(
        Modifier.fillMaxWidth().background(SurfaceAlt).padding(
            horizontal = if (compact) 16.dp else 18.dp,
            vertical = if (compact) 12.dp else 14.dp,
        ),
    ) {
        HeaderCell("Sewa day", Modifier.weight(if (compact) 1.6f else 1.4f))
        HeaderCell("Arrival", Modifier.weight(1f))
        HeaderCell("Departure", Modifier.weight(1f))
        HeaderCell("Hours", Modifier.weight(0.8f))
    }
}

/** One day of one sewadar's sewa — the row a total can be checked against. */
@Composable
private fun SewadarDayRow(row: ExportRow) {
    val compact = isCompactWidth()
    val minutes = SewaDays.minutesBetween(row.arrivalAt, row.departureAt)
    val edited = row.arrivalEdited || row.departureEdited

    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = if (compact) 16.dp else 18.dp,
            vertical = if (compact) 12.dp else 14.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(if (compact) 1.6f else 1.4f)) {
            Text(row.sewaDate, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                SewaDays.parse(row.sewaDate).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } +
                    if (edited) " - by hand" else "",
                fontSize = 12.sp,
                color = InkMuted,
            )
        }
        val arrivalDisplay = SewaDays.formatTime(row.arrivalAt) + if (row.arrivalEdited) "*" else ""
        val departureDisplay = if (row.departureAt != null) {
            SewaDays.formatTime(row.departureAt) + if (row.departureEdited) "*" else ""
        } else "--"
        Text(arrivalDisplay, Modifier.weight(1f), fontSize = 15.sp, color = Ink)
        Text(
            departureDisplay,
            Modifier.weight(1f),
            fontSize = 15.sp,
            color = if (row.departureAt != null) Ink else Amber,
        )
        Text(
            if (minutes != null) String.format("%.1f", minutes / 60.0) else "open",
            Modifier.weight(0.8f),
            fontSize = if (compact) 15.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (minutes != null) Ink else Amber,
        )
    }
}

/**
 * The one total the report is for — this person's hours, or this department's.
 * There is deliberately nothing wider than that: a figure summed across every
 * department answers no question the sangat asks.
 */
@Composable
private fun SubjectTotal(days: Int, hours: Double, label: String = "Total") {
    val compact = isCompactWidth()
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(
            horizontal = if (compact) 16.dp else 18.dp,
            vertical = if (compact) 14.dp else 16.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = if (compact) 15.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "$days ${if (days == 1) "day" else "days"} attended",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            String.format("%.1f", hours),
            fontSize = if (compact) 19.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
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


@Composable
private fun EmptyState(loading: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "Working it out..." else "No sewa recorded in this range.",
            color = InkMuted,
            fontSize = 17.sp,
        )
    }
}
