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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sewaentry.tablet.data.AdminLock

/**
 * The PIN screen in front of the summary and the roster.
 *
 * Its own keypad rather than a text field: the tablet sits on a counter and the
 * soft keyboard is slow, cramped and covers half the screen. Four large keys
 * across is quick with one hand.
 *
 * On a tablet with no PIN set, this asks for one to be chosen and confirmed —
 * so the protection exists from the first time anyone opens the admin side,
 * without a default PIN that everybody would end up knowing.
 */
@Composable
fun AdminGate(
    configured: Boolean,
    onUnlock: (String) -> Boolean,
    onSetPin: (String) -> Boolean,
) {
    var entry by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val settingUp = !configured

    val title = when {
        !settingUp -> "Enter admin PIN"
        confirming == null -> "Choose an admin PIN"
        else -> "Enter it again"
    }
    val subtitle = when {
        !settingUp -> "The summary and roster are for the admin. The register stays open to everyone."
        confirming == null -> "Four digits. It protects the summary and the roster on this tablet."
        else -> "Confirming, so a slip now does not lock the tablet later."
    }

    fun submit(pin: String) {
        when {
            !settingUp -> {
                if (onUnlock(pin)) error = null
                else { error = "Wrong PIN."; entry = "" }
            }
            confirming == null -> { confirming = pin; entry = ""; error = null }
            confirming != pin -> {
                error = "Those did not match. Start again."
                confirming = null
                entry = ""
            }
            else -> {
                if (!onSetPin(pin)) {
                    error = "That PIN could not be set."
                    confirming = null
                    entry = ""
                }
            }
        }
    }

    fun press(digit: String) {
        if (entry.length >= AdminLock.LENGTH) return
        error = null
        val next = entry + digit
        entry = next
        if (next.length == AdminLock.LENGTH) submit(next)
    }

    // A phone screen has far less width than the 10" tablet this was designed
    // for, so the card and its keypad shrink to fit rather than clipping.
    val compact = isCompactWidth()
    val keyWidth = if (compact) 76.dp else 96.dp
    val keyHeight = if (compact) 56.dp else 68.dp
    val keySpacing = if (compact) 8.dp else 12.dp

    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 460.dp),
        ) {
            Column(
                Modifier.padding(if (compact) 20.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Navy,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = InkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 340.dp),
                )

                Spacer(Modifier.height(22.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(AdminLock.LENGTH) { index ->
                        val filled = index < entry.length
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (filled) Navy else Line),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    error ?: " ",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DepartureRed,
                )
                Spacer(Modifier.height(10.dp))

                // 1-9, then blank / 0 / backspace.
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                        row.forEach { digit ->
                            Key(digit.toString(), keyWidth, keyHeight) { press(digit.toString()) }
                        }
                    }
                    Spacer(Modifier.height(keySpacing))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                    Spacer(Modifier.width(keyWidth))
                    Key("0", keyWidth, keyHeight) { press("0") }
                    Button(
                        onClick = { entry = entry.dropLast(1); error = null },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Navy),
                        modifier = Modifier.size(keyWidth, keyHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Ink),
        modifier = Modifier.size(width, height),
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.Unspecified)
    }
}
