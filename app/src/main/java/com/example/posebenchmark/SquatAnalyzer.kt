package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
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
        const val READY_HOLD_MS = 150L
        const val TRANSITION_HOLD_MS = 50L
        const val FINISH_HOLD_MS = 100L
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
    private var readySinceMs = -1L
    private var downSinceMs = -1L
    private var bottomSinceMs = -1L
    private var upSinceMs = -1L
    private var finishSinceMs = -1L
    private var shallowSinceMs = -1L
    private var baselineTorsoDeg = Double.NaN
    private var currentTorsoDeg = Double.NaN
    private var peakTorsoDeviation = 0.0
    private var pendingGap = false
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
        readySinceMs = -1L
        downSinceMs = -1L
        bottomSinceMs = -1L
        upSinceMs = -1L
        finishSinceMs = -1L
        shallowSinceMs = -1L
        baselineTorsoDeg = Double.NaN
        currentTorsoDeg = Double.NaN
        peakTorsoDeviation = 0.0
        pendingGap = false
    }

    fun onPoseLost(timestampMs: Long) {
        // Preserve phase for a short dropout, but never derive motion across it.
        pendingGap = true
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
        val shoulder = points[11 + chosen]
        val knee = points[25 + chosen]
        val ankle = points[27 + chosen]
        val legLength = SquatGeometry.distance(hip, knee, width, height) +
            SquatGeometry.distance(knee, ankle, width, height)
        if (legLength < height * 0.06) return invalid(timestampMs)
        val rawHip = (ankle.y() - hip.y()).toDouble() * height / legLength
        val rawKnee = 180.0 - SquatGeometry.angle(hip, knee, ankle, width, height)
        val torsoDeg = Math.toDegrees(atan2(
            abs((shoulder.x() - hip.x()).toDouble() * width),
            abs((shoulder.y() - hip.y()).toDouble() * height)))
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
        currentTorsoDeg = torsoDeg
        if (phase == SquatExercisePhase.STANDING && smoothKnee <= 25.0 &&
            abs(velocity) < 0.09) {
            baselineTorsoDeg = if (baselineTorsoDeg.isNaN()) torsoDeg else
                baselineTorsoDeg + .03 * (torsoDeg - baselineTorsoDeg)
        }
        val torsoDeviation = if (baselineTorsoDeg.isFinite())
            abs(torsoDeg - baselineTorsoDeg) else 0.0
        if (phase != SquatExercisePhase.NOT_READY)
            peakTorsoDeviation = max(peakTorsoDeviation, torsoDeviation)
        if (pendingGap) {
            pendingGap = false
        } else advance(timestampMs, velocity, kneeDelta)
        val depth = if (baseline.isFinite()) (baseline - smoothHip).coerceAtLeast(0.0) else 0.0
        val shallow = (phase == SquatExercisePhase.DESCENDING &&
            shallowSinceMs >= 0 && timestampMs - shallowSinceMs >= 150L) ||
            (phase == SquatExercisePhase.ASCENDING && deepest in .10..<.20)
        return SquatExerciseResult(true, phase, reps, if (chosen == 0) "LEFT" else "RIGHT",
            rawHip, smoothHip, smoothKnee, velocity,
            baseline.takeIf { it.isFinite() }, feedback(), completedRep,
            depth, torsoDeviation, torsoDeg, shallow, legLength, timestampMs)
    }

    private fun advance(now: Long, velocity: Double, kneeDelta: Double) {
        val descent = if (baseline.isFinite()) baseline - smoothHip else 0.0
        when (phase) {
            SquatExercisePhase.NOT_READY -> {
                readySinceMs = since(smoothKnee <= 25.0 && smoothHip >= 0.65,
                    now, readySinceMs)
                if (held(readySinceMs, now, READY_HOLD_MS)) {
                    baseline = smoothHip
                    baselineTorsoDeg = currentTorsoDeg
                    phase = SquatExercisePhase.STANDING
                }
            }
            SquatExercisePhase.STANDING -> {
                if (smoothKnee <= 25.0 && abs(velocity) < 0.09) {
                    baseline += 0.03 * (smoothHip - baseline)
                }
                downSinceMs = since(velocity > VELOCITY_DEAD_ZONE && kneeDelta > 0.4 &&
                    descent > 0.055 && smoothKnee > 18.0, now, downSinceMs)
                if (held(downSinceMs, now, TRANSITION_HOLD_MS)) {
                    phase = SquatExercisePhase.DESCENDING
                    startMs = now
                    deepest = descent
                    maxFlexion = smoothKnee
                    peakTorsoDeviation = 0.0
                    bottomTimestamp = -1L
                    ascentTimestamp = -1L
                    shallowSinceMs = -1L
                }
            }
            SquatExercisePhase.DESCENDING -> {
                deepest = max(deepest, descent)
                maxFlexion = max(maxFlexion, smoothKnee)
                if (descent < 0.045 && smoothKnee < 28.0) {
                    phase = SquatExercisePhase.STANDING // Aborted shallow movement.
                    downSinceMs = -1L
                } else {
                    shallowSinceMs = since(descent in .10..<.20 && smoothKnee >= 35.0 &&
                        abs(velocity) < VELOCITY_DEAD_ZONE, now, shallowSinceMs)
                    bottomSinceMs = since(deepest >= 0.20 && smoothKnee >= 55.0 &&
                        velocity < VELOCITY_DEAD_ZONE, now, bottomSinceMs)
                    if (held(bottomSinceMs, now, TRANSITION_HOLD_MS)) {
                        phase = SquatExercisePhase.BOTTOM
                        bottomTimestamp = now
                    } else if (deepest >= .10 && deepest < .20 &&
                        velocity < -VELOCITY_DEAD_ZONE && kneeDelta < -0.4) {
                        upSinceMs = since(true, now, upSinceMs)
                        if (held(upSinceMs, now, TRANSITION_HOLD_MS)) {
                            phase = SquatExercisePhase.ASCENDING
                            ascentTimestamp = now
                        }
                    } else upSinceMs = -1L
                }
            }
            SquatExercisePhase.BOTTOM -> {
                deepest = max(deepest, descent)
                maxFlexion = max(maxFlexion, smoothKnee)
                upSinceMs = since(velocity < -VELOCITY_DEAD_ZONE && kneeDelta < -0.4,
                    now, upSinceMs)
                if (held(upSinceMs, now, TRANSITION_HOLD_MS)) {
                    phase = SquatExercisePhase.ASCENDING
                    ascentTimestamp = now
                }
            }
            SquatExercisePhase.ASCENDING -> {
                maxFlexion = max(maxFlexion, smoothKnee)
                finishSinceMs = since(descent <= 0.075 && smoothKnee <= 28.0,
                    now, finishSinceMs)
                if (held(finishSinceMs, now, FINISH_HOLD_MS)) {
                    if (deepest >= 0.20 && now - startMs >= MIN_REP_MS) {
                        reps++
                        completedRep = SquatRepMetrics(startMs, now, maxFlexion, deepest,
                            maxTorsoLean = peakTorsoDeviation,
                            descentDurationSeconds = if (bottomTimestamp >= 0)
                                (bottomTimestamp - startMs) / 1000.0 else null,
                            ascentDurationSeconds = if (ascentTimestamp >= 0)
                                (now - ascentTimestamp) / 1000.0 else null)
                    }
                    phase = SquatExercisePhase.STANDING
                    baseline = smoothHip
                    downSinceMs = -1L
                    finishSinceMs = -1L
                }
            }
        }
    }

    private fun since(condition: Boolean, now: Long, previous: Long): Long =
        if (!condition) -1L else if (previous < 0) now else previous

    private fun held(since: Long, now: Long, duration: Long) =
        since >= 0 && now - since >= duration

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
