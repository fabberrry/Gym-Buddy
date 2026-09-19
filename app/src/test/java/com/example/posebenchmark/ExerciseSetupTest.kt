package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import org.junit.Assert.*
import org.junit.Test

class ExerciseSetupTest {
    private fun point(x: Float, y: Float, visibility: Float = 1f) =
        NormalizedLandmark.create(x, y, 0f, Optional.of(visibility), Optional.of(1f))

    private fun squatPose(): MutableList<NormalizedLandmark> =
        MutableList(33) { point(0f, 0f, 0f) }.also {
            it[11] = point(.5f, .15f)
            it[23] = point(.5f, .45f)
            it[25] = point(.5f, .68f)
            it[27] = point(.5f, .90f)
        }

    private fun pushUpPose(): MutableList<NormalizedLandmark> =
        MutableList(33) { point(0f, 0f, 0f) }.also {
            it[11] = point(.20f, .40f)
            it[13] = point(.23f, .55f)
            it[15] = point(.22f, .72f)
            it[23] = point(.50f, .45f)
            it[27] = point(.80f, .50f)
        }

    private fun activate(controller: ExerciseSetupController,
                         profile: ExerciseProfile, pose: List<NormalizedLandmark>) {
        controller.update(profile, pose, 1000, 1000, 0)
        assertEquals(SetupStage.ALIGN,
            controller.update(profile, pose, 1000, 1000, 1900).stage)
        assertEquals(SetupStage.ALIGN,
            controller.update(profile, pose, 1000, 1000, 3000).stage)
        assertEquals(SetupStage.COUNTDOWN,
            controller.update(profile, pose, 1000, 1000, 3100).stage)
        assertTrue(controller.update(profile, pose, 1000, 1000, 5200).canScore)
    }

    @Test fun readinessUsesElapsedTimeAndDoesNotTriggerFromOneFrame() {
        val controller = ExerciseSetupController()
        val pose = squatPose()
        assertEquals(SetupStage.DEMO,
            controller.update(ExerciseProfile.SQUAT, pose, 1000, 1000, 0).stage)
        assertEquals(SetupStage.ALIGN,
            controller.update(ExerciseProfile.SQUAT, pose, 1000, 1000, 1900).stage)
        assertFalse(controller.update(ExerciseProfile.SQUAT, pose, 1000, 1000, 3000).canScore)
        assertEquals(SetupStage.COUNTDOWN,
            controller.update(ExerciseProfile.SQUAT, pose, 1000, 1000, 3100).stage)
        assertTrue(controller.update(ExerciseProfile.SQUAT, pose, 1000, 1000, 5200).canScore)
    }

    @Test fun framingOrientationAndTemporaryLossAreGated() {
        val controller = ExerciseSetupController()
        val pose = pushUpPose()
        controller.update(ExerciseProfile.PUSH_UP, pose, 1000, 1000, 0)
        val cropped = pushUpPose().also { it[27] = point(.98f, .50f) }
        assertEquals(SetupHint.TOO_CLOSE,
            controller.update(ExerciseProfile.PUSH_UP, cropped, 1000, 1000, 1900).hint)
        val tooFar = pushUpPose().also {
            for (i in listOf(11, 13, 15, 23, 27)) {
                val p = it[i]
                it[i] = point(.5f + (p.x() - .5f) * .35f,
                    .5f + (p.y() - .5f) * .35f)
            }
        }
        assertEquals(SetupHint.TOO_FAR,
            controller.update(ExerciseProfile.PUSH_UP, tooFar, 1000, 1000, 1950).hint)
        val shifted = pushUpPose().also {
            for (i in listOf(11, 13, 15, 23, 27)) {
                val p = it[i]
                it[i] = point(p.x() - .13f, p.y())
            }
        }
        assertEquals(SetupHint.MOVE_RIGHT,
            controller.update(ExerciseProfile.PUSH_UP, shifted, 1000, 1000, 2000).hint)
        activateAfterDemo(controller, ExerciseProfile.PUSH_UP, pose, 2100)
        assertEquals(SetupStage.TEMPORARILY_LOST,
            controller.update(ExerciseProfile.PUSH_UP, null, 1000, 1000, 5500).stage)
        assertTrue(controller.update(ExerciseProfile.PUSH_UP, pose, 1000, 1000, 5700).canScore)
        controller.update(ExerciseProfile.PUSH_UP, null, 1000, 1000, 6000)
        assertEquals(SetupStage.ALIGN,
            controller.update(ExerciseProfile.PUSH_UP, null, 1000, 1000, 6400).stage)
    }

    private fun activateAfterDemo(controller: ExerciseSetupController,
                                  profile: ExerciseProfile, pose: List<NormalizedLandmark>,
                                  start: Long) {
        controller.update(profile, pose, 1000, 1000, start)
        controller.update(profile, pose, 1000, 1000, start + 1200)
        assertTrue(controller.update(profile, pose, 1000, 1000, start + 3300).canScore)
    }

    @Test fun orientationAndEitherSideAreHandledWithoutOscillation() {
        val controller = ExerciseSetupController()
        val front = squatPose().also {
            it[12] = point(.78f, .15f)
            it[24] = point(.78f, .45f)
        }
        controller.update(ExerciseProfile.SQUAT, front, 1000, 1000, 0)
        assertEquals(SetupHint.TURN_SIDEWAYS,
            controller.update(ExerciseProfile.SQUAT, front, 1000, 1000, 1900).hint)

        val rightController = ExerciseSetupController()
        val right = squatPose().also {
            for (i in listOf(11, 23, 25, 27)) {
                val p = it[i]
                it[i + 1] = point(p.x(), p.y())
                it[i] = point(p.x(), p.y(), .4f)
            }
        }
        rightController.update(ExerciseProfile.SQUAT, right, 1000, 1000, 0)
        assertEquals(1, rightController.update(ExerciseProfile.SQUAT,
            right, 1000, 1000, 1900).selectedSide)
    }

    @Test fun activeSquatMovementDoesNotReapplyStartTemplate() {
        val controller = ExerciseSetupController()
        val start = squatPose()
        activate(controller, ExerciseProfile.SQUAT, start)
        val lowered = squatPose().also {
            it[23] = point(.58f, .56f)
            it[25] = point(.65f, .66f)
        }
        assertTrue(controller.update(ExerciseProfile.SQUAT,
            lowered, 1000, 1000, 5250).canScore)
    }
}
