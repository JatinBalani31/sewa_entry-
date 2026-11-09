package org.sewaentry.tablet.ui

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sewaentry.tablet.data.FaceTemplateCrypto
import org.sewaentry.tablet.data.FaceTemplateEntity
import org.sewaentry.tablet.data.FaceTemplateMetadata
import org.sewaentry.tablet.data.FaceTemplateTransfer
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.SewaDatabase
import org.sewaentry.tablet.data.SewadarEntity
import org.sewaentry.tablet.data.EncryptedFaceEmbedding
import org.json.JSONArray
import org.json.JSONObject
import org.sewaentry.tablet.face.FaceEnrollmentPolicy
import org.sewaentry.tablet.face.FaceModelContract
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicReference

data class FaceEnrollmentSession(
    val sewadar: SewadarEntity,
    val sampleCount: Int,
    val consentAt: Long,
)

data class FaceBackupExport(
    val contents: String,
    val memberCount: Int,
    val sampleCount: Int,
)

data class FaceEnrollmentUiState(
    val session: FaceEnrollmentSession? = null,
    val capturedSamples: Int = 0,
    val faceReady: Boolean = false,
    val saving: Boolean = false,
    val status: String = "Face data is stored on this tablet only.",
    val message: String? = null,
    val transferMessage: String? = null,
)

class FaceEnrollmentViewModel(app: Application) : AndroidViewModel(app) {
    private val database = SewaDatabase.get(app)
    private val templateDao = database.faceTemplates()
    private val latestEmbedding = AtomicReference<FloatArray?>()
    private val samples = mutableListOf<FloatArray>()
    private var enrollmentJob: Job? = null

    suspend fun createFaceBackup(password: CharArray): FaceBackupExport = withContext(Dispatchers.IO) {
        val entries = JSONArray()
        val departments = database.departments().all().associateBy { it.id }
        val sewadars = database.sewadars().all().associateBy { it.id }
        val templates = database.faceTemplates().allTemplatesForTransfer()
        templates.groupBy { it.template.sewadarId }.values.forEach { personTemplates ->
            val first = personTemplates.first()
            val dept = first.departmentId?.let { departments[it]?.name }
            val sewadar = sewadars[first.template.sewadarId]
            val samples = JSONArray()
            personTemplates.forEach { active ->
                val t = active.template
                val metadata = FaceTemplateMetadata(t.sewadarId, t.sampleIndex, t.modelVersion, t.consentVersion, t.consentAt, t.enrolledAt)
                val embedding = FaceTemplateCrypto.decrypt(EncryptedFaceEmbedding(t.ivBase64, t.encryptedEmbeddingBase64), metadata)
                samples.put(JSONObject().put("model", t.modelVersion).put("consentVersion", t.consentVersion)
                    .put("consentAt", t.consentAt).put("enrolledAt", t.enrolledAt)
                    .put("embedding", JSONArray().apply { embedding.forEach { value -> put(value.toDouble()) } }))
            }
            entries.put(
                JSONObject()
                    .put("name", first.sewadarName)
                    .put("sewadarId", first.template.sewadarId)
                    .put("department", dept)
                    .put("phone", sewadar?.phone)
                    .put("phone2", sewadar?.phone2)
                    .put("samples", samples),
            )
        }
        val sampleCount = (0 until entries.length()).sumOf { entries.getJSONObject(it).getJSONArray("samples").length() }
        val payload = JSONObject()
            .put("format", "sewa-face-transfer-payload-v2")
            .put("memberCount", entries.length())
            .put("sampleCount", sampleCount)
            .put("entries", entries)
            .toString()
        FaceBackupExport(
            contents = FaceTemplateTransfer.seal(payload, password),
            memberCount = entries.length(),
            sampleCount = sampleCount,
        )
    }

    suspend fun importFaceBackup(container: String, password: CharArray): String = withContext(Dispatchers.IO) {
        val containerJson = JSONObject(container)
        val payload = if (containerJson.optString("format") == "sewa-face-transfer-v1") {
            FaceTemplateTransfer.open(container, password)
        } else {
            container
        }
        val root = JSONObject(payload)
        val format = root.optString("format")
        require(format == "sewa-face-transfer-payload-v2" || format == "sewa-face-transfer-plain-v1") {
            "Unsupported face backup format."
        }
        val entries = root.getJSONArray("entries")
        var sourceSamples = 0
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            require(entry.getString("name").isNotBlank()) { "Face backup contains a member without a name." }
            val samples = entry.getJSONArray("samples")
            require(samples.length() > 0) { "Face backup contains an empty entry." }
            sourceSamples += samples.length()
            for (j in 0 until samples.length()) {
                val sample = samples.getJSONObject(j)
                require(sample.getString("model") == FaceModelContract.MODEL_VERSION) { "Unsupported face model." }
                require(sample.getString("consentVersion").isNotBlank() && sample.getLong("consentAt") > 0 && sample.getLong("enrolledAt") > 0) { "Incomplete face consent metadata." }
                val embedding = sample.getJSONArray("embedding")
                require(embedding.length() == FaceModelContract.EMBEDDING_SIZE) { "Invalid face template." }
                for (k in 0 until embedding.length()) require(embedding.getDouble(k).isFinite()) { "Invalid face template." }
            }
        }
        if (format == "sewa-face-transfer-payload-v2") {
            require(root.getInt("memberCount") == entries.length()) { "Face backup member count does not match its data." }
            require(root.getInt("sampleCount") == sourceSamples) { "Face backup sample count does not match its data." }
        }

        var importedSamples = 0
        var importedMembers = 0
        var unmatched = 0
        var ambiguous = 0
        var skippedAsOlder = 0
        database.withTransaction {
            val roster = database.sewadars().all()
            val departments = database.departments().all().associateBy { it.name.lowercase() }
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val deptName = entry.optString("department").takeIf { it.isNotBlank() && it != "null" }
                val department = deptName?.let { departments[it.lowercase()] }
                if (deptName != null && department == null) {
                    unmatched++
                    continue
                }
                val membersInDepartment = roster.filter { it.departmentId == department?.id }
                val nameMatches = membersInDepartment.filter {
                    it.name.equals(entry.getString("name"), ignoreCase = true)
                }
                val importedPhones = setOfNotNull(
                    entry.optString("phone").takeIf { it.isNotBlank() && it != "null" },
                    entry.optString("phone2").takeIf { it.isNotBlank() && it != "null" },
                ).map(::phoneDigits).filter { it.isNotEmpty() }.toSet()
                val phoneMatches = if (importedPhones.isEmpty()) {
                    emptyList()
                } else {
                    membersInDepartment.filter { candidate ->
                        setOfNotNull(candidate.phone, candidate.phone2)
                            .map(::phoneDigits)
                            .any { it in importedPhones }
                    }
                }
                val sewadar = when {
                    nameMatches.size == 1 -> nameMatches.single()
                    nameMatches.size > 1 -> phoneMatches.singleOrNull()
                    else -> phoneMatches.singleOrNull()
                }
                if (sewadar == null) {
                    if (nameMatches.size > 1 || phoneMatches.size > 1) ambiguous++ else unmatched++
                    continue
                }
                val existing = database.faceTemplates().forSewadar(sewadar.id)
                val samples = entry.getJSONArray("samples")
                val importedAt = (0 until samples.length()).maxOf { samples.getJSONObject(it).getLong("enrolledAt") }
                if (existing.isNotEmpty() && importedAt < existing.maxOf { it.enrolledAt }) {
                    skippedAsOlder++
                    continue
                }
                templateDao.deleteForSewadar(sewadar.id)
                var sampleIndex = 0
                val rows = mutableListOf<FaceTemplateEntity>()
                for (j in 0 until samples.length()) {
                    val sample = samples.getJSONObject(j)
                    val values = sample.getJSONArray("embedding")
                    val embedding = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                    val metadata = FaceTemplateMetadata(sewadar.id, sampleIndex++, sample.getString("model"), sample.getString("consentVersion"), sample.getLong("consentAt"), sample.getLong("enrolledAt"))
                    val encrypted = FaceTemplateCrypto.encrypt(embedding, metadata)
                    rows += FaceTemplateEntity(sewadarId = sewadar.id, sampleIndex = metadata.sampleIndex, modelVersion = metadata.modelVersion,
                        ivBase64 = encrypted.ivBase64, encryptedEmbeddingBase64 = encrypted.ciphertextBase64,
                        consentVersion = metadata.consentVersion, consentAt = metadata.consentAt, enrolledAt = metadata.enrolledAt)
                }
                if (rows.isNotEmpty()) {
                    database.faceTemplates().insertAll(rows)
                    val storedRows = database.faceTemplates().forSewadar(sewadar.id)
                    check(storedRows.size == rows.size) {
                        "Imported face template count could not be verified for ${sewadar.name}."
                    }
                    storedRows.forEachIndexed { index, stored ->
                        val metadata = FaceTemplateMetadata(
                            sewadarId = stored.sewadarId,
                            sampleIndex = stored.sampleIndex,
                            modelVersion = stored.modelVersion,
                            consentVersion = stored.consentVersion,
                            consentAt = stored.consentAt,
                            enrolledAt = stored.enrolledAt,
                        )
                        val decrypted = FaceTemplateCrypto.decrypt(
                            EncryptedFaceEmbedding(stored.ivBase64, stored.encryptedEmbeddingBase64),
                            metadata,
                        )
                        val source = samples.getJSONObject(index).getJSONArray("embedding")
                        val expected = FloatArray(source.length()) { source.getDouble(it).toFloat() }
                        check(decrypted.contentEquals(expected)) {
                            "Imported face template verification failed for ${sewadar.name}."
                        }
                    }
                    importedSamples += rows.size
                    importedMembers++
                }
            }
        }
        "Imported and verified $importedSamples face template(s) for $importedMembers member(s). " +
            "Preserved newer local data for $skippedAsOlder member(s); $unmatched unmatched and $ambiguous ambiguous."
    }

    private fun phoneDigits(value: String): String = value.filter(Char::isDigit)

    private val _uiState = MutableStateFlow(FaceEnrollmentUiState())
    val uiState: StateFlow<FaceEnrollmentUiState> = _uiState.asStateFlow()

    val sewadars: StateFlow<List<SewadarEntity>> =
        database.sewadars().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val departments: StateFlow<List<DepartmentEntity>> =
        database.departments().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val templateCounts: StateFlow<Map<Long, Int>> =
        templateDao.observeCounts()
            .map { counts -> counts.associate { it.sewadarId to it.templateCount } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun requestConsent(sewadar: SewadarEntity) {
        latestEmbedding.set(null)
        samples.clear()
        _uiState.value = FaceEnrollmentUiState(
            session = FaceEnrollmentSession(
                sewadar = sewadar,
                sampleCount = FaceEnrollmentPolicy.DEFAULT_SAMPLES,
                consentAt = System.currentTimeMillis(),
            ),
            status = "Center one face in view, then capture the first of ${FaceEnrollmentPolicy.DEFAULT_SAMPLES} samples.",
        )
    }

    fun receiveEmbedding(embedding: FloatArray) {
        if (_uiState.value.session == null || _uiState.value.saving) return
        latestEmbedding.set(embedding.copyOf())
        _uiState.value = _uiState.value.copy(
            faceReady = true,
            status = "Face aligned. Capture this sample.",
        )
    }

    fun reportCameraStatus(status: String) {
        latestEmbedding.set(null)
        if (_uiState.value.session != null && !_uiState.value.saving) {
            _uiState.value = _uiState.value.copy(faceReady = false, status = status)
        }
    }

    fun captureSample() {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.saving) return
        val embedding = latestEmbedding.getAndSet(null)
        if (embedding == null) {
            _uiState.value = state.copy(
                faceReady = false,
                status = "No suitable face is visible. Center one face and try again.",
            )
            return
        }

        samples += embedding
        val captured = samples.size
        if (captured < session.sampleCount) {
            _uiState.value = state.copy(
                capturedSamples = captured,
                faceReady = false,
                status = "Captured $captured of ${session.sampleCount}. Slightly change angle before the next sample.",
            )
            return
        }

        val capturedSamples = samples.map { it.copyOf() }
        _uiState.value = state.copy(
            capturedSamples = captured,
            faceReady = false,
            saving = true,
            status = "Encrypting and saving face templates…",
        )
        saveEnrollment(session, capturedSamples)
    }

    fun cancelEnrollment() {
        enrollmentJob?.cancel()
        enrollmentJob = null
        latestEmbedding.set(null)
        samples.clear()
        _uiState.value = FaceEnrollmentUiState(
            status = "Enrollment cancelled. Previously saved face data was not changed.",
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun reportTransfer(message: String) {
        _uiState.value = _uiState.value.copy(transferMessage = message)
    }

    fun deleteFaceData(sewadar: SewadarEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    templateDao.deleteForSewadar(sewadar.id)
                }
                _uiState.value = _uiState.value.copy(
                    message = "Deleted face data for ${sewadar.name}.",
                )
            } catch (error: SQLiteException) {
                _uiState.value = _uiState.value.copy(
                    message = "Could not delete face data: ${error.message ?: "database error"}",
                )
            }
        }
    }

    private fun saveEnrollment(session: FaceEnrollmentSession, capturedSamples: List<FloatArray>) {
        enrollmentJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val enrolledAt = System.currentTimeMillis()
                    val templates = capturedSamples.mapIndexed { index, sample ->
                        val metadata = FaceTemplateMetadata(
                            sewadarId = session.sewadar.id,
                            sampleIndex = index,
                            modelVersion = FaceModelContract.MODEL_VERSION,
                            consentVersion = FaceModelContract.CONSENT_VERSION,
                            consentAt = session.consentAt,
                            enrolledAt = enrolledAt,
                        )
                        val encrypted = FaceTemplateCrypto.encrypt(sample, metadata)
                        FaceTemplateEntity(
                            sewadarId = metadata.sewadarId,
                            sampleIndex = metadata.sampleIndex,
                            modelVersion = metadata.modelVersion,
                            ivBase64 = encrypted.ivBase64,
                            encryptedEmbeddingBase64 = encrypted.ciphertextBase64,
                            consentVersion = metadata.consentVersion,
                            consentAt = metadata.consentAt,
                            enrolledAt = metadata.enrolledAt,
                        )
                    }
                    database.withTransaction {
                        templateDao.deleteForSewadar(session.sewadar.id)
                        templateDao.insertAll(templates)
                    }
                }
                samples.clear()
                latestEmbedding.set(null)
                _uiState.value = FaceEnrollmentUiState(
                    status = "Enrollment complete. ${capturedSamples.size} encrypted templates saved for ${session.sewadar.name}; no photos were saved.",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: GeneralSecurityException) {
                failSave(error.message)
            } catch (error: IOException) {
                failSave(error.message)
            } catch (error: ProviderException) {
                failSave(error.message)
            } catch (error: SQLiteException) {
                failSave(error.message)
            } catch (error: IllegalArgumentException) {
                failSave(error.message)
            } catch (error: IllegalStateException) {
                failSave(error.message)
            } catch (error: SecurityException) {
                failSave(error.message)
            } finally {
                enrollmentJob = null
            }
        }
    }

    private fun failSave(reason: String?) {
        samples.clear()
        latestEmbedding.set(null)
        _uiState.value = _uiState.value.copy(
            session = null,
            saving = false,
            faceReady = false,
            status = "Could not save face enrollment: ${reason ?: "storage or encryption error"}",
        )
    }

}
