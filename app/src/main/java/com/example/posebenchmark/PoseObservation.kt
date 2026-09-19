package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import kotlin.math.exp
import kotlin.math.hypot

/** Camera independent input. Coordinates are normalized to the rotated, cropped image. */
data class PosePoint(val x: Float, val y: Float, val visibility: Float)

data class PoseObservation(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val points: List<PosePoint>
) {
    companion object {
        fun fromMediaPipe(timestampMs: Long, width: Int, height: Int,
                          landmarks: List<NormalizedLandmark>) = PoseObservation(
            timestampMs, width, height,
            landmarks.map { PosePoint(it.x(), it.y(), it.visibility().orElse(0f)) }
        )
    }

    fun toMediaPipe(): List<NormalizedLandmark> = points.map {
        NormalizedLandmark.create(it.x, it.y, 0f, Optional.of(it.visibility), Optional.of(1f))
    }
}

/** Causal point filter. A severe single-frame jump becomes low confidence, never a correction. */
class PoseTemporalFilter {
    companion object {
        const val MIN_VISIBILITY = 0.60f
        const val MAX_BODY_LENGTHS_PER_SECOND = 4.0f
        const val MAX_SAMPLE_GAP_MS = 350L
        private const val TIME_CONSTANT_MS = 80.0
    }

    private val previous = arrayOfNulls<PosePoint>(33)
    private var lastTimestamp = -1L

    fun reset() {
        previous.fill(null)
        lastTimestamp = -1L
    }

    fun process(input: PoseObservation): PoseObservation? {
        if (input.width <= 0 || input.height <= 0 || input.points.size < 33 ||
            input.timestampMs <= lastTimestamp) return null
        val dtMs = input.timestampMs - lastTimestamp
        if (lastTimestamp < 0 || dtMs > MAX_SAMPLE_GAP_MS) previous.fill(null)
        val bodySpan = bodySpan(input).coerceAtLeast(minOf(input.width, input.height) * 0.25f)
        val dtSeconds = dtMs / 1000f
        val alpha = if (lastTimestamp < 0) 1f else
            (1.0 - exp(-dtMs / TIME_CONSTANT_MS)).toFloat().coerceIn(0.25f, 0.85f)
        val output = ArrayList<PosePoint>(input.points.size)
        input.points.forEachIndexed { index, raw ->
            val old = previous.getOrNull(index)
            val valid = raw.x.isFinite() && raw.y.isFinite() &&
                raw.x in 0f..1f && raw.y in 0f..1f &&
                raw.visibility.isFinite() && raw.visibility >= MIN_VISIBILITY
            val point = when {
                !valid -> PosePoint(raw.x, raw.y, 0f)
                old == null || index >= previous.size -> raw
                dtSeconds > 0f && hypot((raw.x - old.x) * input.width,
                    (raw.y - old.y) * input.height) / (bodySpan * dtSeconds) >
                    MAX_BODY_LENGTHS_PER_SECOND -> PosePoint(old.x, old.y, 0f)
                else -> PosePoint(old.x + alpha * (raw.x - old.x),
                    old.y + alpha * (raw.y - old.y), raw.visibility)
            }
            output += point
            if (index < previous.size && point.visibility >= MIN_VISIBILITY)
                previous[index] = point
        }
        lastTimestamp = input.timestampMs
        return input.copy(points = output)
    }

    private fun bodySpan(input: PoseObservation): Float {
        val shoulder = input.points.getOrNull(11) ?: return 0f
        val ankle = input.points.getOrNull(27) ?: return 0f
        if (shoulder.visibility < MIN_VISIBILITY || ankle.visibility < MIN_VISIBILITY) return 0f
        return hypot((shoulder.x - ankle.x) * input.width,
            (shoulder.y - ankle.y) * input.height)
    }
}
