package org.sewaentry.tablet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.sewaentry.tablet.face.FaceScanMode

@Composable
fun FaceScanScreen(
    requireEntryConfirmation: Boolean,
    scanMode: FaceScanMode,
    vm: FaceScanViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscapeLayout = configuration.screenWidthDp > configuration.screenHeightDp
    val sideBySideLayout = landscapeLayout && configuration.screenWidthDp >= 720
    val compactWidth = configuration.screenWidthDp < 600
    val canStartCamera = !state.loadingGallery &&
        state.enrolledTemplateCount > 0 && state.galleryError == null
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) vm.onCameraStatus("Camera permission was denied. Grant it to use face attendance.")
    }

    LaunchedEffect(Unit) { vm.refreshGallery() }
    LaunchedEffect(requireEntryConfirmation) {
        vm.setRequireEntryConfirmation(requireEntryConfirmation)
    }
    LaunchedEffect(scanMode) {
        vm.setScanMode(scanMode)
    }
    LaunchedEffect(canStartCamera, hasCameraPermission) {
        if (canStartCamera && !hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(vm) {
        onDispose { vm.stopScan() }
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Face Scan", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("On-device matching · no face photos saved", fontSize = 14.sp, color = InkMuted)
            }
        }
        if (state.loadingGallery) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Navy)
                    Spacer(Modifier.height(10.dp))
                    Text(state.cameraStatus, color = InkMuted)
                }
            }
        } else if (state.galleryError != null) {
            FaceStatusCard(state.galleryError, Modifier.weight(1f), error = true)
        } else if (state.enrolledTemplateCount == 0) {
            FaceStatusCard(state.cameraStatus, Modifier.weight(1f))
        } else if (hasCameraPermission) {
            val outlineColor = when (state.feedback) {
                FaceScanFeedback.SCANNING -> Color(0xFFB8C0CC)
                FaceScanFeedback.ARRIVAL -> ArrivalGreen
                FaceScanFeedback.DEPARTURE, FaceScanFeedback.ERROR -> DepartureRed
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val availableWidth = maxWidth
                val availableHeight = maxHeight
                val cameraSide = minOf(availableHeight, availableWidth * if (sideBySideLayout) 0.48f else 1f)
                if (sideBySideLayout) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(cameraSide).clip(RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            FaceCameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                onFrame = vm::onCameraFrame,
                                onStatus = vm::onCameraStatus,
                            )
                            Box(
                                Modifier.fillMaxWidth(0.72f)
                                    .fillMaxHeight(0.78f)
                                    .border(4.dp, outlineColor, CircleShape),
                            )
                        }
                        FaceResultsCard(
                            state = state,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(
                                    ((availableWidth - cameraSide) / 2f - 16.dp)
                                        .coerceIn(160.dp, 380.dp),
                                )
                                .heightIn(max = availableHeight),
                            landscapeLayout = landscapeLayout,
                            compactWidth = compactWidth,
                            onConfirm = vm::confirmDetectedEntry,
                            onCancel = vm::cancelDetectedEntry,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(cameraSide).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            FaceCameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                onFrame = vm::onCameraFrame,
                                onStatus = vm::onCameraStatus,
                            )
                            Box(
                                Modifier.fillMaxWidth(0.72f)
                                    .fillMaxHeight(0.78f)
                                    .border(4.dp, outlineColor, CircleShape),
                            )
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.fillMaxWidth(0.68f).weight(1f).border(4.dp, DepartureRed, CircleShape))
                    Spacer(Modifier.height(12.dp))
                    Text(state.cameraStatus, color = DepartureRed, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera access")
                    }
                }
            }
        }

        if (!sideBySideLayout) {
            FaceResultsCard(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compactWidth) 220.dp else 280.dp),
                landscapeLayout = landscapeLayout,
                compactWidth = compactWidth,
                onConfirm = vm::confirmDetectedEntry,
                onCancel = vm::cancelDetectedEntry,
            )
        }
    }
}
@Composable
private fun FaceResultsCard(
    state: FaceScanUiState,
    modifier: Modifier = Modifier,
    landscapeLayout: Boolean,
    compactWidth: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(if (compactWidth) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactWidth) 6.dp else 8.dp),
        ) {
            Text(
                state.resultName ?: "Face attendance",
                fontSize = if (compactWidth) 20.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                color = when (state.feedback) {
                    FaceScanFeedback.SCANNING -> Ink
                    FaceScanFeedback.ARRIVAL -> ArrivalGreen
                    FaceScanFeedback.DEPARTURE, FaceScanFeedback.ERROR -> DepartureRed
                },
            )
            Text(
                state.resultMessage ?: state.cameraStatus,
                color = if (state.feedback == FaceScanFeedback.ERROR) DepartureRed else InkMuted,
                fontSize = if (compactWidth) 14.sp else 18.sp,
            )
            state.resultTime?.let { time ->
                Text(
                    "${if (state.feedback == FaceScanFeedback.ARRIVAL) "Arrival" else "Departure"} · $time",
                    color = if (state.feedback == FaceScanFeedback.ARRIVAL) ArrivalGreen else DepartureRed,
                    fontSize = if (compactWidth) 18.sp else 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            state.liveness?.takeUnless { it.passed }?.let { progress ->
                Spacer(Modifier.height(5.dp))
                Text(
                    progress.instruction,
                    color = Navy,
                    fontSize = if (compactWidth) 14.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.processingResult) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Navy, strokeWidth = 2.dp)
            }
            if (state.pendingMatch != null) {
                Spacer(Modifier.height(8.dp))
                if (landscapeLayout) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth().height(if (compactWidth) 56.dp else 76.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ArrivalGreen),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Confirm entry", fontSize = if (compactWidth) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().height(if (compactWidth) 56.dp else 76.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DepartureRed),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Cancel detection", fontSize = if (compactWidth) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f).height(if (compactWidth) 56.dp else 76.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ArrivalGreen),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Confirm", fontSize = if (compactWidth) 15.sp else 18.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f).height(if (compactWidth) 56.dp else 76.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DepartureRed),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Cancel", fontSize = if (compactWidth) 15.sp else 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun FaceStatusCard(message: String?, modifier: Modifier, error: Boolean = false) {
    Card(
        modifier = modifier.fillMaxWidth().then(
            if (error) Modifier.border(2.dp, DepartureRed, RoundedCornerShape(14.dp)) else Modifier,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Text(
                message ?: "Face attendance is unavailable.",
                color = if (error) DepartureRed else InkMuted,
                fontWeight = if (error) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
