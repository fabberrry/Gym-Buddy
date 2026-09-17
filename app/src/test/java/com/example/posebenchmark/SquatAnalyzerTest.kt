package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional

class SquatAnalyzerTest {
    private fun point(x: Float, y: Float, visibility: Float = 1f) =
        NormalizedLandmark.create(x, y, 0f, Optional.of(visibility), Optional.of(1f))

    private fun pose(depth: Float): List<NormalizedLandmark> {
        val points = MutableList(33) { point(0f, 0f, 0f) }
        points[11] = point(.5f, .15f)
        points[23] = point(.5f, .25f + .40f * depth)
        points[25] = point(.5f + .18f * depth, .58f + .14f * depth)
        points[27] = point(.5f, .90f)
        return points
    }

    @Test fun geometryUsesFrameAspectRatio() {
        val a = point(0f, 0f)
        val b = point(1f, 0f)
        val c = point(0f, 1f)
        assertEquals(100.0, SquatGeometry.distance(a, b, 100, 200), 0.001)
        assertEquals(200.0, SquatGeometry.distance(a, c, 100, 200), 0.001)
    }

    @Test fun fullSquatCountsOnceAndIdleDoesNot() {
        val analyzer = SquatAnalyzer()
        var time = 0L
        fun sample(depth: Float): SquatExerciseResult {
            time += 50
            return analyzer.analyze(pose(depth), 720, 1280, time)
        }
        repeat(10) { sample(0f) }
        assertEquals(SquatExercisePhase.STANDING, sample(0f).phase)
        repeat(10) { sample(if (it % 2 == 0) .01f else 0f) }
        assertEquals(0, sample(0f).repCount)
        val seen = mutableSetOf<SquatExercisePhase>()
        var completed: SquatRepMetrics? = null
        fun capture(result: SquatExerciseResult) {
            seen += result.phase
            if (result.completedRep != null) completed = result.completedRep
        }
        for (i in 1..24) capture(sample(i / 24f))
        repeat(6) { capture(sample(1f)) }
        for (i in 23 downTo 0) capture(sample(i / 24f))
        repeat(6) { capture(sample(0f)) }
        assertTrue(seen.containsAll(listOf(SquatExercisePhase.DESCENDING,
            SquatExercisePhase.BOTTOM, SquatExercisePhase.ASCENDING)))
        assertEquals(1, sample(0f).repCount)
        assertTrue(completed != null)
        assertTrue(completed!!.durationSeconds >= .7)
        assertTrue(completed!!.maxKneeFlexion >= 55.0)
        assertTrue(completed!!.maxHipDepth >= .2)
        repeat(20) { assertEquals(1, sample(0f).repCount) }
    }

    @Test fun lossCancelsPendingRep() {
        val analyzer = SquatAnalyzer()
        var time = 0L
        repeat(10) { time += 50; analyzer.analyze(pose(0f), 720, 1280, time) }
        repeat(24) { time += 50; analyzer.analyze(pose((it + 1) / 24f), 720, 1280, time) }
        time += 500
        analyzer.onPoseLost(time)
        repeat(30) { time += 50; analyzer.analyze(pose(0f), 720, 1280, time) }
        assertEquals(0, analyzer.analyze(pose(0f), 720, 1280, time + 50).repCount)
    }

    @Test fun partialBendAndStartingAtBottomDoNotCount() {
        val analyzer = SquatAnalyzer()
        var time = 0L
        repeat(10) { time += 50; assertEquals(SquatExercisePhase.NOT_READY,
            analyzer.analyze(pose(1f), 720, 1280, time).phase) }
        repeat(12) { time += 50; analyzer.analyze(pose(0f), 720, 1280, time) }
        for (i in 1..12) { time += 50; analyzer.analyze(pose(i / 12f * .35f), 720, 1280, time) }
        for (i in 11 downTo 0) { time += 50; analyzer.analyze(pose(i / 12f * .35f), 720, 1280, time) }
        repeat(10) { time += 50; assertEquals(0,
            analyzer.analyze(pose(0f), 720, 1280, time).repCount) }
    }
}
