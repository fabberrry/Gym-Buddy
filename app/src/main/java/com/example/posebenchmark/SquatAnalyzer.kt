package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One selected side, measured in image pixels before any geometric calculation. */
internal object SquatGeometry {
    fun distance(a: NormalizedLandmark, b: NormalizedLandmark, width: Int, height: Int): Double =
        hypot((a.x() - b.x()).toDouble() * width, (a.y() - b.y()).toDouble() * height)

    fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark,
              width: Int, height: Int): Double {
        val ax = (a.x() - b.x()).toDouble() * width
        val ay = (a.y() - b.y()).toDouble() * height
        val cx = (c.x() - b.x()).toDouble() * width
        val cy = (c.y() - b.y()).toDouble() * height
        val length = hypot(ax, ay) * hypot(cx, cy)
        if (length < 1.0) return Double.NaN
        return Math.toDegrees(acos((ax * cx + ay * cy).div(length).coerceIn(-1.0, 1.0)))
    }
}

/** Fixed-size causal median. Samples contain measurements only, never camera images. */
private class SignalHistory {
    private val hip = DoubleArray(3)
    private val knee = DoubleArray(3)
    private var count = 0
    private var next = 0

    fun clear() { count = 0; next = 0 }
    fun add(hipValue: Double, kneeValue: Double) {
        hip[next] = hipValue
        knee[next] = kneeValue
        next = (next + 1) % hip.size
        count = min(count + 1, hip.size)
    }
    fun hipMedian() = median(hip)
    fun kneeMedian() = median(knee)
    private fun median(values: DoubleArray): Double = when (count) {
        1 -> values[0]
        2 -> (values[0] + values[1]) / 2.0
        else -> values[0] + values[1] + values[2] -
            min(values[0], min(values[1], values[2])) -
            max(values[0], max(values[1], values[2]))
    }
}

internal class SquatAnalyzer {
    private companion object {
        const val VISIBILITY = 0.60f
        const val SIDE_SWITCH_FRAMES = 6
        const val READY_FRAMES = 4
        const val CONFIRM_FRAMES = 2
        const val FINISH_FRAMES = 3
        const val MIN_REP_MS = 700L
        const val MAX_GAP_MS = 350L
        const val VELOCITY_DEAD_ZONE = 0.055
    }

    private val history = SignalHistory()
    private var side: Int? = null // 0 left, 1 right
    private var switchFrames = 0
    private var lastValidMs = -1L
    private var lastSampleMs = -1L
    private var smoothHip = Double.NaN
    private var smoothKnee = Double.NaN
    private var previousHip = Double.NaN
    private var previousKnee = Double.NaN
    private var baseline = Double.NaN
    private var startMs = -1L
    private var deepest = 0.0
    private var maxFlexion = 0.0
    private var bottomTimestamp = -1L
    private var ascentTimestamp = -1L
    private var completedRep: SquatRepMetrics? = null
    private var readyFrames = 0
    private var downFrames = 0
    private var bottomFrames = 0
    private var upFrames = 0
    private var finishFrames = 0
    private var phase = SquatExercisePhase.NOT_READY
    private var reps = 0

    fun resetSession() {
        reps = 0
        side = null
        lastValidMs = -1L
        resetMovement()
    }

    private fun resetMovement() {
        phase = SquatExercisePhase.NOT_READY
        history.clear()
        smoothHip = Double.NaN
        smoothKnee = Double.NaN
        previousHip = Double.NaN
        previousKnee = Double.NaN
        baseline = Double.NaN
        lastSampleMs = -1L
        startMs = -1L
        deepest = 0.0
        maxFlexion = 0.0
        bottomTimestamp = -1L
        ascentTimestamp = -1L
        completedRep = null
        readyFrames = 0
        downFrames = 0
        bottomFrames = 0
        upFrames = 0
        finishFrames = 0
    }

    fun onPoseLost(timestampMs: Long) {
        // Never bridge a missing measurement into a completed rep.
        phase = SquatExercisePhase.NOT_READY
        if (lastValidMs < 0 || timestampMs - lastValidMs > MAX_GAP_MS) resetMovement()
    }

    fun analyze(points: List<NormalizedLandmark>, width: Int, height: Int,
                timestampMs: Long): SquatExerciseResult {
        if (width <= 0 || height <= 0 || points.size <= 28 ||
            (lastSampleMs >= 0 && timestampMs <= lastSampleMs)) return invalid(timestampMs)

        val left = quality(points, 0)
        val right = quality(points, 1)
        val selected = side
        if (selected == null) {
            side = when {
                left < VISIBILITY && right < VISIBILITY -> return invalid(timestampMs)
                left >= right -> 0
                else -> 1
            }
        } else {
            val current = if (selected == 0) left else right
            val other = if (selected == 0) right else left
            if (other >= VISIBILITY && other > current + 0.15f) switchFrames++ else switchFrames = 0
            if (switchFrames >= SIDE_SWITCH_FRAMES) {
                side = 1 - selected
                switchFrames = 0
                resetMovement() // A side change invalidates the baseline and pending rep.
            }
        }
        val chosen = side!!
        if ((if (chosen == 0) left else right) < VISIBILITY) return invalid(timestampMs)

        val hip = points[23 + chosen]
        val knee = points[25 + chosen]
        val ankle = points[27 + chosen]
        val legLength = SquatGeometry.distance(hip, knee, width, height) +
            SquatGeometry.distance(knee, ankle, width, height)
        if (legLength < height * 0.06) return invalid(timestampMs)
        val rawHip = (ankle.y() - hip.y()).toDouble() * height / legLength
        val rawKnee = 180.0 - SquatGeometry.angle(hip, knee, ankle, width, height)
        if (!rawHip.isFinite() || !rawKnee.isFinite() || rawHip !in -0.2..1.1 ||
            rawKnee !in 0.0..160.0) return invalid(timestampMs)

        if (lastValidMs >= 0 && timestampMs - lastValidMs > MAX_GAP_MS) resetMovement()
        lastValidMs = timestampMs
        history.add(rawHip, rawKnee)
        val hipMedian = history.hipMedian()
        val kneeMedian = history.kneeMedian()
        val dt = if (lastSampleMs < 0) 0.0 else (timestampMs - lastSampleMs) / 1000.0
        previousHip = smoothHip
        previousKnee = smoothKnee
        val alpha = if (dt <= 0.0) 1.0 else (1.0 - Math.exp(-dt / 0.12)).coerceIn(0.2, 0.7)
        smoothHip = if (smoothHip.isNaN()) hipMedian else smoothHip + alpha * (hipMedian - smoothHip)
        smoothKnee = if (smoothKnee.isNaN()) kneeMedian else smoothKnee + alpha * (kneeMedian - smoothKnee)
        val velocity = if (dt in 0.015..0.30 && previousHip.isFinite())
            (previousHip - smoothHip) / dt else 0.0 // Positive means descending.
        val kneeDelta = if (previousKnee.isFinite()) smoothKnee - previousKnee else 0.0
        lastSampleMs = timestampMs
        completedRep = null
        advance(timestampMs, velocity, kneeDelta)
        return SquatExerciseResult(true, phase, reps, if (chosen == 0) "LEFT" else "RIGHT",
            rawHip, smoothHip, smoothKnee, velocity,
            baseline.takeIf { it.isFinite() }, feedback(), completedRep)
    }

    private fun advance(now: Long, velocity: Double, kneeDelta: Double) {
        val descent = if (baseline.isFinite()) baseline - smoothHip else 0.0
        when (phase) {
            SquatExercisePhase.NOT_READY -> {
                readyFrames = if (smoothKnee <= 25.0 && smoothHip >= 0.65) readyFrames + 1 else 0
                if (readyFrames >= READY_FRAMES) {
                    baseline = smoothHip
                    phase = SquatExercisePhase.STANDING
                }
            }
            SquatExercisePhase.STANDING -> {
                if (smoothKnee <= 25.0 && abs(velocity) < 0.09) {
                    baseline += 0.03 * (smoothHip - baseline)
                }
                downFrames = if (velocity > VELOCITY_DEAD_ZONE && kneeDelta > 0.4 &&
                    descent > 0.055 && smoothKnee > 18.0) downFrames + 1 else 0
                if (downFrames >= CONFIRM_FRAMES) {
                    phase = SquatExercisePhase.DESCENDING
                    startMs = now
                    deepest = descent
                    maxFlexion = smoothKnee
                    bottomTimestamp = -1L
                    ascentTimestamp = -1L
                }
            }
            SquatExercisePhase.DESCENDING -> {
                deepest = max(deepest, descent)
                maxFlexion = max(maxFlexion, smoothKnee)
                if (descent < 0.045 && smoothKnee < 28.0) {
                    phase = SquatExercisePhase.STANDING // Aborted shallow movement.
                    downFrames = 0
                } else {
                    bottomFrames = if (deepest >= 0.20 && smoothKnee >= 55.0 &&
                        velocity < VELOCITY_DEAD_ZONE) bottomFrames + 1 else 0
                    if (bottomFrames >= CONFIRM_FRAMES) {
                        phase = SquatExercisePhase.BOTTOM
                        bottomTimestamp = now
                    }
                }
            }
            SquatExercisePhase.BOTTOM -> {
                deepest = max(deepest, descent)
                maxFlexion = max(maxFlexion, smoothKnee)
                upFrames = if (velocity < -VELOCITY_DEAD_ZONE && kneeDelta < -0.4)
                    upFrames + 1 else 0
                if (upFrames >= CONFIRM_FRAMES) {
                    phase = SquatExercisePhase.ASCENDING
                    ascentTimestamp = now
                }
            }
            SquatExercisePhase.ASCENDING -> {
                maxFlexion = max(maxFlexion, smoothKnee)
                finishFrames = if (descent <= 0.075 && smoothKnee <= 28.0)
                    finishFrames + 1 else 0
                if (finishFrames >= FINISH_FRAMES) {
                    if (deepest >= 0.20 && now - startMs >= MIN_REP_MS) {
                        reps++
                        completedRep = SquatRepMetrics(startMs, now, maxFlexion, deepest,
                            descentDurationSeconds = if (bottomTimestamp >= 0)
                                (bottomTimestamp - startMs) / 1000.0 else null,
                            ascentDurationSeconds = if (ascentTimestamp >= 0)
                                (now - ascentTimestamp) / 1000.0 else null)
                    }
                    phase = SquatExercisePhase.STANDING
                    baseline = smoothHip
                    downFrames = 0
                    finishFrames = 0
                }
            }
        }
    }

    private fun quality(points: List<NormalizedLandmark>, offset: Int): Float {
        var minimum = 1f
        for (index in 0..3) {
            val landmarkIndex = when (index) { 0 -> 11; 1 -> 23; 2 -> 25; else -> 27 }
            val p = points[landmarkIndex + offset]
            val confidence = p.visibility().orElse(0f)
            if (!confidence.isFinite() || !p.x().isFinite() || !p.y().isFinite() ||
                p.x() !in 0f..1f || p.y() !in 0f..1f) return 0f
            minimum = min(minimum, confidence)
        }
        return minimum
    }

    private fun invalid(now: Long): SquatExerciseResult {
        onPoseLost(now)
        return SquatExerciseResult(false, SquatExercisePhase.NOT_READY, reps,
            side?.let { if (it == 0) "LEFT" else "RIGHT" }, Double.NaN, Double.NaN,
            Double.NaN, 0.0, baseline.takeIf { it.isFinite() },
            "Stand upright with shoulder, hip, knee and ankle visible")
    }

    private fun feedback() = when (phase) {
        SquatExercisePhase.NOT_READY -> "Stand upright to calibrate"
        SquatExercisePhase.STANDING -> "Ready - squat down"
        SquatExercisePhase.DESCENDING -> "Descending"
        SquatExercisePhase.BOTTOM -> "Bottom"
        SquatExercisePhase.ASCENDING -> "Ascending"
    }
}
