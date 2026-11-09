package org.sewaentry.tablet.ui

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sewaentry.tablet.data.EncryptedFaceEmbedding
import org.sewaentry.tablet.data.FaceTemplateCrypto
import org.sewaentry.tablet.data.FaceTemplateMetadata
import org.sewaentry.tablet.data.SewaDatabase
import org.sewaentry.tablet.data.SewaRepository
import org.sewaentry.tablet.face.AttendanceDirection
import org.sewaentry.tablet.face.FaceAttendancePolicy
import org.sewaentry.tablet.face.ConsecutiveFaceMatchConfirmation
import org.sewaentry.tablet.face.FaceGalleryTemplate
import org.sewaentry.tablet.face.FaceMatch
import org.sewaentry.tablet.face.FaceRecognitionMatcher
import org.sewaentry.tablet.face.FaceScanMode
import org.sewaentry.tablet.face.HeadTurnLiveness
import org.sewaentry.tablet.face.HeadTurnProgress
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class FaceScanOverride(
    val direction: AttendanceDirection,
    val adminId: String,
    val note: String,
)

enum class FaceScanFeedback { SCANNING, ARRIVAL, DEPARTURE, ERROR }

data class FaceScanUiState(
    val loadingGallery: Boolean = true,
    val enrolledTemplateCount: Int = 0,
    val cameraStatus: String = "Loading encrypted face templates…",
    val liveness: HeadTurnProgress? = null,
    val resultName: String? = null,
    val resultMessage: String? = null,
    val resultTime: String? = null,
    val feedback: FaceScanFeedback = FaceScanFeedback.SCANNING,
    val processingResult: Boolean = false,
    val pendingMatch: FaceMatch? = null,
    val override: FaceScanOverride? = null,
    val galleryError: String? = null,
)

class FaceScanViewModel(app: Application) : AndroidViewModel(app) {
    private val database = SewaDatabase.get(app)
    private val repository = SewaRepository(database)
    private val matcher = FaceRecognitionMatcher()
    private val confirmation = ConsecutiveFaceMatchConfirmation()
    private val liveness = HeadTurnLiveness()
    private val isMatching = AtomicBoolean(false)
    private val scanGeneration = AtomicInteger()
    @Volatile
    private var gallery: List<FaceGalleryTemplate> = emptyList()
    private var galleryJob: Job? = null
    private var attendanceJob: Job? = null
    @Volatile
    private var requireEntryConfirmation = true
    @Volatile
    private var scanMode = FaceScanMode.LIVENESS_DETECTION

    private val _uiState = MutableStateFlow(FaceScanUiState())
    val uiState: StateFlow<FaceScanUiState> = _uiState.asStateFlow()

    fun refreshGallery() {
        galleryJob?.cancel()
        _uiState.update {
            it.copy(loadingGallery = true, galleryError = null, cameraStatus = "Loading encrypted face templates…")
        }
        galleryJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    database.faceTemplates().activeTemplates().map { active ->
                        val template = active.template
                        val metadata = FaceTemplateMetadata(
                            sewadarId = template.sewadarId,
                            sampleIndex = template.sampleIndex,
                            modelVersion = template.modelVersion,
                            consentVersion = template.consentVersion,
                            consentAt = template.consentAt,
                            enrolledAt = template.enrolledAt,
                        )
                        val decrypted = try {
                            FaceTemplateCrypto.decrypt(
                                EncryptedFaceEmbedding(
                                    ivBase64 = template.ivBase64,
                                    ciphertextBase64 = template.encryptedEmbeddingBase64,
                                ),
                                metadata,
                            )
                        } catch (error: GeneralSecurityException) {
                            throw IllegalStateException(
                                "Saved face data for ${active.sewadarName} cannot be decrypted; delete and re-enroll it.",
                                error,
                            )
                        } catch (error: IllegalArgumentException) {
                            throw IllegalStateException(
                                "Saved face data for ${active.sewadarName} is incompatible; delete and re-enroll it.",
                                error,
                            )
                        }
                        FaceGalleryTemplate(
                            sewadarId = template.sewadarId,
                            sewadarName = active.sewadarName,
                            departmentId = active.departmentId,
                            embedding = decrypted,
                        )
                    }
                }
                gallery = loaded
                _uiState.update {
                    it.copy(
                        loadingGallery = false,
                        enrolledTemplateCount = loaded.size,
                        cameraStatus = if (loaded.isEmpty()) {
                            "No active face enrollments. Ask an admin to enroll the roster first."
                        } else {
                            "Center your face, turn to either side, then face the camera again."
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GeneralSecurityException) {
                failGallery(error.message)
            } catch (error: IOException) {
                failGallery(error.message)
            } catch (error: ProviderException) {
                failGallery(error.message)
            } catch (error: SQLiteException) {
                failGallery(error.message)
            } catch (error: IllegalArgumentException) {
                failGallery(error.message)
            } catch (error: IllegalStateException) {
                failGallery(error.message)
            } catch (error: SecurityException) {
                failGallery(error.message)
            }
        }
    }

    fun setRequireEntryConfirmation(required: Boolean) {
        requireEntryConfirmation = required
        if (!required && _uiState.value.pendingMatch != null) {
            cancelDetectedEntry()
        }
    }

    fun setScanMode(mode: FaceScanMode) {
        if (scanMode == mode) return
        scanMode = mode
        scanGeneration.incrementAndGet()
        resetConfirmation()
        liveness.reset()
        _uiState.update {
            it.copy(
                resultName = null,
                resultMessage = null,
                resultTime = null,
                liveness = null,
                pendingMatch = null,
                processingResult = false,
                override = null,
                cameraStatus = if (mode == FaceScanMode.LIVENESS_DETECTION) {
                    "Center your face, turn to either side, then face the camera again."
                } else {
                    "Center your face and hold still for direct detection."
                },
            )
        }
    }

    fun confirmDetectedEntry() {
        val current = _uiState.value
        val match = current.pendingMatch ?: return
        val override = current.override
        _uiState.update { it.copy(pendingMatch = null) }
        recordMatch(
            match,
            override,
            livenessPassed = if (scanMode == FaceScanMode.LIVENESS_DETECTION) true else null,
            delayBeforeRecording = false,
        )
    }

    fun cancelDetectedEntry() {
        if (_uiState.value.pendingMatch == null) return
        scanGeneration.incrementAndGet()
        resetConfirmation()
        liveness.reset()
        _uiState.update {
            it.copy(
                resultName = null,
                resultMessage = null,
                resultTime = null,
                feedback = FaceScanFeedback.SCANNING,
                processingResult = false,
                pendingMatch = null,
                override = null,
                liveness = null,
                cameraStatus = "Detection cancelled. Center your face to scan again.",
            )
        }
    }

    fun onCameraFrame(frame: FaceCameraFrame) {
        if (_uiState.value.loadingGallery || gallery.isEmpty() || _uiState.value.processingResult) return
        val activeMode = scanMode
        val progress = if (activeMode == FaceScanMode.LIVENESS_DETECTION) {
            liveness.update(
                faceCount = frame.faceCount,
                yawDegrees = frame.yawDegrees,
                elapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            )
        } else {
            null
        }
        val livenessPassed = activeMode == FaceScanMode.DIRECT_DETECTION || progress?.passed == true
        _uiState.update { current ->
            current.copy(
                liveness = progress,
                feedback = if (livenessPassed) current.feedback else FaceScanFeedback.SCANNING,
                cameraStatus = when {
                    activeMode == FaceScanMode.DIRECT_DETECTION && frame.faceCount != 1 ->
                        "Center your face in the camera."
                    activeMode == FaceScanMode.DIRECT_DETECTION && !frame.qualityGood ->
                        "Face the camera directly and move a little closer."
                    activeMode == FaceScanMode.DIRECT_DETECTION ->
                        "Hold still while your face is recognized."
                    frame.faceCount == 1 && !frame.qualityGood && !livenessPassed ->
                        "Face the camera directly and move a little closer."
                    else -> progress?.instruction.orEmpty()
                },
            )
        }
        if (frame.faceCount != 1 || !livenessPassed || !frame.qualityGood || frame.embedding == null) {
            scanGeneration.incrementAndGet()
            confirmation.reset()
            return
        }
        if (!isMatching.compareAndSet(false, true)) return

        val probe = frame.embedding.copyOf()
        val generation = scanGeneration.get()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val match = matcher.findMatch(probe, gallery)
                withContext(Dispatchers.Main) {
                    if (scanGeneration.get() == generation) {
                        if (match == null) rejectMatch() else acceptMatch(match)
                    }
                }
            } catch (error: IllegalArgumentException) {
                withContext(Dispatchers.Main) {
                    if (scanGeneration.get() == generation) failMatch(error.message)
                }
            } catch (error: IllegalStateException) {
                withContext(Dispatchers.Main) {
                    if (scanGeneration.get() == generation) failMatch(error.message)
                }
            } finally {
                isMatching.set(false)
            }
        }
    }

    fun onCameraStatus(status: String) {
        _uiState.update { current ->
            if (current.processingResult) current else current.copy(
                cameraStatus = status,
                feedback = if (status.looksLikeFaceScanError()) FaceScanFeedback.ERROR else current.feedback,
            )
        }
    }

    fun armOverride(direction: AttendanceDirection, adminId: String, note: String) {
        if (adminId.isBlank() || note.isBlank()) return
        _uiState.update {
            it.copy(
                override = FaceScanOverride(direction, adminId.trim(), note.trim()),
                cameraStatus = "One-time direction override armed for the next recognized face.",
            )
        }
    }

    fun clearOverride() {
        _uiState.update { it.copy(override = null) }
    }

    fun stopScan() {
        galleryJob?.cancel()
        gallery = emptyList()
        scanGeneration.incrementAndGet()
        resetConfirmation()
        liveness.reset()
        val hadPendingMatch = _uiState.value.pendingMatch != null
        _uiState.update {
            it.copy(
                loadingGallery = true,
                enrolledTemplateCount = 0,
                resultName = if (hadPendingMatch) null else it.resultName,
                resultMessage = if (hadPendingMatch) null else it.resultMessage,
                processingResult = if (hadPendingMatch) false else it.processingResult,
                pendingMatch = null,
                override = null,
                liveness = null,
                cameraStatus = "Face scanner paused.",
            )
        }
    }

    private fun acceptMatch(match: FaceMatch?) {
        if (_uiState.value.processingResult) return
        val confirmedMatch = confirmation.accept(match) ?: return

        resetConfirmation()
        val override = _uiState.value.override
        if (requireEntryConfirmation) {
            _uiState.update {
                it.copy(
                    resultName = confirmedMatch.sewadarName,
                    resultMessage = "Confirm this person to record attendance, or cancel if this is not them.",
                    resultTime = null,
                    feedback = FaceScanFeedback.SCANNING,
                    processingResult = true,
                    pendingMatch = confirmedMatch,
                    cameraStatus = "Face detected. Confirm or cancel the attendance entry.",
                )
            }
            return
        }
        recordMatch(
            confirmedMatch,
            override,
            livenessPassed = if (scanMode == FaceScanMode.LIVENESS_DETECTION) true else null,
            delayBeforeRecording = true,
        )
    }

    private fun recordMatch(
        confirmedMatch: FaceMatch,
        override: FaceScanOverride?,
        livenessPassed: Boolean?,
        delayBeforeRecording: Boolean,
    ) {
        _uiState.update {
            it.copy(
                resultName = confirmedMatch.sewadarName,
                resultMessage = "Recording attendance…",
                resultTime = null,
                processingResult = true,
                pendingMatch = null,
                override = null,
                cameraStatus = "Please wait.",
            )
        }
        attendanceJob = viewModelScope.launch {
            try {
                if (delayBeforeRecording) delay(FaceAttendancePolicy.NAME_DISPLAY_MS)
                val recorded = withContext(Dispatchers.IO) {
                    repository.recordFaceAttendance(
                        sewadarId = confirmedMatch.sewadarId,
                        departmentId = confirmedMatch.departmentId,
                        date = java.time.LocalDate.now(),
                        at = System.currentTimeMillis(),
                        matchScore = confirmedMatch.score,
                        livenessPassed = livenessPassed,
                        forceDirection = override?.direction,
                        adminId = override?.adminId,
                        note = override?.note,
                    )
                }
                recorded.fold(
                    onSuccess = { entry ->
                        val direction = if (entry.direction == AttendanceDirection.IN) {
                            FaceScanFeedback.ARRIVAL
                        } else {
                            FaceScanFeedback.DEPARTURE
                        }
                        val time = java.time.Instant.ofEpochMilli(entry.deviceTime)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                        _uiState.update {
                            it.copy(
                                feedback = direction,
                                resultTime = time,
                                resultMessage = "${entry.direction.name} recorded at $time.",
                                cameraStatus = "${entry.direction.name} recorded. You may scan again.",
                                override = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                feedback = FaceScanFeedback.ERROR,
                                resultTime = null,
                                resultMessage = "No entry recorded: ${error.message ?: "attendance error"}",
                                cameraStatus = "No attendance entry was written.",
                                override = null,
                            )
                        }
                    },
                )
                delay(RESULT_DISPLAY_MS)
                liveness.reset()
                _uiState.update {
                    it.copy(
                        resultName = null,
                        resultMessage = null,
                        resultTime = null,
                        feedback = FaceScanFeedback.SCANNING,
                        processingResult = false,
                        liveness = null,
                        cameraStatus = "Center your face, turn to either side, then face the camera again.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                attendanceJob = null
            }
        }
    }

    private fun failGallery(reason: String?) {
        gallery = emptyList()
        _uiState.update {
            it.copy(
                loadingGallery = false,
                enrolledTemplateCount = 0,
                galleryError = "Could not load face templates: ${reason ?: "storage or decryption error"}",
                cameraStatus = "Face attendance is unavailable.",
            )
        }
    }

    private fun failMatch(reason: String?) {
        resetConfirmation()
        liveness.reset()
        _uiState.update {
            it.copy(
                feedback = FaceScanFeedback.ERROR,
                resultName = null,
                resultMessage = null,
                cameraStatus = "Face match could not be completed: ${reason ?: "invalid embedding"}",
            )
        }
    }

    private fun rejectMatch() {
        confirmation.reset()
        liveness.reset()
        _uiState.update {
            it.copy(
                feedback = FaceScanFeedback.ERROR,
                resultName = null,
                resultTime = null,
                resultMessage = "Face not recognized. Try again or ask an admin for help.",
                cameraStatus = "Face not recognized. Try again or ask an admin for help.",
            )
        }
    }

    private fun String.looksLikeFaceScanError(): Boolean {
        val message = lowercase()
        return listOf("failed", "could not", "unavailable", "denied", "permission", "invalid", "error")
            .any(message::contains)
    }

    private fun resetConfirmation() {
        scanGeneration.incrementAndGet()
        confirmation.reset()
    }

    private companion object {
        const val RESULT_DISPLAY_MS = 5_000L
    }
}
