package org.sewaentry.tablet.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarStats
import java.time.LocalDate

/**
 * Per-sewadar analytics over a chosen window, and the Excel export.
 *
 * "Days attended" counts any day with an arrival; "complete" counts only days
 * that were also signed out. Hours only accrue from complete days, so a
 * forgotten sign-out shows up as a gap rather than quietly inflating a total.
 */
@Composable
fun StatsScreen(vm: RegisterViewModel) {
    val stats by vm.stats.collectAsState()
    val from by vm.statsFrom.collectAsState()
    val to by vm.statsTo.collectAsState()

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sewadar analytics", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "${SewaDays.format(from)} to ${SewaDays.format(to)}",
                    fontSize = 13.sp,
                    color = InkMuted,
                )
            }

            RangeChip("1 month") { vm.setStatsRange(LocalDate.now().minusMonths(1), LocalDate.now()) }
            Spacer(Modifier.width(8.dp))
            RangeChip("3 months") { vm.setStatsRange(LocalDate.now().minusMonths(3), LocalDate.now()) }
            Spacer(Modifier.width(8.dp))
            RangeChip("1 year") { vm.setStatsRange(LocalDate.now().minusYears(1), LocalDate.now()) }

            Spacer(Modifier.width(14.dp))

            Button(
                onClick = { vm.exportExcel() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                modifier = Modifier.height(50.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export Excel", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(14.dp))

        val attended = stats.count { it.daysAttended > 0 }
        val totalHours = stats.sumOf { it.totalMinutes } / 60.0
        val open = stats.sumOf { it.daysAttended - it.daysComplete }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Sewadars active", attended.toString(), Modifier.weight(1f))
            StatTile("Total hours", String.format("%.1f", totalHours), Modifier.weight(1f))
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
            Column {
                HeaderRow()
                if (stats.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No sewadars yet.", color = InkMuted)
                    }
                } else {
                    LazyColumn {
                        items(stats, key = { it.sewadarId }) { StatRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Navy),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkMuted)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent ?: Ink)
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(
        Modifier.fillMaxWidth().background(SurfaceAlt).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeaderCell("Sewadar", Modifier.weight(2.2f))
        HeaderCell("Department", Modifier.weight(1.4f))
        HeaderCell("Days", Modifier.weight(0.8f))
        HeaderCell("Complete", Modifier.weight(1f))
        HeaderCell("Total hours", Modifier.weight(1f))
        HeaderCell("Avg", Modifier.weight(0.8f))
        HeaderCell("Last sewa", Modifier.weight(1.2f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text.uppercase(),
        modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = InkMuted,
    )
}

@Composable
private fun StatRow(stat: SewadarStats) {
    val hours = stat.totalMinutes / 60.0
    val average = if (stat.daysComplete > 0) hours / stat.daysComplete else 0.0
    val incomplete = stat.daysAttended - stat.daysComplete

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stat.name, Modifier.weight(2.2f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(stat.departmentName ?: "--", Modifier.weight(1.4f), fontSize = 14.sp, color = InkMuted)
        Text(stat.daysAttended.toString(), Modifier.weight(0.8f), fontSize = 14.sp, color = Ink)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stat.daysComplete.toString(), fontSize = 14.sp, color = Ink)
            if (incomplete > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .background(AmberBg, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("$incomplete open", fontSize = 10.sp, color = Amber, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(String.format("%.1f", hours), Modifier.weight(1f), fontSize = 14.sp, color = Ink)
        Text(String.format("%.1f", average), Modifier.weight(0.8f), fontSize = 14.sp, color = InkMuted)
        Text(stat.lastAttendedDate ?: "--", Modifier.weight(1.2f), fontSize = 13.sp, color = InkMuted)
    }
}
