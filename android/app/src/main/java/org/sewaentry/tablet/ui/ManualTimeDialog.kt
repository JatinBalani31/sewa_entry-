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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewadarWithStatus
import java.time.LocalDate

/**
 * Manual time entry — the only path that sets a time by hand.
 *
 * Two two-digit boxes rather than a free-text field: on a tablet an admin is
 * working through a stack of people, so the hour box takes focus immediately,
 * jumps to minutes the moment two digits are in, and every value can also be
 * nudged with big +/- buttons without opening the keyboard at all.
 *
 * Anything saved here is flagged as edited, in the register and in the export.
 */
@Composable
fun ManualTimeDialog(
    sewadar: SewadarWithStatus,
    date: LocalDate,
    isToday: Boolean,
    onDismiss: () -> Unit,
    onSet: (arrival: Boolean, hour: Int, minute: Int) -> Unit,
    onClear: () -> Unit,
) {
    // Open on whichever end still needs filling in.
    var editingArrival by remember { mutableStateOf(sewadar.arrivalAt == null || sewadar.departureAt != null) }

    val existing = if (editingArrival) sewadar.arrivalAt else sewadar.departureAt

    // Sensible starting values so the common case is a couple of taps: the
    // recorded time if there is one, otherwise a typical sewa start/end.
    val default = remember(editingArrival, existing) {
        when {
            existing != null -> SewaDays.formatTime(existing).split(":").let { it[0].toInt() to it[1].toInt() }
            editingArrival -> 9 to 30
            else -> 16 to 30
        }
    }

    var hour by remember(editingArrival) { mutableStateOf(default.first) }
    var minute by remember(editingArrival) { mutableStateOf(default.second) }

    val arrivalMissing = !editingArrival && sewadar.arrivalAt == null
    val canSave = !arrivalMissing

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 620.dp).heightIn(max = 800.dp),
        ) {
            Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState())) {
                Text(sewadar.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "Manual entry for ${SewaDays.format(date)}" +
                        if (isToday) "" else " - a past day, so times must be entered by hand.",
                    fontSize = 15.sp,
                    color = InkMuted,
                )

                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth()) {
                    SegmentButton(
                        label = "Arrival",
                        detail = sewadar.arrivalAt?.let { SewaDays.formatTime(it) } ?: "not set",
                        selected = editingArrival,
                        accent = ArrivalGreen,
                        onClick = { editingArrival = true },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    SegmentButton(
                        label = "Departure",
                        detail = sewadar.departureAt?.let { SewaDays.formatTime(it) } ?: "not set",
                        selected = !editingArrival,
                        accent = DepartureRed,
                        onClick = { editingArrival = false },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(24.dp))

                TimeSpinner(
                    hour = hour,
                    minute = minute,
                    accent = if (editingArrival) ArrivalGreen else DepartureRed,
                    onHourChange = { hour = it },
                    onMinuteChange = { minute = it },
                )

                if (arrivalMissing) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Set the arrival first - a departure on its own cannot be counted.",
                        fontSize = 14.sp,
                        color = DepartureRed,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (sewadar.arrivalAt != null) {
                        TextButton(onClick = onClear) {
                            Text("Clear entry", color = DepartureRed, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.height(56.dp)) {
                        Text("Cancel", color = InkMuted, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { onSet(editingArrival, hour, minute) },
                        enabled = canSave,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                        modifier = Modifier.height(56.dp).width(180.dp),
                    ) {
                        Text("Save time", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * HH : MM, each a two-digit box with a stepper above and below.
 *
 * Typing two digits into the hour box moves focus to minutes automatically,
 * so a full time is four keystrokes with no reaching for a colon or a Next
 * button.
 */
@Composable
private fun TimeSpinner(
    hour: Int,
    minute: Int,
    accent: Color,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
) {
    val minuteFocus = remember { FocusRequester() }
    val hourFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { hourFocus.requestFocus() }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DigitBox(
            value = hour,
            max = 23,
            label = "HOUR",
            accent = accent,
            focusRequester = hourFocus,
            onValueChange = onHourChange,
            onFilled = { minuteFocus.requestFocus() },
        )

        Text(
            ":",
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            color = InkMuted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 40.dp),
        )

        DigitBox(
            value = minute,
            max = 59,
            label = "MINUTE",
            accent = accent,
            focusRequester = minuteFocus,
            onValueChange = onMinuteChange,
            onFilled = { },
        )
    }
}

@Composable
private fun DigitBox(
    value: Int,
    max: Int,
    label: String,
    accent: Color,
    focusRequester: FocusRequester,
    onValueChange: (Int) -> Unit,
    onFilled: () -> Unit,
) {
    val formatted = "%02d".format(value)

    // The box always shows two digits, so the first keystroke has to *replace*
    // what is there rather than append to it — otherwise typing 4 into a box
    // reading 30 gives 40. Selecting the whole text whenever the box takes
    // focus is what makes "type two digits" behave the way it looks.
    var field by remember { mutableStateOf(TextFieldValue(formatted, TextRange(0, 2))) }

    // True while a partial value is being typed, so the sync below does not
    // overwrite a half-entered number.
    var midEdit by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!midEdit && field.text != formatted) {
            field = TextFieldValue(formatted, TextRange(0, formatted.length))
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepButton(icon = true, accent = accent) {
            midEdit = false
            onValueChange(if (value >= max) 0 else value + 1)
        }

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .size(width = 150.dp, height = 118.dp)
                .background(SurfaceAlt, RoundedCornerShape(18.dp))
                .border(3.dp, accent, RoundedCornerShape(18.dp))
                // The whole box is the touch target, not just the glyphs — the
                // text itself is a fraction of this area on a tablet.
                .clickable { focusRequester.requestFocus() },
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = field,
                onValueChange = { input ->
                    val digits = input.text.filter(Char::isDigit)
                    when {
                        digits.isEmpty() -> {
                            midEdit = true
                            field = TextFieldValue("", TextRange(0))
                        }
                        digits.length == 1 -> {
                            midEdit = true
                            field = TextFieldValue(digits, TextRange(1))
                            // Clamp rather than reject, so a stray 9 in the hour
                            // box cannot leave the field unsavable.
                            onValueChange(digits.toInt().coerceIn(0, max))
                        }
                        else -> {
                            val two = digits.takeLast(2)
                            midEdit = false
                            field = TextFieldValue(two, TextRange(0, two.length))
                            onValueChange(two.toInt().coerceIn(0, max))
                            onFilled()
                        }
                    }
                },
                textStyle = TextStyle(
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        // Arriving in the box selects what is there, so the
                        // next digit typed replaces it.
                        if (state.isFocused) {
                            midEdit = false
                            field = field.copy(selection = TextRange(0, field.text.length))
                        }
                    },
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = InkMuted)
        Spacer(Modifier.height(6.dp))

        StepButton(icon = false, accent = accent) {
            midEdit = false
            onValueChange(if (value <= 0) max else value - 1)
        }
    }
}

@Composable
private fun StepButton(icon: Boolean, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.12f), contentColor = accent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(width = 150.dp, height = 60.dp),
    ) {
        Icon(
            if (icon) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (icon) "Increase" else "Decrease",
            modifier = Modifier.size(38.dp),
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    detail: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent else SurfaceAlt,
            contentColor = if (selected) Color.White else InkMuted,
        ),
        modifier = modifier.height(72.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(detail, fontSize = 12.sp)
        }
    }
}
