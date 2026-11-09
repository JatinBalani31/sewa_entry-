package org.sewaentry.tablet.face

data class FaceGalleryTemplate(
    val sewadarId: Long,
    val sewadarName: String,
    val departmentId: Long?,
    val embedding: FloatArray,
)

data class FaceMatch(
    val sewadarId: Long,
    val sewadarName: String,
    val departmentId: Long?,
    val score: Float,
)

class FaceRecognitionMatcher(
    private val threshold: Float = FaceAttendancePolicy.MATCH_THRESHOLD,
) {
    init {
        require(threshold in -1f..1f)
    }

    fun findMatch(probe: FloatArray, gallery: List<FaceGalleryTemplate>): FaceMatch? {
        require(probe.size == FaceModelContract.EMBEDDING_SIZE) {
            "Probe embedding does not match ${FaceModelContract.MODEL_VERSION}"
        }
        require(probe.all { it.isFinite() }) { "Probe embedding contains a non-finite value" }

        val bestBySewadar = mutableMapOf<Long, FaceMatch>()
        for (template in gallery) {
            require(template.embedding.size == probe.size) {
                "Stored embedding for ${template.sewadarName} does not match ${FaceModelContract.MODEL_VERSION}"
            }
            require(template.embedding.all { it.isFinite() }) {
                "Stored embedding for ${template.sewadarName} contains a non-finite value"
            }
            var score = 0f
            for (index in probe.indices) {
                score += probe[index] * template.embedding[index]
            }
            val current = bestBySewadar[template.sewadarId]
            if (current == null || score > current.score) {
                bestBySewadar[template.sewadarId] = FaceMatch(
                    sewadarId = template.sewadarId,
                    sewadarName = template.sewadarName,
                    departmentId = template.departmentId,
                    score = score,
                )
            }
        }
        return bestBySewadar.values.maxByOrNull { it.score }?.takeIf { it.score >= threshold }
    }
}

class ConsecutiveFaceMatchConfirmation(
    private val requiredHits: Int = FaceAttendancePolicy.REQUIRED_CONSECUTIVE_HITS,
) {
    private var pendingSewadarId: Long? = null
    private var consecutiveHits = 0

    init {
        require(requiredHits > 0)
    }

    @Synchronized
    fun accept(match: FaceMatch?): FaceMatch? {
        if (match == null) {
            reset()
            return null
        }
        if (pendingSewadarId == match.sewadarId) {
            consecutiveHits++
        } else {
            pendingSewadarId = match.sewadarId
            consecutiveHits = 1
        }
        if (consecutiveHits < requiredHits) return null
        reset()
        return match
    }

    @Synchronized
    fun reset() {
        pendingSewadarId = null
        consecutiveHits = 0
    }
}
