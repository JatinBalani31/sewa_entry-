package org.sewaentry.tablet.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import org.sewaentry.tablet.face.FaceModelContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import java.util.UUID

/** One password-protected local file containing the SQLite data and portable face entries. */
object FullLocalBackup {
    fun suggestedFileName(): String = BackupManager.suggestedFileName().removeSuffix(".db") + ".sewabackup"

    suspend fun backupTo(context: Context, uri: Uri, password: CharArray) {
        val db = SewaDatabase.get(context)
        val entries = exportFaceEntries(db)
        val payload = BackupManager.withSanitizedBackup(context) { databaseFile ->
            val sampleCount = (0 until entries.length()).sumOf {
                entries.getJSONObject(it).getJSONArray("samples").length()
            }
            JSONObject()
                .put("format", "sewa-full-local-backup-v2")
                .put("sourceTimeZone", ZoneId.systemDefault().id)
                .put("database", Base64.encodeToString(databaseFile.readBytes(), Base64.NO_WRAP))
                .put("faceEntries", entries)
                .put("faceMemberCount", entries.length())
                .put("faceSampleCount", sampleCount)
                .toString()
        }
        val sealed = FaceTemplateTransfer.seal(payload, password)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(sealed) }
            ?: throw IOException("Could not open the chosen location for writing.")
    }

    suspend fun restoreFrom(
        context: Context,
        uri: Uri,
        password: CharArray,
    ): String {
        val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the selected backup file.")

        val isDatabaseOnly = fileBytes.size >= 16 && String(fileBytes, 0, 15).startsWith("SQLite format 3")
        val payload = if (isDatabaseOnly) null else JSONObject(FaceTemplateTransfer.open(fileBytes.toString(Charsets.UTF_8), password))
        val sourceTimeZone = if (payload == null) {
            null
        } else {
            when (payload.optString("format")) {
                "sewa-full-local-backup-v1" -> null
                "sewa-full-local-backup-v2" -> ZoneId.of(payload.getString("sourceTimeZone"))
                else -> throw IllegalArgumentException("Unsupported local backup format.")
            }
        }
        if (payload != null) {
            require(payload.optString("format") in setOf("sewa-full-local-backup-v1", "sewa-full-local-backup-v2")) {
                "Unsupported local backup format."
            }
        }
        val databaseBytes = if (isDatabaseOnly) fileBytes else Base64.decode(payload!!.getString("database"), Base64.DEFAULT)
        require(databaseBytes.size >= 16 && String(databaseBytes, 0, 15).startsWith("SQLite format 3")) {
            "The backup does not contain a valid database."
        }
        val entries = payload?.optJSONArray("faceEntries") ?: JSONArray()
        validateFaceEntries(entries)
        if (payload?.has("faceMemberCount") == true) {
            require(payload.getInt("faceMemberCount") == entries.length()) {
                "The backup face-member count does not match its data."
            }
        }
        if (payload?.has("faceSampleCount") == true) {
            val actualSamples = (0 until entries.length()).sumOf {
                entries.getJSONObject(it).getJSONArray("samples").length()
            }
            require(payload.getInt("faceSampleCount") == actualSamples) {
                "The backup face-template count does not match its data."
            }
        }

        // Verify that this device can re-encrypt face data before changing any data.
        if (entries.length() > 0) {
            FaceTemplateCrypto.encrypt(
                FloatArray(FaceModelContract.EMBEDDING_SIZE),
                FaceTemplateMetadata(1L, 0, FaceModelContract.MODEL_VERSION, FaceModelContract.CONSENT_VERSION, 1L, 1L),
            )
        }

        val stagedFile = File.createTempFile("sewa-import-", ".db", context.cacheDir)
        val stagedName = "sewa-import-${UUID.randomUUID()}.db"
        var staging: SewaDatabase? = null
        try {
            FileOutputStream(stagedFile).use { it.write(databaseBytes) }
            staging = SewaDatabase.openStaging(context, stagedFile, stagedName)
            val result = mergeDatabase(
                target = SewaDatabase.get(context),
                source = staging,
                faceEntries = entries,
                sourceTimeZone = sourceTimeZone,
                destinationTimeZone = ZoneId.systemDefault(),
            )
            return if (isDatabaseOnly) {
                "Backup imported without replacing local data: ${result.summary}. Face templates were not included."
            } else {
                "Backup imported without replacing local data: ${result.summary}."
            }
        } finally {
            staging?.close()
            context.deleteDatabase(stagedName)
            stagedFile.delete()
            File(stagedFile.path + "-wal").delete()
            File(stagedFile.path + "-shm").delete()
        }
    }

    private suspend fun mergeDatabase(
        target: SewaDatabase,
        source: SewaDatabase,
        faceEntries: JSONArray,
        sourceTimeZone: ZoneId?,
        destinationTimeZone: ZoneId,
    ): BackupMergeResult = target.withTransaction {
        fun normalizeTime(timestamp: Long): Long =
            sourceTimeZone?.let { BackupTimeZone.preserveLocalTime(timestamp, it, destinationTimeZone) } ?: timestamp

        var departmentsAdded = 0
        var sewadarsAdded = 0
        var sewadarsUpdated = 0
        var attendanceAdded = 0
        var attendanceUpdated = 0
        var logsAdded = 0
        var facesImported = 0
        var facesKept = 0
        var faceEntriesUnmatched = 0

        val targetDepartments = target.departments().all().toMutableList()
        val departmentIds = mutableMapOf<Long, Long>()
        for (incoming in source.departments().all()) {
            val existing = targetDepartments.firstOrNull { it.name.equals(incoming.name, ignoreCase = true) }
            if (existing != null) {
                departmentIds[incoming.id] = existing.id
            } else {
                val insertedId = target.departments().insert(incoming.copy(id = 0))
                require(insertedId > 0) { "Could not import department ${incoming.name}." }
                departmentIds[incoming.id] = insertedId
                targetDepartments += incoming.copy(id = insertedId)
                departmentsAdded++
            }
        }

        val sourceSewadars = source.sewadars().all()
        val duplicateSourceNames = sourceSewadars
            .groupBy { it.departmentId to it.name.trim().lowercase() }
            .filterValues { it.size > 1 }
            .keys
        val targetSewadars = target.sewadars().all().toMutableList()
        val sewadarIds = mutableMapOf<Long, Long>()
        for (incoming in sourceSewadars) {
            val departmentId = incoming.departmentId?.let { departmentIds[it] }
            val sameDepartment = targetSewadars.filter { it.departmentId == departmentId }
            val incomingPhones = setOfNotNull(incoming.phone, incoming.phone2).map(::phoneDigits).filter { it.isNotEmpty() }.toSet()
            val nameMatches = sameDepartment.filter { it.name.equals(incoming.name, ignoreCase = true) }
            val phoneMatches = if (incomingPhones.isNotEmpty()) {
                sameDepartment.filter { existing ->
                    setOfNotNull(existing.phone, existing.phone2).map(::phoneDigits).any { it in incomingPhones }
                }.distinctBy { it.id }
            } else {
                emptyList()
            }
            val duplicateNameInSource =
                (incoming.departmentId to incoming.name.trim().lowercase()) in duplicateSourceNames
            val byName = when {
                nameMatches.size == 1 && !duplicateNameInSource -> nameMatches.single()
                nameMatches.size > 1 -> phoneMatches.singleOrNull()
                else -> null
            }
            val existing = if (duplicateNameInSource) phoneMatches.singleOrNull() else byName ?: phoneMatches.singleOrNull()
            if (existing == null) {
                val insertedId = target.sewadars().insert(incoming.copy(id = 0, departmentId = departmentId))
                require(insertedId > 0) { "Could not import sewadar ${incoming.name}." }
                val inserted = incoming.copy(id = insertedId, departmentId = departmentId)
                targetSewadars += inserted
                sewadarIds[incoming.id] = insertedId
                sewadarsAdded++
            } else {
                val updated = existing.copy(
                    name = incoming.name,
                    departmentId = departmentId ?: existing.departmentId,
                    phone = incoming.phone ?: existing.phone,
                    phone2 = incoming.phone2 ?: existing.phone2,
                    organization = incoming.organization ?: existing.organization,
                    isHod = false,
                    isActive = existing.isActive || incoming.isActive,
                )
                if (updated != existing) {
                    target.sewadars().update(updated)
                    val index = targetSewadars.indexOfFirst { it.id == existing.id }
                    if (index >= 0) targetSewadars[index] = updated
                    sewadarsUpdated++
                }
                sewadarIds[incoming.id] = existing.id
            }
        }

        val logIds = mutableMapOf<Long, Long>()
        for (incoming in source.attendanceLogs().all()) {
            val targetSewadarId = sewadarIds[incoming.sewadarId] ?: continue
            val mapped = incoming.copy(
                id = 0,
                sewadarId = targetSewadarId,
                departmentId = incoming.departmentId?.let { departmentIds[it] },
                deviceTime = normalizeTime(incoming.deviceTime),
                syncedAt = incoming.syncedAt?.let(::normalizeTime),
            )
            val existing = target.attendanceLogs().findByClientLogId(incoming.clientLogId)
            if (existing == null) {
                val insertedId = target.attendanceLogs().insert(mapped)
                logIds[incoming.id] = insertedId
                logsAdded++
            } else {
                val importedVersion = mapped.syncedAt ?: mapped.deviceTime
                val existingVersion = existing.syncedAt ?: existing.deviceTime
                if (importedVersion >= existingVersion) {
                    target.attendanceLogs().update(mapped.copy(id = existing.id))
                }
                logIds[incoming.id] = existing.id
            }
        }

        for (incoming in source.attendance().all()) {
            val targetSewadarId = sewadarIds[incoming.sewadarId] ?: continue
            val normalized = incoming.copy(
                arrivalAt = incoming.arrivalAt?.let(::normalizeTime),
                departureAt = incoming.departureAt?.let(::normalizeTime),
                updatedAt = normalizeTime(incoming.updatedAt),
            )
            val existing = target.attendance().find(targetSewadarId, normalized.sewaDate)
            if (existing == null || normalized.updatedAt >= existing.updatedAt) {
                val merged = normalized.copy(
                    id = existing?.id ?: 0,
                    sewadarId = targetSewadarId,
                    departmentId = incoming.departmentId?.let { departmentIds[it] },
                    arrivalLogId = incoming.arrivalLogId?.let { logIds[it] },
                    departureLogId = incoming.departureLogId?.let { logIds[it] },
                )
                target.attendance().upsert(merged)
                if (existing == null) attendanceAdded++ else attendanceUpdated++
            }
        }

        val knownAudit = target.attendanceOverrideAudit().all()
            .mapTo(mutableSetOf(), ::auditKey)
        for (incoming in source.attendanceOverrideAudit().all()) {
            val targetSewadarId = sewadarIds[incoming.sewadarId] ?: continue
            val mapped = incoming.copy(
                id = 0,
                sewadarId = targetSewadarId,
                occurredAt = normalizeTime(incoming.occurredAt),
            )
            if (knownAudit.add(auditKey(mapped))) target.attendanceOverrideAudit().insert(mapped)
        }

        var unmatched = 0
        for (i in 0 until faceEntries.length()) {
            val entry = faceEntries.getJSONObject(i)
            val departmentName = entry.optString("department").takeIf { it.isNotBlank() && it != "null" }
            val department = departmentName?.let { target.departments().findByName(it) }
            if (departmentName != null && department == null) {
                unmatched++
                continue
            }
            val mappedId = if (entry.has("sewadarId")) sewadarIds[entry.getLong("sewadarId")] else null
            val sewadar = mappedId?.let { id -> targetSewadars.firstOrNull { it.id == id } }
                ?: findFaceTransferTarget(
                    candidates = targetSewadars.filter { it.departmentId == department?.id },
                    entry = entry,
                )
            if (sewadar == null) {
                unmatched++
                continue
            }
            val samples = entry.getJSONArray("samples")
            val importedAt = (0 until samples.length()).maxOf {
                normalizeTime(samples.getJSONObject(it).getLong("enrolledAt"))
            }
            val existing = target.faceTemplates().forSewadar(sewadar.id)
            if (existing.isNotEmpty() && importedAt < existing.maxOf { it.enrolledAt }) {
                facesKept++
                continue
            }

            target.faceTemplates().deleteForSewadar(sewadar.id)
            val rows = (0 until samples.length()).map { index ->
                val sample = samples.getJSONObject(index)
                val values = sample.getJSONArray("embedding")
                val embedding = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                val metadata = FaceTemplateMetadata(
                    sewadarId = sewadar.id,
                    sampleIndex = index,
                    modelVersion = sample.getString("model"),
                    consentVersion = sample.getString("consentVersion"),
                    consentAt = normalizeTime(sample.getLong("consentAt")),
                    enrolledAt = normalizeTime(sample.getLong("enrolledAt")),
                )
                val encrypted = FaceTemplateCrypto.encrypt(embedding, metadata)
                FaceTemplateEntity(
                    sewadarId = sewadar.id,
                    sampleIndex = index,
                    modelVersion = metadata.modelVersion,
                    ivBase64 = encrypted.ivBase64,
                    encryptedEmbeddingBase64 = encrypted.ciphertextBase64,
                    consentVersion = metadata.consentVersion,
                    consentAt = metadata.consentAt,
                    enrolledAt = metadata.enrolledAt,
                )
            }
            target.faceTemplates().insertAll(rows)
            val storedRows = target.faceTemplates().forSewadar(sewadar.id)
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
                val values = samples.getJSONObject(index).getJSONArray("embedding")
                val expected = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                check(decrypted.contentEquals(expected)) {
                    "Imported face template verification failed for ${sewadar.name}."
                }
            }
            facesImported += rows.size
        }
        faceEntriesUnmatched = unmatched

        BackupMergeResult(
            summary = "$departmentsAdded department(s) added, $sewadarsAdded member(s) added, " +
                "$sewadarsUpdated member(s) updated, $attendanceAdded attendance row(s) added, " +
                "$attendanceUpdated attendance row(s) updated, $logsAdded attendance event(s) added, " +
                "$facesImported face sample(s) imported, $facesKept newer local face set(s) kept, " +
                "$faceEntriesUnmatched face entry/entries unmatched",
        )
    }

    private fun phoneDigits(value: String): String = value.filter(Char::isDigit)

    private fun findFaceTransferTarget(
        candidates: List<SewadarEntity>,
        entry: JSONObject,
    ): SewadarEntity? {
        val nameMatches = candidates.filter { it.name.equals(entry.getString("name"), ignoreCase = true) }
        val importedPhones = setOfNotNull(
            entry.optString("phone").takeIf { it.isNotBlank() && it != "null" },
            entry.optString("phone2").takeIf { it.isNotBlank() && it != "null" },
        ).map(::phoneDigits).filter { it.isNotEmpty() }.toSet()
        val phoneMatches = if (importedPhones.isEmpty()) {
            emptyList()
        } else {
            candidates.filter { candidate ->
                setOfNotNull(candidate.phone, candidate.phone2)
                    .map(::phoneDigits)
                    .any { it in importedPhones }
            }
        }
        return when {
            nameMatches.size == 1 -> nameMatches.single()
            nameMatches.size > 1 -> phoneMatches.singleOrNull()
            else -> phoneMatches.singleOrNull()
        }
    }

    private fun auditKey(entry: AttendanceOverrideAuditEntity): String =
        "${entry.sewadarId}|${entry.sewaDate}|${entry.action}|${entry.adminId}|${entry.note}|${entry.occurredAt}"

    private data class BackupMergeResult(val summary: String)

    private suspend fun exportFaceEntries(db: SewaDatabase): JSONArray {
        val departments = db.departments().all().associateBy { it.id }
        val sewadars = db.sewadars().all().associateBy { it.id }
        val templates = db.faceTemplates().allTemplatesForTransfer()
        return JSONArray().apply {
            templates.groupBy { it.template.sewadarId }.values.forEach { personTemplates ->
                val person = personTemplates.first()
                val samples = JSONArray()
                personTemplates.forEach { active ->
                    val t = active.template
                    val metadata = FaceTemplateMetadata(t.sewadarId, t.sampleIndex, t.modelVersion, t.consentVersion, t.consentAt, t.enrolledAt)
                    val embedding = FaceTemplateCrypto.decrypt(EncryptedFaceEmbedding(t.ivBase64, t.encryptedEmbeddingBase64), metadata)
                    samples.put(JSONObject().put("model", t.modelVersion).put("consentVersion", t.consentVersion)
                        .put("consentAt", t.consentAt).put("enrolledAt", t.enrolledAt)
                        .put("embedding", JSONArray().apply { embedding.forEach { value -> put(value.toDouble()) } }))
                }
                put(JSONObject().put("name", person.sewadarName)
                    .put("sewadarId", person.template.sewadarId)
                    .put("department", person.departmentId?.let { departments[it]?.name })
                    .put("phone", sewadars[person.template.sewadarId]?.phone)
                    .put("phone2", sewadars[person.template.sewadarId]?.phone2)
                    .put("samples", samples))
            }
        }
    }

    private fun validateFaceEntries(entries: JSONArray) {
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            require(entry.getString("name").isNotBlank()) { "Backup contains a face entry without a name." }
            val samples = entry.getJSONArray("samples")
            require(samples.length() > 0) { "Backup contains an empty face entry." }
            for (j in 0 until samples.length()) {
                val sample = samples.getJSONObject(j)
                require(sample.getString("model") == FaceModelContract.MODEL_VERSION) { "Backup contains an unsupported face model." }
                require(sample.getString("consentVersion").isNotBlank()) { "Backup contains incomplete face consent metadata." }
                require(sample.getLong("consentAt") > 0L && sample.getLong("enrolledAt") > 0L) {
                    "Backup contains incomplete face consent metadata."
                }
                val embedding = sample.getJSONArray("embedding")
                require(embedding.length() == FaceModelContract.EMBEDDING_SIZE) { "Backup contains an invalid face template." }
                for (k in 0 until embedding.length()) require(embedding.getDouble(k).isFinite()) { "Backup contains an invalid face template." }
            }
        }
    }

}
