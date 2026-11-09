package org.sewaentry.tablet

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sewaentry.tablet.data.BackupFrequency
import org.sewaentry.tablet.data.GoogleDriveBackupScheduler
import org.sewaentry.tablet.data.GoogleDriveBackupSettings
import org.sewaentry.tablet.face.FaceScanMode
import org.sewaentry.tablet.ui.AdminGate
import org.sewaentry.tablet.ui.AdminGateMode
import org.sewaentry.tablet.ui.Background
import org.sewaentry.tablet.ui.FaceEnrollmentScreen
import org.sewaentry.tablet.ui.FaceScanScreen
import org.sewaentry.tablet.ui.Navy
import org.sewaentry.tablet.ui.RegisterScreen
import org.sewaentry.tablet.ui.RegisterViewModel
import org.sewaentry.tablet.ui.RosterScreen
import org.sewaentry.tablet.ui.SettingsScreen
import org.sewaentry.tablet.ui.StatsScreen
import org.sewaentry.tablet.ui.SewaTheme
import androidx.lifecycle.viewmodel.compose.viewModel

/** The tablet register, with public attendance tabs and admin screens in the menu. */
class MainActivity : ComponentActivity() {
    private val _userInteractionSequence = MutableStateFlow(0L)
    val userInteractionSequence: StateFlow<Long> = _userInteractionSequence.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SewaTheme {
                AppShell(
                    onRestoreComplete = ::restart,
                    onStartPinning = ::startPinning,
                    onStopPinning = ::stopPinning,
                    userInteractionSequence = userInteractionSequence,
                )
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        _userInteractionSequence.value = SystemClock.elapsedRealtime()
    }

    private fun startPinning() {
        val manager = getSystemService(ActivityManager::class.java)
        if (manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
    }

    private fun stopPinning() {
        val manager = getSystemService(ActivityManager::class.java)
        if (manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
    }

    private fun restart() {
        val intent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("Could not restart Sewa Entry after restoring the database."))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}

private enum class AdminPage { FACE_SCAN, MANUAL, STATS, ROSTER, ENROLLMENT, SETTINGS }
// Google admin authentication is disabled for now. Keep ADMIN_LOGIN and ADD_ADMIN
// logic below commented until the OAuth setup is ready to be reintroduced.
private enum class GoogleSignInPurpose { BACKUP_ACCOUNT }

@Composable
private fun AppShell(
    vm: RegisterViewModel = viewModel(),
    onRestoreComplete: () -> Unit = {},
    onStartPinning: () -> Unit = {},
    onStopPinning: () -> Unit = {},
    userInteractionSequence: StateFlow<Long>,
) {
    val context = LocalContext.current
    val compactWidth = LocalConfiguration.current.screenWidthDp < 600
    val appContext = context.applicationContext
    val departments by vm.departments.collectAsState()
    val driveSettings = remember { GoogleDriveBackupSettings(appContext) }
    val localPinPrefs = remember { appContext.getSharedPreferences(APP_SETTINGS_PREFS, Activity.MODE_PRIVATE) }
    val googleConfigured = BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()

    var page by remember { mutableStateOf(AdminPage.FACE_SCAN) }
    var pageBeforeRoster by remember { mutableStateOf(AdminPage.FACE_SCAN) }
    var pageBeforeAnalytics by remember { mutableStateOf(AdminPage.SETTINGS) }
    var changingPin by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var persistentRosterMessage by remember { mutableStateOf<String?>(null) }
    var signInPurpose by remember { mutableStateOf(GoogleSignInPurpose.BACKUP_ACCOUNT) }
    var backupFrequency by remember { mutableStateOf(driveSettings.frequency) }
    var backupAccountEmail by remember { mutableStateOf(driveSettings.accountEmail) }
    var lastBackupAt by remember { mutableStateOf(driveSettings.lastBackupAt) }
    var backupError by remember { mutableStateOf(driveSettings.lastError) }
    var backupStatus by remember { mutableStateOf(driveSettings.statusMessage) }
    var pinOnFaceScan by remember {
        mutableStateOf(localPinPrefs.getBoolean(KEY_PIN_ON_FACE_SCAN, false))
    }
    var requireFaceEntryConfirmation by remember {
        mutableStateOf(localPinPrefs.getBoolean(KEY_FACE_ENTRY_CONFIRMATION, false))
    }
    var faceScanMode by remember {
        val savedMode = localPinPrefs.getString(KEY_FACE_SCAN_MODE, FaceScanMode.LIVENESS_DETECTION.name)
        mutableStateOf(FaceScanMode.entries.firstOrNull { it.name == savedMode } ?: FaceScanMode.LIVENESS_DETECTION)
    }
    var pinRequestAttempted by remember { mutableStateOf(false) }

    val googleClient = remember(context, googleConfigured) {
        if (!googleConfigured) null else {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                .requestScopes(Scope(DRIVE_FILE_SCOPE))
                .build()
            GoogleSignIn.getClient(context, options)
        }
    }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            localMessage = "Google sign-in was cancelled or did not complete."
        } else {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val email = account.email?.trim()
                if (email.isNullOrBlank() || account.idToken.isNullOrBlank()) {
                    localMessage = "Google did not return a verified account. Check OAuth configuration and try again."
                } else {
                    when (signInPurpose) {
                        GoogleSignInPurpose.BACKUP_ACCOUNT -> {
                            if (driveSettings.accountEmail != email) {
                                driveSettings.driveFileId = null
                            }
                            driveSettings.accountEmail = email
                            backupAccountEmail = email
                            backupError = null
                            backupStatus = "Google backup account connected."
                            GoogleDriveBackupScheduler.setFrequency(appContext, backupFrequency)
                            if (backupFrequency != BackupFrequency.OFF) {
                                GoogleDriveBackupScheduler.backupNow(appContext)
                                backupStatus = "Backup queued; it will upload when internet is available."
                                localMessage = "Google account connected. A backup will run when internet is available."
                            } else {
                                localMessage = "Google Drive backup account connected."
                            }
                        }
                        /*
                        GoogleSignInPurpose.ADD_ADMIN -> {
                            if (activeAdminEmail == null && !vm.unlocked.value) {
                                localMessage = "Unlock an authorized admin account before adding another administrator."
                            } else if (accountStore.add(email)) {
                                adminEmails = accountStore.all()
                                activeAdminEmail = email
                                vm.markAdminActivity()
                                localMessage = "$email is now an authorized admin."
                            } else {
                                localMessage = "That account is already authorized, or the account could not be saved."
                            }
                        }
                        GoogleSignInPurpose.ADMIN_LOGIN -> {
                            if (adminEmails.isEmpty()) {
                                if (accountStore.add(email)) {
                                    adminEmails = accountStore.all()
                                    activeAdminEmail = email
                                    vm.markAdminActivity()
                                    localMessage = "First admin account set up. Add a six-digit PIN in Settings for offline recovery."
                                } else {
                                    localMessage = "Could not save the first admin account."
                                }
                            } else if (accountStore.contains(email)) {
                                activeAdminEmail = email
                                vm.markAdminActivity()
                                localMessage = "Signed in as $email."
                            } else if (activeAdminEmail != null || vm.unlocked.value) {
                                if (accountStore.add(email)) {
                                    adminEmails = accountStore.all()
                                    activeAdminEmail = email
                                    vm.markAdminActivity()
                                    localMessage = "$email is now an authorized admin."
                                } else {
                                    localMessage = "Could not save this admin account."
                                }
                            } else {
                                localMessage = "This Google account is not an admin on this tablet."
                            }
                        }
                        */
                    }
                }
            } catch (error: ApiException) {
                localMessage = "Google sign-in failed (status ${error.statusCode}). Check the OAuth client configuration."
            }
        }
    }

    fun launchGoogleSignIn(purpose: GoogleSignInPurpose) {
        val client = googleClient
        if (client == null) {
            localMessage = "Google sign-in is not configured. Set GOOGLE_SERVER_CLIENT_ID in Gradle properties."
        } else {
            if (purpose == GoogleSignInPurpose.BACKUP_ACCOUNT) {
                signInPurpose = purpose
                googleSignInLauncher.launch(client.signInIntent)
            }
        }
    }

    val unlocked by vm.unlocked.collectAsState()
    val userInteraction by userInteractionSequence.collectAsState()
    val message by vm.message.collectAsState()
    // Admin access currently uses the local tablet PIN only. The Google admin
    // account code above is retained for a later release, but has no UI entry point.
    val adminAccess = unlocked
    val visibleMessage = localMessage ?: message
    val messageIsPersistent = page == AdminPage.SETTINGS ||
        page == AdminPage.ROSTER ||
        visibleMessage == persistentRosterMessage

    LaunchedEffect(visibleMessage, messageIsPersistent) {
        if (visibleMessage != null && !messageIsPersistent) {
            delay(5_000)
            if (localMessage == visibleMessage) {
                localMessage = null
            } else if (localMessage == null && vm.message.value == visibleMessage) {
                vm.dismissMessage()
            }
        }
    }

    LaunchedEffect(page) {
        if (page == AdminPage.SETTINGS) {
            while (true) {
                delay(3_000)
                backupAccountEmail = driveSettings.accountEmail
                lastBackupAt = driveSettings.lastBackupAt
                backupError = driveSettings.lastError
                backupStatus = driveSettings.statusMessage
            }
        }
    }

    LaunchedEffect(adminAccess, userInteraction) {
        if (adminAccess) {
            vm.markAdminActivity()
            delay(ADMIN_IDLE_TIMEOUT_MS)
            if (System.currentTimeMillis() - vm.lastAdminActivity.value >= ADMIN_IDLE_TIMEOUT_MS) {
                vm.lockAdmin()
                changingPin = false
            }
        }
    }

    LaunchedEffect(page, pinOnFaceScan) {
        if (page == AdminPage.FACE_SCAN && pinOnFaceScan && !pinRequestAttempted) {
            pinRequestAttempted = true
            onStartPinning()
        } else if (page != AdminPage.FACE_SCAN) {
            pinRequestAttempted = false
        }
    }

    BackHandler(enabled = changingPin || page != AdminPage.FACE_SCAN) {
        if (changingPin) {
            changingPin = false
        } else if (page == AdminPage.STATS) {
            page = pageBeforeAnalytics
        } else if (page == AdminPage.ROSTER) {
            page = pageBeforeRoster
        } else {
            page = AdminPage.FACE_SCAN
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Navy)
                    .padding(horizontal = if (compactWidth) 10.dp else 16.dp, vertical = if (compactWidth) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Sewa Entry", color = Color.White, fontSize = if (compactWidth) 18.sp else 20.sp, fontWeight = FontWeight.Bold)
                    if (!compactWidth) {
                        Text(
                            "TABLET REGISTER",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (adminAccess) {
                    TextButton(
                        onClick = {
                            vm.lockAdmin()
                            page = AdminPage.FACE_SCAN
                            changingPin = false
                        },
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (compactWidth) "Lock" else "Lock admin", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Open menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        listOf(
                            AdminPage.ENROLLMENT to "Face enrollment",
                            AdminPage.SETTINGS to "Settings",
                        ).forEach { (destination, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    page = destination
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = when (page) {
                    AdminPage.MANUAL -> 1
                    else -> 0
                },
                containerColor = Color.White,
                contentColor = Navy,
            ) {
                Tab(
                    selected = page == AdminPage.FACE_SCAN,
                    onClick = { page = AdminPage.FACE_SCAN },
                    text = { Text("Face Scan", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Filled.Face, contentDescription = null) },
                )
                Tab(
                    selected = page == AdminPage.MANUAL,
                    onClick = { page = AdminPage.MANUAL },
                    text = { Text("Manual Entry", fontWeight = FontWeight.SemiBold) },
                    icon = {
                        Icon(
                            if (adminAccess) Icons.Filled.EditNote else Icons.Filled.Lock,
                            contentDescription = null,
                        )
                    },
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    changingPin -> AdminGate(
                        configured = vm.pinConfigured,
                        migrationRequired = vm.pinMigrationRequired,
                        mode = AdminGateMode.CHANGE_PIN,
                        onUnlock = vm::unlock,
                        onVerifyCurrentPin = vm::verifyCurrentPin,
                        onSetPin = vm::setPin,
                        onChangePin = { current, next ->
                            vm.changePin(current, next).also {
                                if (it.accepted) changingPin = false
                            }
                        },
                        onCancel = { changingPin = false },
                    )
                    page == AdminPage.FACE_SCAN -> FaceScanScreen(
                        requireEntryConfirmation = requireFaceEntryConfirmation,
                        scanMode = faceScanMode,
                    )
                    !vm.pinConfigured && !adminAccess -> AdminGate(
                        configured = false,
                        migrationRequired = false,
                        onUnlock = vm::unlock,
                        onVerifyCurrentPin = vm::verifyCurrentPin,
                        onSetPin = vm::setPin,
                        onChangePin = vm::changePin,
                    )
                    !adminAccess -> AdminGate(
                        configured = vm.pinConfigured,
                        migrationRequired = vm.pinMigrationRequired,
                        onUnlock = vm::unlock,
                        onVerifyCurrentPin = vm::verifyCurrentPin,
                        onSetPin = vm::setPin,
                        onChangePin = vm::changePin,
                    )
                    page == AdminPage.MANUAL -> RegisterScreen(vm)
                    page == AdminPage.STATS -> StatsScreen(vm)
                    page == AdminPage.ROSTER -> RosterScreen(
                        vm,
                        onRestoreComplete = onRestoreComplete,
                        onImportComplete = { summary ->
                            localMessage = summary
                            persistentRosterMessage = summary
                            page = pageBeforeRoster
                        },
                    )
                    page == AdminPage.ENROLLMENT -> FaceEnrollmentScreen()
                    else -> SettingsScreen(
                        backupFrequency = backupFrequency,
                        backupAccountEmail = backupAccountEmail,
                        lastBackupAt = lastBackupAt,
                        backupError = backupError,
                        backupStatus = backupStatus,
                        pinOnFaceScan = pinOnFaceScan,
                        requireFaceEntryConfirmation = requireFaceEntryConfirmation,
                        faceScanMode = faceScanMode,
                        googleConfigured = googleConfigured,
                        onSignInBackupAccount = { launchGoogleSignIn(GoogleSignInPurpose.BACKUP_ACCOUNT) },
                        onFrequencyChange = { frequency ->
                            driveSettings.frequency = frequency
                            backupFrequency = frequency
                            if (backupAccountEmail != null) {
                                GoogleDriveBackupScheduler.setFrequency(appContext, frequency)
                            } else {
                                GoogleDriveBackupScheduler.setFrequency(appContext, BackupFrequency.OFF)
                            }
                            if (frequency == BackupFrequency.OFF) {
                                backupStatus = "Automatic Google Drive backup is off."
                            } else if (backupAccountEmail != null) {
                                GoogleDriveBackupScheduler.backupNow(appContext)
                                backupStatus = "Backup queued; it will upload when internet is available."
                                localMessage = "Backup schedule saved. The first backup will run when internet is available."
                            } else {
                                backupStatus = "Connect the dedicated Google account to start scheduled backups."
                            }
                        },
                        onBackupNow = {
                            GoogleDriveBackupScheduler.backupNow(appContext)
                            driveSettings.statusMessage = "Backup queued; it will upload when internet is available."
                            backupStatus = driveSettings.statusMessage
                            localMessage = "Backup queued. It will run when internet is available."
                        },
                        onPinChange = { enabled ->
                            pinOnFaceScan = enabled
                            check(localPinPrefs.edit().putBoolean(KEY_PIN_ON_FACE_SCAN, enabled).commit()) {
                                "Could not save the app-pinning setting."
                            }
                            if (!enabled) onStopPinning()
                        },
                        onFaceEntryConfirmationChange = { enabled ->
                            requireFaceEntryConfirmation = enabled
                            check(
                                localPinPrefs.edit()
                                    .putBoolean(KEY_FACE_ENTRY_CONFIRMATION, enabled)
                                    .commit(),
                            ) { "Could not save the face attendance confirmation setting." }
                        },
                        onFaceScanModeChange = { mode ->
                            check(localPinPrefs.edit().putString(KEY_FACE_SCAN_MODE, mode.name).commit()) {
                                "Could not save the face scan mode."
                            }
                            faceScanMode = mode
                        },
                        onChangeAdminPin = { changingPin = true },
                        onOpenRoster = {
                            pageBeforeRoster = AdminPage.SETTINGS
                            page = AdminPage.ROSTER
                        },
                        departments = departments,
                        onRenameDepartment = vm::renameDepartment,
                        onOpenAnalytics = {
                            pageBeforeAnalytics = page
                            page = AdminPage.STATS
                        },
                    )
                }
            }
        }

        visibleMessage?.let { text ->
            val isFailure = text.contains("fail", ignoreCase = true) ||
                text.contains("error", ignoreCase = true) ||
                text.contains("could not", ignoreCase = true) ||
                text.contains("denied", ignoreCase = true)
            val messageColor = if (isFailure) Color(0xFF9B1C1C) else Navy
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.5.dp, messageColor.copy(alpha = 0.6f)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        color = messageColor,
                        fontWeight = FontWeight.Medium,
                    )
                    IconButton(
                        onClick = {
                            localMessage = null
                            persistentRosterMessage = null
                            vm.dismissMessage()
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss message",
                            tint = messageColor,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val ADMIN_IDLE_TIMEOUT_MS = 60_000L
private const val APP_SETTINGS_PREFS = "sewa-app-settings"
private const val KEY_PIN_ON_FACE_SCAN = "pin_on_face_scan"
private const val KEY_FACE_ENTRY_CONFIRMATION = "face_entry_confirmation"
private const val KEY_FACE_SCAN_MODE = "face_scan_mode"
private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
