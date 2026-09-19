package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** Provisional, phase-aware squat rules. All movement distances are body-relative. */
class SquatPostureEvaluator {
    companion object {
        const val MIN_DEPTH_LEG_LENGTHS = .20
        const val STANDING_TORSO_WARNING_DEG = 30.0
        const val STANDING_TORSO_WRONG_DEG = 40.0
        const val MOVING_TORSO_WARNING_DEG = 30.0
        const val MOVING_TORSO_WRONG_DEG = 40.0
        const val ISSUE_ACTIVATION_MS = 250L
        const val ISSUE_CLEAR_MS = 200L
        private const val MIN_VISIBILITY = .60f
    }

    private val depthIssue = PersistentIssue()
    private val torsoIssue = PersistentIssue()

    fun reset() {
        depthIssue.reset()
        torsoIssue.reset()
    }

    fun evaluate(points: List<NormalizedLandmark>, width: Int, height: Int,
                 result: SquatExerciseResult, timeMs: Long): VisualCue? {
        if (!result.bodyVisible || width <= 0 || height <= 0) {
            reset()
            return null
        }
        val side = if (result.selectedSide == "RIGHT") 1 else 0
        val shoulder = points.getOrNull(11 + side) ?: return null
        val hip = points.getOrNull(23 + side) ?: return null
        val knee = points.getOrNull(25 + side) ?: return null
        val ankle = points.getOrNull(27 + side) ?: return null
        val confidence = minOf(shoulder.visibility().orElse(0f),
            hip.visibility().orElse(0f), knee.visibility().orElse(0f),
            ankle.visibility().orElse(0f))
        if (confidence < MIN_VISIBILITY) {
            reset()
            return null
        }

        val depthEvidence = result.shallowBottom &&
            result.normalizedDepth in .08..<MIN_DEPTH_LEG_LENGTHS &&
            result.legLengthPx.isFinite()
        val depthState = depthIssue.update(depthEvidence, timeMs)
        val depthCue = if (depthState != null) {
            val targetY = (hip.y() + ((MIN_DEPTH_LEG_LENGTHS - result.normalizedDepth) *
                result.legLengthPx / height).toFloat()).coerceIn(.02f, .98f)
            VisualCue("squat_depth", depthState, 23 + side,
                listOf((23 + side) to (25 + side)), CorrectionDirection.DOWN,
                TargetJointPosition(hip.x(), targetY), confidence, timeMs)
        } else null

        val standing = result.phase == SquatExercisePhase.STANDING &&
            result.kneeFlexion <= 25.0
        val moving = result.phase == SquatExercisePhase.DESCENDING ||
            result.phase == SquatExercisePhase.BOTTOM ||
            result.phase == SquatExercisePhase.ASCENDING
        val torsoMeasure = if (standing) result.torsoInclinationDeg
            else result.torsoDeviationDeg
        val warning = if (standing) STANDING_TORSO_WARNING_DEG else MOVING_TORSO_WARNING_DEG
        val wrong = if (standing) STANDING_TORSO_WRONG_DEG else MOVING_TORSO_WRONG_DEG
        val torsoEvidence = (standing || moving) && torsoMeasure.isFinite() &&
            torsoMeasure >= warning && (standing || result.normalizedDepth > .06)
        val torsoState = torsoIssue.update(torsoEvidence, timeMs,
            torsoMeasure >= wrong)
        val torsoCue = if (torsoState != null) {
            val direction = if (shoulder.x() > hip.x()) CorrectionDirection.LEFT
                else CorrectionDirection.RIGHT
            // A vertical target is defensible at standing only. During a squat,
            // natural torso lean varies, so avoid claiming an exact target.
            val target = if (standing) TargetJointPosition(hip.x(), shoulder.y()) else null
            VisualCue("squat_torso", torsoState, 11 + side,
                listOf((11 + side) to (23 + side)), direction, target,
                confidence, timeMs)
        } else null

        return when {
            depthCue?.state == PostureVisualState.WRONG -> depthCue
            torsoCue?.state == PostureVisualState.WRONG -> torsoCue
            depthCue != null -> depthCue
            else -> torsoCue
        }
    }

    private class PersistentIssue {
        private var activeSince = -1L
        private var strongSince = -1L
        private var clearSince = -1L
        private var confirmed = false

        fun reset() {
            activeSince = -1L; strongSince = -1L; clearSince = -1L; confirmed = false
        }

        fun update(evidence: Boolean, now: Long, strong: Boolean = true): PostureVisualState? {
            if (evidence) {
                clearSince = -1L
                if (activeSince < 0) activeSince = now
                if (strong) {
                    if (strongSince < 0) strongSince = now
                    if (now - strongSince >= ISSUE_ACTIVATION_MS) confirmed = true
                } else strongSince = -1L
                return if (confirmed) PostureVisualState.WRONG else PostureVisualState.WARNING
            }
            if (activeSince < 0) return null
            if (clearSince < 0) clearSince = now
            if (now - clearSince >= ISSUE_CLEAR_MS) {
                reset()
                return null
            }
            return if (confirmed) PostureVisualState.WRONG else PostureVisualState.WARNING
        }
    }
}
