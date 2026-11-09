package org.sewaentry.tablet.face

import kotlin.math.abs

enum class HeadTurnStage {
    CENTER,
    TURN,
    RETURN,
    PASSED,
}

data class HeadTurnProgress(
    val stage: HeadTurnStage,
    val instruction: String,
    val passed: Boolean,
)

class HeadTurnLiveness {
    private var stage = HeadTurnStage.CENTER
    private var challengeStartedAt: Long? = null
    private var passedAt: Long? = null

    @Synchronized
    fun update(faceCount: Int, yawDegrees: Float?, elapsedRealtime: Long): HeadTurnProgress {
        if (faceCount != 1 || yawDegrees == null || !yawDegrees.isFinite()) {
            reset()
            return progress(
                if (faceCount > 1) "Only one face can be in view." else "Position one face in the camera view.",
            )
        }

        val startedAt = challengeStartedAt
        if (startedAt != null &&
            stage != HeadTurnStage.PASSED &&
            elapsedRealtime - startedAt > CHALLENGE_TIMEOUT_MS
        ) {
            reset()
        }
        val completedAt = passedAt
        if (completedAt != null && elapsedRealtime - completedAt > PASSED_WINDOW_MS) {
            reset()
        }

        when (stage) {
            HeadTurnStage.CENTER -> if (abs(yawDegrees) <= CENTER_MAX_YAW_DEGREES) {
                stage = HeadTurnStage.TURN
                challengeStartedAt = elapsedRealtime
            }
            HeadTurnStage.TURN -> if (abs(yawDegrees) >= TURN_MIN_YAW_DEGREES &&
                abs(yawDegrees) <= TURN_MAX_YAW_DEGREES
            ) {
                stage = HeadTurnStage.RETURN
            }
            HeadTurnStage.RETURN -> if (abs(yawDegrees) <= CENTER_MAX_YAW_DEGREES) {
                stage = HeadTurnStage.PASSED
                passedAt = elapsedRealtime
            }
            HeadTurnStage.PASSED -> Unit
        }
        return progress()
    }

    @Synchronized
    fun reset() {
        stage = HeadTurnStage.CENTER
        challengeStartedAt = null
        passedAt = null
    }

    private fun progress(instruction: String? = null) = HeadTurnProgress(
        stage = stage,
        instruction = instruction ?: when (stage) {
            HeadTurnStage.CENTER -> "Center your face."
            HeadTurnStage.TURN -> "Turn your head slightly to either side."
            HeadTurnStage.RETURN -> "Face the camera again."
            HeadTurnStage.PASSED -> "Liveness passed. Matching face…"
        },
        passed = stage == HeadTurnStage.PASSED,
    )

    private companion object {
        const val CENTER_MAX_YAW_DEGREES = 8f
        const val TURN_MIN_YAW_DEGREES = 15f
        const val TURN_MAX_YAW_DEGREES = 45f
        const val CHALLENGE_TIMEOUT_MS = 12_000L
        const val PASSED_WINDOW_MS = 3_000L
    }
}

/** Faster, no-motion readiness check: one centered face held briefly in view. */
class SteadyFaceLiveness {
    private var centeredAt: Long? = null
    private var passedAt: Long? = null

    @Synchronized
    fun update(faceCount: Int, yawDegrees: Float?, elapsedRealtime: Long): HeadTurnProgress {
        if (faceCount != 1 || yawDegrees == null || !yawDegrees.isFinite() || abs(yawDegrees) > CENTER_MAX_YAW_DEGREES) {
            centeredAt = null
            passedAt = null
            return HeadTurnProgress(
                stage = HeadTurnStage.CENTER,
                instruction = if (faceCount > 1) "Only one face can be in view." else "Center your face and hold still briefly.",
                passed = false,
            )
        }
        val readySince = centeredAt ?: elapsedRealtime.also { centeredAt = it }
        if (elapsedRealtime - readySince >= HOLD_STEADY_MS) passedAt = elapsedRealtime
        val passed = passedAt?.let { elapsedRealtime - it <= PASSED_WINDOW_MS } ?: false
        if (!passed && passedAt != null) {
            centeredAt = elapsedRealtime
            passedAt = null
        }
        return HeadTurnProgress(
            stage = if (passed) HeadTurnStage.PASSED else HeadTurnStage.CENTER,
            instruction = if (passed) "Face ready. Matching…" else "Hold your face centered briefly.",
            passed = passed,
        )
    }

    @Synchronized
    fun reset() {
        centeredAt = null
        passedAt = null
    }

    private companion object {
        const val CENTER_MAX_YAW_DEGREES = 12f
        const val HOLD_STEADY_MS = 450L
        const val PASSED_WINDOW_MS = 3_000L
    }
}
