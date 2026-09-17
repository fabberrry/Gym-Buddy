package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

enum class SquatExercisePhase { NOT_READY, STANDING, DESCENDING, BOTTOM, ASCENDING }

data class SquatExerciseResult(
    val bodyVisible: Boolean,
    val phase: SquatExercisePhase,
    val repCount: Int,
    val selectedSide: String?,
    val rawHipSignal: Double,
    val smoothedHipSignal: Double,
    val kneeFlexion: Double,
    val movementVelocity: Double,
    val standingBaseline: Double?,
    val feedback: String,
    val completedRep: SquatRepMetrics? = null
)

/** Keeps the existing exercise entry point while movement analysis stays independent of UI. */
class SquatExercise {
    private val analyzer = SquatAnalyzer()

    fun analyze(landmarks: List<NormalizedLandmark>, width: Int, height: Int,
                timestampMs: Long): SquatExerciseResult =
        analyzer.analyze(landmarks, width, height, timestampMs)

    fun onPoseLost(timestampMs: Long) { analyzer.onPoseLost(timestampMs) }

    fun resetSession() { analyzer.resetSession() }
}
