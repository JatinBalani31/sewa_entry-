package org.sewaentry.tablet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.sewaentry.tablet.data.SewadarEntity

@Composable
fun FaceEnrollmentScreen(vm: FaceEnrollmentViewModel = viewModel()) {
    DisposableEffect(vm) {
        onDispose { vm.cancelEnrollment() }
    }

    val state by vm.uiState.collectAsState()
    val sewadars by vm.sewadars.collectAsState()
    val departments by vm.departments.collectAsState()
    val templateCounts by vm.templateCounts.collectAsState()
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var deleteRequest by remember { mutableStateOf<SewadarEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDepartmentId by remember { mutableStateOf<Long?>(null) }
    var departmentFilterExpanded by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) vm.reportCameraStatus("Camera permission was denied. Grant it to enroll a face.")
    }

    LaunchedEffect(state.session?.sewadar?.id, hasCameraPermission) {
        if (state.session != null && !hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    deleteRequest?.let { sewadar ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text("Delete face data?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This permanently deletes the encrypted face templates stored for ${sewadar.name}. Attendance records are not changed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteRequest = null
                        vm.deleteFaceData(sewadar)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DepartureRed),
                ) { Text("Delete face data") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {
        val activeSewadars = sewadars.filter { it.isActive }
        val enrolledCount = activeSewadars.count { (templateCounts[it.id] ?: 0) > 0 }
        val departmentNames = departments.associate { it.id to it.name }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Face enrollment", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "$enrolledCount / ${activeSewadars.size} sewadars enrolled",
                    fontSize = 14.sp,
                    color = InkMuted,
                )
            }
            Box {
                IconButton(onClick = { departmentFilterExpanded = true }) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Filter by department", tint = Ink)
                }
                DropdownMenu(
                    expanded = departmentFilterExpanded,
                    onDismissRequest = { departmentFilterExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All departments") },
                        onClick = {
                            selectedDepartmentId = null
                            departmentFilterExpanded = false
                        },
                    )
                    departments.forEach { department ->
                        DropdownMenuItem(
                            text = { Text(department.name) },
                            onClick = {
                                selectedDepartmentId = department.id
                                departmentFilterExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f), color = Ink)
                TextButton(onClick = vm::clearMessage) { Text("Dismiss") }
            }
        }

        val session = state.session
        if (session != null) {
            EnrollmentCapture(
                session = session,
                state = state,
                hasCameraPermission = hasCameraPermission,
                onEmbedding = vm::receiveEmbedding,
                onStatus = vm::reportCameraStatus,
                onRequestPermission = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onCapture = vm::captureSample,
                onCancel = vm::cancelEnrollment,
            )
        } else {
            if (activeSewadars.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No active sewadars are available for face enrollment.", color = InkMuted)
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Find a Sewadar") },
                )
                val visibleSewadars = activeSewadars.filter { sewadar ->
                    sewadar.name.contains(searchQuery.trim(), ignoreCase = true) &&
                        (selectedDepartmentId == null || sewadar.departmentId == selectedDepartmentId)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleSewadars, key = { it.id }) { sewadar ->
                        val count = templateCounts[sewadar.id] ?: 0
                        EnrollmentRosterCard(
                            sewadar = sewadar,
                            departmentName = sewadar.departmentId?.let(departmentNames::get),
                            templateCount = count,
                            onEnroll = { vm.requestConsent(sewadar) },
                            onDelete = { deleteRequest = sewadar },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnrollmentRosterCard(
    sewadar: SewadarEntity,
    departmentName: String?,
    templateCount: Int,
    onEnroll: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(sewadar.name, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
                val memberDetails = buildList {
                    if (sewadar.phone != null) add(sewadar.phone)
                    if (departmentName != null) add(departmentName)
                    if (sewadar.organization != null) add(sewadar.organization)
                }
                if (memberDetails.isNotEmpty()) {
                    Text(memberDetails.joinToString(" · "), color = InkMuted, fontSize = 13.sp)
                }
                Text(
                    when {
                        !sewadar.isActive && templateCount > 0 -> "Inactive · $templateCount encrypted sample(s)"
                        !sewadar.isActive -> "Inactive sewadar"
                        templateCount == 0 -> "No face data enrolled"
                        else -> "$templateCount encrypted sample(s) stored"
                    },
                    color = InkMuted,
                    fontSize = 13.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEnroll,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (templateCount == 0) "Enroll face" else "Re-enroll face")
                }
                if (templateCount > 0) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Delete face data", color = DepartureRed) }
                }
            }
        }
    }
}

@Composable
private fun EnrollmentCapture(
    session: FaceEnrollmentSession,
    state: FaceEnrollmentUiState,
    hasCameraPermission: Boolean,
    onEmbedding: (FloatArray) -> Unit,
    onStatus: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    if (landscape) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(0.82f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EnrollmentInstructions(session, state)
                Spacer(Modifier.height(8.dp))
                EnrollmentActions(state, session.sampleCount, hasCameraPermission, onCapture, onCancel)
                Text("No camera image is saved. Enrollment ends automatically after the selected samples are encrypted.", color = InkMuted, fontSize = 13.sp)
            }
            Box(Modifier.weight(1.18f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (hasCameraPermission) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FacePreview(Modifier.size(minOf(maxWidth, maxHeight)), state, onEmbedding, onStatus)
                    }
                } else CameraPermissionCard(onRequestPermission)
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EnrollmentInstructions(session, state)
            if (hasCameraPermission) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    FacePreview(Modifier.size(minOf(maxWidth, maxHeight)), state, onEmbedding, onStatus)
                }
            } else CameraPermissionCard(onRequestPermission, Modifier.fillMaxWidth().weight(1f))
            EnrollmentActions(state, session.sampleCount, hasCameraPermission, onCapture, onCancel)
            Text("No camera image is saved. Enrollment ends automatically after the selected samples are encrypted.", color = InkMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EnrollmentInstructions(session: FaceEnrollmentSession, state: FaceEnrollmentUiState) {
    Text("Enroll face", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text("${session.sewadar.name} · sample ${state.capturedSamples + 1} of ${session.sampleCount}", color = InkMuted)
    Text("Center the face in the guide. Turn slightly between samples.", color = InkMuted)
}

@Composable
private fun FacePreview(modifier: Modifier, state: FaceEnrollmentUiState, onEmbedding: (FloatArray) -> Unit, onStatus: (String) -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        FaceCameraPreview(modifier = Modifier.fillMaxSize(), onFrame = { frame ->
            when {
                frame.faceCount == 0 -> onStatus("Position one face in the camera view.")
                frame.faceCount > 1 -> onStatus("Only one face may be in view.")
                !frame.qualityGood -> onStatus("Face the camera directly and move a little closer.")
                frame.embedding != null -> onEmbedding(frame.embedding)
            }
        }, onStatus = onStatus)
        Box(Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.78f).border(3.dp, if (state.faceReady) ArrivalGreen else Color(0xFFB8C0CC), CircleShape))
    }
}

@Composable
private fun CameraPermissionCard(onRequestPermission: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Camera permission is needed to enroll a face.", color = InkMuted)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermission) { Text("Grant camera access") }
        }
    }
}

@Composable
private fun EnrollmentActions(state: FaceEnrollmentUiState, sampleCount: Int, hasCameraPermission: Boolean, onCapture: () -> Unit, onCancel: () -> Unit) {
    LinearProgressIndicator(progress = { state.capturedSamples.toFloat() / sampleCount.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    Text(state.status, color = if (state.faceReady) ArrivalGreen else InkMuted, fontWeight = if (state.faceReady) FontWeight.SemiBold else FontWeight.Normal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onCapture, enabled = hasCameraPermission && state.faceReady && !state.saving, colors = ButtonDefaults.buttonColors(containerColor = Navy), modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (state.saving) "Saving…" else "Capture sample ${state.capturedSamples + 1}")
        }
        OutlinedButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel enrollment") }
    }
}
