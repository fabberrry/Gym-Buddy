package com.example.posebenchmark

enum class WorkoutSessionState { NOT_STARTED, ACTIVE_SET, SET_COMPLETE, WORKOUT_COMPLETE }

/** Owns workout progress. It knows nothing about MediaPipe landmarks or squat phases. */
class WorkoutSessionManager(private val plan: WorkoutExercise) {
    init { require(plan.sets in 1..20 && plan.reps in 1..100) }

    var state = WorkoutSessionState.NOT_STARTED
        private set
    var currentSet = 1
        private set
    var currentRep = 0
        private set

    private var sessionStart = 0L
    private var setStart = 0L
    private var lastSampleTime = -1L
    private val setResults = mutableListOf<SetResult>()
    private val currentReps = mutableListOf<RepResult>()
    private val samples = ArrayDeque<MovementSample>()

    fun start(timestampMs: Long) {
        check(state == WorkoutSessionState.NOT_STARTED)
        sessionStart = timestampMs
        setStart = timestampMs
        state = WorkoutSessionState.ACTIVE_SET
    }

    fun recordSample(timestampMs: Long, depth: Double) {
        if (state != WorkoutSessionState.ACTIVE_SET || !depth.isFinite()) return
        if (lastSampleTime >= 0 && timestampMs - lastSampleTime < 100) return
        lastSampleTime = timestampMs
        // At most six minutes at 10 Hz; retain the latest portion of a longer workout.
        if (samples.size == 3600) samples.removeFirst()
        samples.addLast(MovementSample((timestampMs - sessionStart) / 1000.0, depth))
    }

    fun completedRep(timestampMs: Long, metrics: ExerciseRepMetrics) {
        if (state != WorkoutSessionState.ACTIVE_SET) return
        currentRep++
        currentReps += RepResult(currentSet, currentRep, metrics)
        if (currentRep == plan.reps) {
            setResults += SetResult(currentSet, plan.reps, setStart, timestampMs, currentReps.toList())
            currentReps.clear()
            state = if (currentSet == plan.sets) WorkoutSessionState.WORKOUT_COMPLETE
                    else WorkoutSessionState.SET_COMPLETE
        }
    }

    fun startNextSet(timestampMs: Long) {
        check(state == WorkoutSessionState.SET_COMPLETE)
        currentSet++
        currentRep = 0
        setStart = timestampMs
        state = WorkoutSessionState.ACTIVE_SET
    }

    fun result(): WorkoutSessionResult {
        check(state == WorkoutSessionState.WORKOUT_COMPLETE)
        return WorkoutSessionResult(plan.exerciseType, plan.sets, plan.reps,
            sessionStart, setResults.last().endTimestamp, setResults.toList(), samples.toList())
    }
}
