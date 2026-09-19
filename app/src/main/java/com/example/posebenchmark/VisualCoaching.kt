package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** One observable correction. The renderer only shows the highest-priority cue. */
data class VisualCue(
    val id: String,
    val state: PostureVisualState,
    val joint: Int,
    val connections: List<Pair<Int, Int>>,
    val direction: CorrectionDirection? = null,
    val target: TargetJointPosition? = null,
    val confidence: Float,
    val timestampMs: Long
)

/** Provisional V1 rules. A camera view cannot establish all aspects of exercise form. */
class VisualCoaching {
    companion object {
        const val PUSH_UP_TOP_ELBOW_WARNING_DEG = 140.0
        const val PUSH_UP_TOP_ELBOW_WRONG_DEG = 130.0
        private const val CONFIRM_FRAMES = 3
    }

    private val squatEvaluator = SquatPostureEvaluator()
    private var pushUpHipFrames = 0
    private var pushUpElbowFrames = 0

    fun reset() {
        squatEvaluator.reset()
        pushUpHipFrames = 0
        pushUpElbowFrames = 0
    }

    fun squat(points: List<NormalizedLandmark>, width: Int, height: Int,
              result: SquatExerciseResult, timestampMs: Long): PostureGuidance {
        val cue = squatEvaluator.evaluate(points, width, height, result, timestampMs)
        return cue?.let(::guidance) ?: PostureGuidance()
    }

    fun pushUp(points: List<NormalizedLandmark>, visual: PushUpAnalysis,
               result: PushUpExerciseResult, timestampMs: Long): PostureGuidance {
        if (visual.trackingState != ExerciseTrackingState.READY || !result.bodyVisible) {
            reset()
            return PostureGuidance()
        }
        val candidates = ArrayList<VisualCue>()
        val hipJoint = visual.guidance.joints.firstOrNull()
        if (hipJoint != null) {
            pushUpHipFrames = if (hipJoint.state == PostureVisualState.WRONG)
                pushUpHipFrames + 1 else 0
            val state = if (hipJoint.state == PostureVisualState.WRONG &&
                pushUpHipFrames < CONFIRM_FRAMES) PostureVisualState.WARNING else hipJoint.state
            val hip = points.getOrNull(hipJoint.landmarkIndex)
            val confidence = hip?.visibility()?.orElse(0f) ?: 0f
            if (confidence >= 0.6f) candidates += VisualCue("hip_line", state,
                hipJoint.landmarkIndex,
                visual.guidance.connections.map { it.startIndex to it.endIndex },
                if (state == PostureVisualState.GOOD) null else hipJoint.direction,
                if (state == PostureVisualState.GOOD) null else hipJoint.target,
                confidence, timestampMs)
        }

        val offset = if (result.activeSide == PushUpSide.RIGHT) 1 else 0
        val elbow = points.getOrNull(13 + offset)
        val confidence = elbow?.visibility()?.orElse(0f) ?: 0f
        val elbowRuleAvailable = result.phase == PushUpPhase.TOP &&
            result.activeSide != null && confidence >= 0.6f &&
            result.averageElbowAngle.isFinite()
        if (elbowRuleAvailable) {
            val angle = result.averageElbowAngle
            pushUpElbowFrames = if (angle < PUSH_UP_TOP_ELBOW_WRONG_DEG)
                pushUpElbowFrames + 1 else 0
            val state = when {
                pushUpElbowFrames >= CONFIRM_FRAMES -> PostureVisualState.WRONG
                angle < PUSH_UP_TOP_ELBOW_WARNING_DEG -> PostureVisualState.WARNING
                else -> PostureVisualState.GOOD
            }
            if (state != PostureVisualState.GOOD) candidates += VisualCue("top_elbow", state,
                13 + offset, listOf((11 + offset) to (13 + offset),
                    (13 + offset) to (15 + offset)), confidence = confidence,
                timestampMs = timestampMs)
        } else pushUpElbowFrames = 0

        val primary = candidates.firstOrNull { it.state == PostureVisualState.WRONG }
            ?: candidates.firstOrNull { it.state == PostureVisualState.WARNING }
            ?: candidates.firstOrNull()
        return primary?.let(::guidance) ?: PostureGuidance()
    }

    private fun guidance(cue: VisualCue) = PostureGuidance(
        joints = listOf(JointCorrection(cue.joint, cue.state, cue.direction, cue.target)),
        connections = cue.connections.map { ConnectionCorrection(it.first, it.second, cue.state) }
    )
}
