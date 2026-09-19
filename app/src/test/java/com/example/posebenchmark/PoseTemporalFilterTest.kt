package com.example.posebenchmark

import org.junit.Assert.*
import org.junit.Test

class PoseTemporalFilterTest {
    private fun pose(time: Long, hipX: Float = .5f): PoseObservation {
        val points = MutableList(33) { PosePoint(.5f, .5f, 1f) }
        points[11] = PosePoint(.5f, .15f, 1f)
        points[23] = PosePoint(hipX, .45f, 1f)
        points[27] = PosePoint(.5f, .90f, 1f)
        return PoseObservation(time, 1000, 1000, points)
    }

    @Test fun severeSingleFrameSpikeIsRejectedWithoutMovingTheJoint() {
        val filter = PoseTemporalFilter()
        filter.process(pose(100))
        val spike = filter.process(pose(150, .95f))!!
        assertEquals(0f, spike.points[23].visibility)
        assertEquals(.5f, spike.points[23].x, .0001f)
    }

    @Test fun ordinaryMotionIsCausallySmoothedAndPreserved() {
        val filter = PoseTemporalFilter()
        filter.process(pose(100))
        val moved = filter.process(pose(200, .53f))!!
        assertTrue(moved.points[23].x > .5f)
        assertTrue(moved.points[23].x < .53f)
        assertEquals(1f, moved.points[23].visibility, .0001f)
    }
}
