package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import org.junit.Assert.*
import org.junit.Test

class VisualCoachingTest {
    private fun point(x: Float, y: Float, visibility: Float = 1f) =
        NormalizedLandmark.create(x, y, 0f, Optional.of(visibility), Optional.of(1f))

    @Test fun pushUpHipTurnsRedOnlyAfterSustainedReliableDeviation() {
        val coaching = VisualCoaching()
        val points = MutableList(33) { point(0f, 0f, 0f) }.also { it[23] = point(.5f, .5f) }
        val visual = PushUpAnalysis(ExerciseTrackingState.READY, BodySide.LEFT,
            guidance = PostureGuidance(
                listOf(JointCorrection(23, PostureVisualState.WRONG, CorrectionDirection.UP)),
                listOf(ConnectionCorrection(11, 23, PostureVisualState.WRONG))))
        val movement = PushUpExerciseResult(true, PushUpSide.LEFT, PushUpPhase.TOP, 0,
            160.0, 0.0, 160.0, 0.0, 0.0, 170.0, true, "")
        assertEquals(PostureVisualState.WARNING,
            coaching.pushUp(points, visual, movement, 100).joints.single().state)
        coaching.pushUp(points, visual, movement, 150)
        assertEquals(PostureVisualState.WRONG,
            coaching.pushUp(points, visual, movement, 200).joints.single().state)
        assertTrue(coaching.pushUp(points, PushUpAnalysis(ExerciseTrackingState.NOT_READY),
            movement, 250).joints.isEmpty())
    }

    @Test fun squatStandingTorsoCueIsLocalizedAndRecovers() {
        val coaching = VisualCoaching()
        val points = MutableList(33) { point(0f, 0f, 0f) }.also {
            it[11] = point(.90f, .20f)
            it[23] = point(.50f, .50f)
            it[25] = point(.50f, .70f)
            it[27] = point(.50f, .90f)
        }
        val result = SquatExerciseResult(true, SquatExercisePhase.STANDING, 0,
            "LEFT", .8, .8, 10.0, 0.0, .8, "",
            torsoInclinationDeg = 53.0)
        coaching.squat(points, 1000, 1000, result, 100)
        coaching.squat(points, 1000, 1000, result, 250)
        val wrong = coaching.squat(points, 1000, 1000, result, 400)
        assertEquals(PostureVisualState.WRONG, wrong.joints.single().state)
        assertEquals(11, wrong.joints.single().landmarkIndex)
        points[11] = point(.50f, .20f)
        val corrected = result.copy(torsoInclinationDeg = 0.0)
        assertEquals(PostureVisualState.WRONG,
            coaching.squat(points, 1000, 1000, corrected, 450).joints.single().state)
        assertTrue(coaching.squat(points, 1000, 1000, corrected, 700).joints.isEmpty())
    }

    @Test fun shallowSquatSelectsHipAndBodyRelativeDownTarget() {
        val coaching = VisualCoaching()
        val points = MutableList(33) { point(0f, 0f, 0f) }.also {
            it[11] = point(.50f, .15f)
            it[23] = point(.55f, .50f)
            it[25] = point(.65f, .67f)
            it[27] = point(.50f, .90f)
        }
        val result = SquatExerciseResult(true, SquatExercisePhase.DESCENDING, 0,
            "LEFT", .6, .6, 48.0, 0.0, .75, "", normalizedDepth = .14,
            torsoDeviationDeg = 5.0, shallowBottom = true, legLengthPx = 500.0)
        val warning = coaching.squat(points, 1000, 1000, result, 100)
        assertEquals(PostureVisualState.WARNING, warning.joints.single().state)
        val wrong = coaching.squat(points, 1000, 1000, result, 400)
        assertEquals(PostureVisualState.WRONG, wrong.joints.single().state)
        assertEquals(23, wrong.joints.single().landmarkIndex)
        assertEquals(CorrectionDirection.DOWN, wrong.joints.single().direction)
        assertTrue(wrong.joints.single().target!!.y > points[23].y())
        assertEquals(listOf(ConnectionCorrection(23, 25, PostureVisualState.WRONG)),
            wrong.connections)
    }
}
