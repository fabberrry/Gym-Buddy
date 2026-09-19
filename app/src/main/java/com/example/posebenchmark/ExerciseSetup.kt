package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class CoachingExercise { SQUAT, PUSH_UP }
enum class CameraView { FRONT, SIDE_LEFT, SIDE_RIGHT, THREE_QUARTER }
enum class SetupStage { DEMO, ALIGN, COUNTDOWN, ACTIVE, TEMPORARILY_LOST }
enum class SetupHint { CAMERA_VIEW, BODY_NOT_VISIBLE, TOO_FAR, TOO_CLOSE, MOVE_LEFT,
    MOVE_RIGHT, TURN_SIDEWAYS, START_POSE, HOLD_STILL, READY }

data class TemplatePoint(val x: Float, val y: Float)
data class PoseTemplate(val joints: Map<Int, TemplatePoint>)

/** Camera requirements and normalized start-pose geometry, independent of drawing. */
data class ExerciseProfile(
    val exercise: CoachingExercise,
    val cameraHeight: String,
    val requiredSideLandmarks: IntArray,
    val preferredViews: Set<CameraView>,
    val setupPose: PoseTemplate,
    val minBodySpan: Float,
    val maxBodySpan: Float
) {
    companion object {
        val SQUAT = ExerciseProfile(CoachingExercise.SQUAT, "hip",
            intArrayOf(11, 23, 25, 27), setOf(CameraView.SIDE_LEFT, CameraView.SIDE_RIGHT),
            PoseTemplate(mapOf(11 to TemplatePoint(0f, 0f),
                23 to TemplatePoint(.03f, .40f), 25 to TemplatePoint(.08f, .70f),
                27 to TemplatePoint(.03f, 1f))), .42f, 1.55f)
        val PUSH_UP = ExerciseProfile(CoachingExercise.PUSH_UP, "body",
            intArrayOf(11, 13, 15, 23, 27), setOf(CameraView.SIDE_LEFT, CameraView.SIDE_RIGHT),
            PoseTemplate(mapOf(11 to TemplatePoint(0f, 0f),
                13 to TemplatePoint(.06f, .18f), 15 to TemplatePoint(.06f, .40f),
                23 to TemplatePoint(.48f, .05f), 27 to TemplatePoint(1f, .10f))), .42f, 1.55f)
        fun forExercise(exercise: CoachingExercise) =
            if (exercise == CoachingExercise.SQUAT) SQUAT else PUSH_UP
    }
}

/** Explicit domain name used by recorded-sequence and future exercise integrations. */
typealias ExerciseAnalysisProfile = ExerciseProfile

data class SetupDisplay(
    val stage: SetupStage,
    val hint: SetupHint,
    val countdown: Int = 0,
    val selectedSide: Int? = null,
    val timestampMs: Long = 0,
    val alignmentScore: Float = 0f
) {
    val canScore: Boolean get() = stage == SetupStage.ACTIVE && hint == SetupHint.READY
}

/** Time-based readiness and temporary loss, separate from the movement phase. */
class ExerciseSetupController {
    companion object {
        const val ALIGNMENT_HOLD_MS = 1_200L
        const val LOST_GRACE_MS = 350L
        const val ALIGNMENT_MIN_SCORE = .72f
        const val MIN_VISIBILITY = .60f
        private const val DEMO_MS = 1_800L
        private const val COUNTDOWN_MS = 2_000L
        private const val SIDE_SWITCH_MS = 500L
    }
    private var stage = SetupStage.DEMO
    private var firstMs = -1L
    private var lastMs = -1L
    private var holdStartMs = -1L
    private var countdownStartMs = -1L
    private var lostStartMs = -1L
    private var selectedSide: Int? = null
    private var sideCandidateSinceMs = -1L

    fun reset() {
        stage = SetupStage.DEMO
        firstMs = -1L; lastMs = -1L; holdStartMs = -1L
        countdownStartMs = -1L; lostStartMs = -1L
        selectedSide = null; sideCandidateSinceMs = -1L
    }

    fun retry() {
        stage = SetupStage.ALIGN
        holdStartMs = -1L; countdownStartMs = -1L; lostStartMs = -1L
    }

    fun update(profile: ExerciseProfile, points: List<NormalizedLandmark>?,
               width: Int, height: Int, nowMs: Long): SetupDisplay {
        if (nowMs <= lastMs) return display(SetupHint.CAMERA_VIEW, nowMs, 0f)
        if (firstMs < 0) firstMs = nowMs
        lastMs = nowMs
        if (stage == SetupStage.DEMO && nowMs - firstMs < DEMO_MS)
            return display(SetupHint.CAMERA_VIEW, nowMs, 0f)
        if (stage == SetupStage.DEMO) stage = SetupStage.ALIGN
        val assessment = assess(profile, points, width, height, nowMs,
            stage != SetupStage.ACTIVE && stage != SetupStage.TEMPORARILY_LOST)
        if (assessment.hint != SetupHint.READY || assessment.score < ALIGNMENT_MIN_SCORE) {
            holdStartMs = -1L
            if (stage == SetupStage.ACTIVE || stage == SetupStage.TEMPORARILY_LOST) {
                if (lostStartMs < 0) lostStartMs = nowMs
                stage = if (nowMs - lostStartMs <= LOST_GRACE_MS)
                    SetupStage.TEMPORARILY_LOST else SetupStage.ALIGN
            } else {
                stage = SetupStage.ALIGN; countdownStartMs = -1L
            }
            return display(assessment.hint, nowMs, assessment.score)
        }
        if (stage == SetupStage.TEMPORARILY_LOST) {
            stage = SetupStage.ACTIVE; lostStartMs = -1L
        }
        if (stage == SetupStage.ALIGN) {
            if (holdStartMs < 0) holdStartMs = nowMs
            if (nowMs - holdStartMs >= ALIGNMENT_HOLD_MS) {
                stage = SetupStage.COUNTDOWN; countdownStartMs = nowMs
            }
        }
        if (stage == SetupStage.COUNTDOWN) {
            val remaining = COUNTDOWN_MS - (nowMs - countdownStartMs)
            if (remaining > 0) return display(SetupHint.HOLD_STILL, nowMs,
                assessment.score, ((remaining + 999) / 1000).toInt())
            stage = SetupStage.ACTIVE
        }
        lostStartMs = -1L
        return display(SetupHint.READY, nowMs, assessment.score)
    }

    private fun display(hint: SetupHint, time: Long, score: Float, countdown: Int = 0) =
        SetupDisplay(stage, hint, countdown, selectedSide, time, score)

    private data class Assessment(val hint: SetupHint, val score: Float)

    private fun assess(profile: ExerciseProfile, points: List<NormalizedLandmark>?,
                       width: Int, height: Int, nowMs: Long,
                       requireStartPose: Boolean): Assessment {
        if (points == null || points.size < 29 || width <= 0 || height <= 0)
            return Assessment(SetupHint.CAMERA_VIEW, 0f)
        val left = quality(points, profile.requiredSideLandmarks, 0)
        val right = quality(points, profile.requiredSideLandmarks, 1)
        val previousSide = selectedSide
        if (previousSide == null) selectedSide = if (left >= right) 0 else 1
        else {
            val current = if (previousSide == 0) left else right
            val other = if (previousSide == 0) right else left
            if (other >= MIN_VISIBILITY && other > current + .15f) {
                if (sideCandidateSinceMs < 0) sideCandidateSinceMs = nowMs
                if (nowMs - sideCandidateSinceMs >= SIDE_SWITCH_MS) {
                    selectedSide = 1 - previousSide; sideCandidateSinceMs = -1L
                    stage = SetupStage.ALIGN; holdStartMs = -1L
                }
            } else sideCandidateSinceMs = -1L
        }
        val side = selectedSide ?: return Assessment(SetupHint.BODY_NOT_VISIBLE, 0f)
        val visibility = if (side == 0) left else right
        if (visibility < MIN_VISIBILITY)
            return Assessment(SetupHint.BODY_NOT_VISIBLE, visibility * .25f)
        val required = profile.requiredSideLandmarks.map { points[it + side] }
        if (required.any { it.x() !in 0.06f..0.94f || it.y() !in 0.06f..0.94f })
            return Assessment(SetupHint.TOO_CLOSE, .20f)
        val shoulder = points[11 + side]
        val ankle = points[27 + side]
        val span = hypot((ankle.x() - shoulder.x()) * width,
            (ankle.y() - shoulder.y()) * height)
        val relativeSpan = span / min(width, height)
        if (relativeSpan < profile.minBodySpan)
            return Assessment(SetupHint.TOO_FAR, relativeSpan / profile.minBodySpan * .4f)
        if (relativeSpan > profile.maxBodySpan)
            return Assessment(SetupHint.TOO_CLOSE, .25f)
        val centerX = (shoulder.x() + ankle.x()) / 2f
        val centerY = (shoulder.y() + ankle.y()) / 2f
        if (centerX < .38f) return Assessment(SetupHint.MOVE_RIGHT, .45f)
        if (centerX > .62f) return Assessment(SetupHint.MOVE_LEFT, .45f)
        if (centerY !in 0.25f..0.75f) return Assessment(SetupHint.START_POSE, .45f)
        if (reliable(points[11]) && reliable(points[12]) &&
            reliable(points[23]) && reliable(points[24])) {
            val shoulderWidth = hypot((points[11].x() - points[12].x()) * width,
                (points[11].y() - points[12].y()) * height)
            val hipWidth = hypot((points[23].x() - points[24].x()) * width,
                (points[23].y() - points[24].y()) * height)
            if (maxOf(shoulderWidth, hipWidth) / span > .28f)
                return Assessment(SetupHint.TURN_SIDEWAYS, .45f)
        }
        val templateScore = templateScore(profile, points, width, height, side, span)
        val centerScore = (1f - hypot(centerX - .5f, centerY - .5f) / .35f).coerceIn(0f, 1f)
        val scaleScore = (1f - abs(relativeSpan - .95f) / .95f).coerceIn(0f, 1f)
        val score = (.30f * visibility + .15f * centerScore + .15f * scaleScore +
            .40f * (if (requireStartPose) templateScore else 1f)).coerceIn(0f, 1f)
        if (requireStartPose && templateScore < .48f)
            return Assessment(SetupHint.START_POSE, score)
        return Assessment(if (score >= ALIGNMENT_MIN_SCORE) SetupHint.READY
            else SetupHint.START_POSE, score)
    }

    private fun templateScore(profile: ExerciseProfile, points: List<NormalizedLandmark>,
                              width: Int, height: Int, side: Int, span: Float): Float {
        val shoulder = points[11 + side]
        val ankle = points[27 + side]
        val facing = if (ankle.x() >= shoulder.x()) 1f else -1f
        var error = 0f
        profile.setupPose.joints.forEach { (index, target) ->
            val point = points[index + side]
            val x = (point.x() - shoulder.x()) * width / span * facing
            val y = (point.y() - shoulder.y()) * height / span
            error += hypot(x - target.x, y - target.y)
        }
        return (1f - error / profile.setupPose.joints.size / .45f).coerceIn(0f, 1f)
    }

    private fun quality(points: List<NormalizedLandmark>, indices: IntArray, side: Int): Float =
        indices.minOf { index ->
            val point = points[index + side]
            if (reliable(point)) point.visibility().orElse(0f) else 0f
        }

    private fun reliable(point: NormalizedLandmark): Boolean =
        point.x().isFinite() && point.y().isFinite() &&
            point.x() in 0f..1f && point.y() in 0f..1f &&
            point.visibility().orElse(0f) >= MIN_VISIBILITY
}
