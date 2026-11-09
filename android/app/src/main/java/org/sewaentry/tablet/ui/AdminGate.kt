package org.sewaentry.tablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
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
import org.sewaentry.tablet.data.PinAttemptResult
import kotlin.math.ceil

enum class AdminGateMode {
    ACCESS,
    CHANGE_PIN,
}

private enum class GateStage {
    VERIFY_CURRENT,
    CHOOSE_NEW,
    CONFIRM_NEW,
}

/**
 * PIN gate for the manual-entry and admin screens.
 *
 * Existing four-digit installations must authenticate with their old PIN
 * before choosing a new six-digit PIN. New installations set and confirm a
 * six-digit PIN before entering the admin area.
 */
@Composable
fun AdminGate(
    configured: Boolean,
    migrationRequired: Boolean,
    mode: AdminGateMode = AdminGateMode.ACCESS,
    onUnlock: (String) -> PinAttemptResult,
    onVerifyCurrentPin: (String) -> PinAttemptResult,
    onSetPin: (String) -> Boolean,
    onChangePin: (current: String, next: String) -> PinAttemptResult,
    onCancel: (() -> Unit)? = null,
) {
    val firstSetup = !configured
    var stage by remember(configured, migrationRequired, mode) {
        mutableStateOf(
            if (firstSetup) GateStage.CHOOSE_NEW else GateStage.VERIFY_CURRENT,
        )
    }
    var entry by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf<String?>(null) }
    var verifiedCurrentPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val oldPinLength = if (migrationRequired) AdminLock.LEGACY_LENGTH else AdminLock.LENGTH
    val entryLength = if (stage == GateStage.VERIFY_CURRENT) oldPinLength else AdminLock.LENGTH
    val title = when {
        stage == GateStage.VERIFY_CURRENT && migrationRequired -> "Update your admin PIN"
        stage == GateStage.VERIFY_CURRENT && mode == AdminGateMode.CHANGE_PIN -> "Enter current admin PIN"
        stage == GateStage.VERIFY_CURRENT -> "Enter admin PIN"
        stage == GateStage.CHOOSE_NEW -> "Choose a six-digit PIN"
        else -> "Confirm your new PIN"
    }
    val subtitle = when {
        stage == GateStage.VERIFY_CURRENT && migrationRequired ->
            "Enter your existing four-digit PIN to continue. You must choose a new six-digit PIN."
        stage == GateStage.VERIFY_CURRENT && mode == AdminGateMode.CHANGE_PIN ->
            "Verify your current PIN before changing it."
        stage == GateStage.VERIFY_CURRENT ->
            "The manual-entry and admin screens are locked. The register will open after verification."
        stage == GateStage.CHOOSE_NEW ->
            "Use six digits. This PIN protects manual attendance changes and admin settings."
        else -> "Enter the same six digits again to confirm."
    }

    fun reject(result: PinAttemptResult) {
        error = if (result.retryAfterMillis > 0) {
            val minutes = ceil(result.retryAfterMillis / 60_000.0).toInt()
            "Too many incorrect attempts. Try again in ${minutes.coerceAtLeast(1)} minute(s)."
        } else {
            "Incorrect PIN. Try again."
        }
        entry = ""
    }

    fun submit(pin: String) {
        when (stage) {
            GateStage.VERIFY_CURRENT -> {
                val result = if (migrationRequired || mode == AdminGateMode.CHANGE_PIN) {
                    onVerifyCurrentPin(pin)
                } else {
                    onUnlock(pin)
                }
                if (!result.accepted) {
                    reject(result)
                    return
                }

                if (migrationRequired || mode == AdminGateMode.CHANGE_PIN) {
                    verifiedCurrentPin = pin
                    stage = GateStage.CHOOSE_NEW
                    entry = ""
                    error = null
                } else {
                    error = null
                }
            }

            GateStage.CHOOSE_NEW -> {
                newPin = pin
                stage = GateStage.CONFIRM_NEW
                entry = ""
                error = null
            }

            GateStage.CONFIRM_NEW -> {
                if (pin != newPin) {
                    error = "Those PINs did not match. Choose a new PIN and try again."
                    newPin = null
                    stage = GateStage.CHOOSE_NEW
                    entry = ""
                    return
                }

                if (firstSetup) {
                    if (onSetPin(pin)) {
                        error = null
                    } else {
                        error = "The PIN could not be set. Enter six digits and try again."
                        newPin = null
                        stage = GateStage.CHOOSE_NEW
                        entry = ""
                    }
                } else {
                    val result = onChangePin(verifiedCurrentPin.orEmpty(), pin)
                    if (result.accepted) {
                        error = null
                    } else {
                        reject(result)
                        newPin = null
                        stage = GateStage.VERIFY_CURRENT
                        verifiedCurrentPin = null
                    }
                }
            }
        }
    }

    fun press(digit: String) {
        if (entry.length >= entryLength) return
        error = null
        val next = entry + digit
        entry = next
        if (next.length == entryLength) submit(next)
    }

    val compact = isCompactWidth()
    val keyWidth = if (compact) 76.dp else 96.dp
    val keyHeight = if (compact) 56.dp else 68.dp
    val keySpacing = if (compact) 8.dp else 12.dp

    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 460.dp).heightIn(max = 720.dp),
        ) {
            Column(
                Modifier
                    .padding(if (compact) 20.dp else 32.dp)
                    .verticalScroll(rememberScrollState()),
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

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(entryLength) { index ->
                        val filled = index < entry.length
                        Box(
                            Modifier
                                .size(18.dp)
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
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))

                listOf("123", "456", "789").forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        row.forEach { digit ->
                            Key(digit.toString(), keyWidth, keyHeight) { press(digit.toString()) }
                        }
                    }
                    Spacer(Modifier.height(keySpacing))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.width(keyWidth + keySpacing))
                    Key("0", keyWidth, keyHeight) { press("0") }
                    Spacer(Modifier.width(keySpacing))
                    Button(
                        onClick = { entry = entry.dropLast(1); error = null },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Navy),
                        modifier = Modifier.size(keyWidth, keyHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", modifier = Modifier.size(24.dp))
                    }
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Cancel", color = InkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = Ink),
        modifier = Modifier.size(width, height),
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.Unspecified)
    }
}
