package com.example.posebenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionManagerTest {
    @Test fun countsSetsAndKeepsRealRepMetrics() {
        val session = WorkoutSessionManager(WorkoutExercise(ExerciseType.SQUAT, 3, 2))
        session.start(1_000)
        var time = 1_000L
        for (set in 1..3) {
            for (rep in 1..2) {
                time += 1_000
                val end = time
                session.recordSample(end - 100, rep * .2)
                session.completedRep(end, SquatRepMetrics(end - 900, end, 88.0 + rep,
                    .25 + rep * .01, descentDurationSeconds = .4,
                    ascentDurationSeconds = .5))
                assertEquals(rep, session.currentRep)
            }
            if (set < 3) {
                assertEquals(WorkoutSessionState.SET_COMPLETE, session.state)
                time += 500
                session.startNextSet(time)
                assertEquals(0, session.currentRep)
                assertEquals(set + 1, session.currentSet)
            }
        }
        assertEquals(WorkoutSessionState.WORKOUT_COMPLETE, session.state)
        val result = session.result()
        assertEquals(3, result.completedSets)
        assertEquals(6, result.totalReps)
        assertEquals(3, result.setResults.size)
        assertEquals(6, result.movementSamples.size)
        assertEquals(2, result.setResults[1].repResults[1].repNumber)
        assertTrue((result.setResults[1].repResults[1].metrics as SquatRepMetrics).maxKneeFlexion > 89.0)
    }
}
