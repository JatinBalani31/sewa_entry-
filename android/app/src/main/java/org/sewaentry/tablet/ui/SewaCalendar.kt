package org.sewaentry.tablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.sewaentry.tablet.data.SewaDays
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy")
private val FULL_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

/**
 * The date control at the top of the register: shows the selected day, and
 * opens a month calendar where every Wednesday and Sunday is ringed, so the
 * sewa days are findable at a glance.
 */
@Composable
fun SewaDateBar(
    selected: LocalDate,
    recordedDates: Set<String>,
    onSelect: (LocalDate) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val isSewaDay = SewaDays.isSewaDay(selected)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSelect(selected.minusDays(1)) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day", tint = Navy)
            }

            Column(
                Modifier.weight(1f).clickable { picking = true },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    selected.format(FULL_DATE),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isSewaDay) "Sewa day - tap to change" else "Not a scheduled sewa day - tap to change",
                    fontSize = 13.sp,
                    color = if (isSewaDay) ArrivalGreen else Amber,
                )
            }

            IconButton(
                onClick = { onSelect(selected.plusDays(1)) },
                // A register cannot be filled in for a day that has not happened.
                enabled = selected.isBefore(LocalDate.now()),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next day",
                    tint = if (selected.isBefore(LocalDate.now())) Navy else Line,
                )
            }
        }
    }

    if (picking) {
        MonthPickerDialog(
            selected = selected,
            recordedDates = recordedDates,
            onDismiss = { picking = false },
            onSelect = {
                onSelect(it)
                picking = false
            },
        )
    }
}

@Composable
private fun MonthPickerDialog(
    selected: LocalDate,
    recordedDates: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val today = LocalDate.now()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 420.dp).heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.ChevronLeft, "Previous month", tint = Navy)
                    }
                    Text(
                        month.format(MONTH_TITLE),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Filled.ChevronRight, "Next month", tint = Navy)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                        Text(
                            label,
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = InkMuted,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Sunday-first grid: shift so the 1st lands in the right column.
                val first = month.atDay(1)
                val leading = first.dayOfWeek.value % 7
                val days = month.lengthOfMonth()
                val cells = leading + days
                val weeks = (cells + 6) / 7

                for (week in 0 until weeks) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val dayNumber = week * 7 + dow - leading + 1
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (dayNumber in 1..days) {
                                    val date = month.atDay(dayNumber)
                                    DayCell(
                                        date = date,
                                        isSelected = date == selected,
                                        isFuture = date.isAfter(today),
                                        hasRecords = SewaDays.format(date) in recordedDates,
                                        onClick = { onSelect(date) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).border(2.dp, Navy, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("Wednesdays and Sundays", fontSize = 12.sp, color = InkMuted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSelect(SewaDays.currentOrPrevious()) }) {
                        Text("Latest sewa day")
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isFuture: Boolean,
    hasRecords: Boolean,
    onClick: () -> Unit,
) {
    val isSewa = SewaDays.isSewaDay(date)

    val background = when {
        isSelected -> Navy
        isSewa && !isFuture -> ArrivalGreenBg
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> Color.White
        isFuture -> Line
        isSewa -> ArrivalGreen
        else -> Ink
    }

    Box(
        Modifier
            .size(40.dp)
            .background(background, CircleShape)
            // The ring is what makes a sewa day findable while scanning.
            .then(if (isSewa && !isSelected) Modifier.border(2.dp, Navy, CircleShape) else Modifier)
            .clickable(enabled = !isFuture, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 15.sp,
                fontWeight = if (isSewa) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            )
            if (hasRecords) {
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .size(4.dp)
                        .background(if (isSelected) Color.White else Amber, CircleShape),
                )
            }
        }
    }
}
