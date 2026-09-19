package com.example.posebenchmark

enum class ExerciseType(val label: String) { SQUAT("Squat"), PUSH_UP("Push-Up") }

data class WorkoutExercise(val exerciseType: ExerciseType, val sets: Int, val reps: Int)
data class WorkoutRoutine(val name: String, val exercises: List<WorkoutExercise>)

object DefaultRoutines {
    val today = WorkoutRoutine("Today's Workout", listOf(
        WorkoutExercise(ExerciseType.SQUAT, 3, 10),
        WorkoutExercise(ExerciseType.PUSH_UP, 3, 10)))
}

sealed interface ExerciseRepMetrics
data class SquatRepMetrics(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val maxKneeFlexion: Double,
    val maxHipDepth: Double,
    val maxTorsoLean: Double? = null,
    val descentDurationSeconds: Double?,
    val ascentDurationSeconds: Double?
) : ExerciseRepMetrics {
    val durationSeconds: Double get() = (endTimestamp - startTimestamp) / 1000.0
}

data class PushUpRepMetrics(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val minimumElbowAngle: Double
) : ExerciseRepMetrics {
    val durationSeconds: Double get() = (endTimestamp - startTimestamp) / 1000.0
}

data class RepResult(val setNumber: Int, val repNumber: Int, val metrics: ExerciseRepMetrics)
data class SetResult(
    val setNumber: Int,
    val targetReps: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val repResults: List<RepResult>
) {
    val completedReps: Int get() = repResults.size
    val durationSeconds: Double get() = (endTimestamp - startTimestamp) / 1000.0
}

data class MovementSample(val elapsedSeconds: Double, val depth: Double)
data class WorkoutSessionResult(
    val exercise: ExerciseType,
    val targetSets: Int,
    val targetReps: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val setResults: List<SetResult>,
    val movementSamples: List<MovementSample>
) {
    val completedSets: Int get() = setResults.size
    val totalReps: Int get() = setResults.sumOf { it.completedReps }
    val durationSeconds: Double get() = (endTimestamp - startTimestamp) / 1000.0
}

/** In-memory handoff to ResultsActivity. No frame data or database is retained. */
object WorkoutResultStore { var latest: WorkoutSessionResult? = null }
