package com.example.posebenchmark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class SkeletonOverlay(context: Context) : View(context) {

    private var landmarks: List<NormalizedLandmark> = emptyList()

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    // MediaPipe Pose landmark connections
    private val connections = listOf(

        // Face
        0 to 1,
        1 to 2,
        2 to 3,
        3 to 7,

        0 to 4,
        4 to 5,
        5 to 6,
        6 to 8,

        9 to 10,

        // Shoulders
        11 to 12,

        // Left arm
        11 to 13,
        13 to 15,

        // Right arm
        12 to 14,
        14 to 16,

        // Left hand
        15 to 17,
        15 to 19,
        15 to 21,
        17 to 19,

        // Right hand
        16 to 18,
        16 to 20,
        16 to 22,
        18 to 20,

        // Torso
        11 to 23,
        12 to 24,
        23 to 24,

        // Left leg
        23 to 25,
        25 to 27,
        27 to 29,
        29 to 31,
        27 to 31,

        // Right leg
        24 to 26,
        26 to 28,
        28 to 30,
        30 to 32,
        28 to 32
    )

    fun setLandmarks(
        newLandmarks: List<NormalizedLandmark>
    ) {
        landmarks = newLandmarks

        // Trigger redraw
        postInvalidate()
    }

    fun clear() {
        landmarks = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (landmarks.isEmpty()) {
            return
        }

        // Draw skeleton lines
        for ((startIndex, endIndex) in connections) {

            if (
                startIndex >= landmarks.size ||
                endIndex >= landmarks.size
            ) {
                continue
            }

            val start = landmarks[startIndex]
            val end = landmarks[endIndex]

            val startX = start.x() * width
            val startY = start.y() * height

            val endX = end.x() * width
            val endY = end.y() * height

            canvas.drawLine(
                startX,
                startY,
                endX,
                endY,
                linePaint
            )
        }

        // Draw landmark dots
        for (landmark in landmarks) {

            val x = landmark.x() * width
            val y = landmark.y() * height

            canvas.drawCircle(
                x,
                y,
                8f,
                pointPaint
            )
        }
    }
}