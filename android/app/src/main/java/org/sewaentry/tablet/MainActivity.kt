package org.sewaentry.tablet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.sewaentry.tablet.ui.Background
import org.sewaentry.tablet.ui.Navy
import org.sewaentry.tablet.ui.RegisterScreen
import org.sewaentry.tablet.ui.AdminGate
import org.sewaentry.tablet.ui.RegisterViewModel
import org.sewaentry.tablet.ui.RosterScreen
import org.sewaentry.tablet.ui.SewaTheme
import org.sewaentry.tablet.ui.StatsScreen

/**
 * The whole app is one activity with three tabs. It opens straight into the
 * register with no sign-in: the tablet lives on the sewa counter under an
 * admin's eye, and a login screen between a queue of sewadars and the register
 * would only slow the line down.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SewaTheme {
                AppShell(onRestoreComplete = ::restart)
            }
        }
    }

    private fun restart() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

@Composable
private fun AppShell(vm: RegisterViewModel = viewModel(), onRestoreComplete: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    val unlocked by vm.unlocked.collectAsState()
    val message by vm.message.collectAsState()

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize()) {

            Row(
                Modifier.fillMaxWidth().background(Navy).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sewa Entry", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "TABLET REGISTER",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.weight(1f))

                // Locking again is one tap, so the tablet can go back on the
                // counter without waiting for the app to be closed.
                if (unlocked) {
                    TextButton(
                        onClick = {
                            vm.lockAdmin()
                            tab = 0
                        },
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Lock admin", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            TabRow(selectedTabIndex = tab, containerColor = Color.White, contentColor = Navy) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Register", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Summary", fontWeight = FontWeight.SemiBold) },
                    icon = {
                        Icon(
                            if (unlocked) Icons.Filled.InsertChart else Icons.Filled.Lock,
                            contentDescription = null,
                        )
                    },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("Roster", fontWeight = FontWeight.SemiBold) },
                    icon = {
                        Icon(
                            if (unlocked) Icons.Filled.Groups else Icons.Filled.Lock,
                            contentDescription = null,
                        )
                    },
                )
            }

            Box(Modifier.weight(1f)) {
                // On a fresh tablet (no PIN configured) the admin tabs are
                // open so the admin can import the roster and set things up.
                // Once a PIN is set, the gate appears on every subsequent visit.
                val needsGate = vm.pinConfigured && !unlocked

                when {
                    tab == 0 -> RegisterScreen(vm)
                    needsGate -> AdminGate(
                        configured = true,
                        onUnlock = vm::unlock,
                        onSetPin = vm::setPin,
                    )
                    tab == 1 -> StatsScreen(vm)
                    else -> RosterScreen(vm, onRestoreComplete = onRestoreComplete)
                }
            }
        }

        message?.let { text ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                action = {
                    TextButton(onClick = { vm.dismissMessage() }) {
                        Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
            ) { Text(text) }
        }
    }
}
