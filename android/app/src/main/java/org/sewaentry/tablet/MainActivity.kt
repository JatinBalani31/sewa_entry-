package org.sewaentry.tablet

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.InsertChart
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
import org.sewaentry.tablet.ui.RegisterViewModel
import org.sewaentry.tablet.ui.SewaTheme
import org.sewaentry.tablet.ui.StatsScreen

/**
 * The whole app is one activity with two tabs. It opens straight into the
 * register with no sign-in: the tablet lives on the sewa counter under an
 * admin's eye, and a login screen between a queue of sewadars and the register
 * would only slow the line down.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SewaTheme {
                AppShell()
            }
        }
    }
}

@Composable
private fun AppShell(vm: RegisterViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
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
                    text = { Text("Analytics", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.InsertChart, contentDescription = null) },
                )
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> RegisterScreen(vm)
                    else -> StatsScreen(vm)
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
