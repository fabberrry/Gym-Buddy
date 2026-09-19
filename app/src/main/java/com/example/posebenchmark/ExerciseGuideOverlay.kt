package com.example.posebenchmark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.sin

/** Visual camera setup guide. The ghost is illustrative, not a fixed body template. */
class ExerciseGuideOverlay(context: Context) : View(context) {
    private var profile = ExerciseProfile.SQUAT
    private var display = SetupDisplay(SetupStage.DEMO, SetupHint.CAMERA_VIEW)
    private var personScale = 1f
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xbb6fe0ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x886fe0ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val sightLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x886fe0ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x886fe0ff.toInt()
        style = Paint.Style.FILL
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 82f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun show(profile: ExerciseProfile, display: SetupDisplay, detectedBodyScale: Float = 1f) {
        this.profile = profile
        this.display = display
        personScale = detectedBodyScale.coerceIn(0.75f, 1.25f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (display.stage == SetupStage.ACTIVE) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * 0.5f
        val cy = h * 0.53f
        val span = minOf(w * 0.62f, h * 0.56f) * personScale
        val progress = if (display.stage == SetupStage.DEMO)
            (sin(android.os.SystemClock.uptimeMillis() / 380.0) * 0.5 + 0.5).toFloat() else 0f
        val good = display.stage == SetupStage.COUNTDOWN || display.hint == SetupHint.READY
        line.color = if (good) 0xcc6fe0b5.toInt() else 0xbb6fe0ff.toInt()
        thin.color = line.color
        fill.color = line.color

        val cameraY = if (profile.exercise == CoachingExercise.SQUAT)
            cy - span * 0.02f else cy
        drawCamera(canvas, w * 0.13f, cameraY)
        canvas.drawLine(w * 0.18f, cameraY, cx, cameraY, sightLine)
        val floorY = if (profile.exercise == CoachingExercise.SQUAT) cy + span * 0.73f
            else cy + span * 0.36f
        canvas.drawLine(w * 0.12f, floorY, w * 0.88f, floorY, thin)
        drawGhost(canvas, cx, cy, span, progress)
        if (display.stage != SetupStage.DEMO) {
            val radius = span * .79f
            val oldWidth = thin.strokeWidth
            thin.strokeWidth = 5f * density
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                -90f, 360f * display.alignmentScore.coerceIn(0f, 1f), false, thin)
            thin.strokeWidth = oldWidth
        }

        when (display.hint) {
            SetupHint.TOO_CLOSE -> {
                drawArrow(canvas, w * 0.18f, cy, w * 0.06f, cy)
                drawArrow(canvas, w * 0.82f, cy, w * 0.94f, cy)
            }
            SetupHint.TOO_FAR -> {
                drawArrow(canvas, w * 0.06f, cy, w * 0.22f, cy)
                drawArrow(canvas, w * 0.94f, cy, w * 0.78f, cy)
            }
            SetupHint.MOVE_LEFT -> drawArrow(canvas, cx, cy, cx - span * .36f, cy)
            SetupHint.MOVE_RIGHT -> drawArrow(canvas, cx, cy, cx + span * .36f, cy)
            SetupHint.BODY_NOT_VISIBLE -> {
                canvas.drawRoundRect(cx - span * .42f, cy - span * .66f,
                    cx + span * .42f, cy + span * .70f,
                    16f * density, 16f * density, sightLine)
            }
            SetupHint.TURN_SIDEWAYS -> {
                canvas.drawArc(cx - span * 0.62f, cy - span * 0.56f,
                    cx + span * 0.62f, cy + span * 0.56f, 220f, 260f, false, line)
                drawArrow(canvas, cx + span * 0.34f, cy - span * 0.45f,
                    cx + span * 0.18f, cy - span * 0.56f)
            }
            SetupHint.HOLD_STILL, SetupHint.READY -> {
                canvas.drawCircle(cx, cy, span * 0.80f, thin)
            }
            else -> Unit
        }
        if (display.stage == SetupStage.COUNTDOWN && display.countdown > 0) {
            val x = cx
            val y = h * 0.19f
            canvas.drawCircle(x, y, 53f * density, fill)
            canvas.drawText(display.countdown.toString(), x, y + 28f * density, number)
        }
        if (display.stage == SetupStage.DEMO) postInvalidateDelayed(50)
    }

    private fun drawCamera(canvas: Canvas, x: Float, y: Float) {
        val r = 15f * density
        canvas.drawRoundRect(x - 2.0f * r, y - r, x + 2.0f * r, y + r, r * 0.3f, r * 0.3f, line)
        canvas.drawCircle(x, y, r * 0.58f, thin)
        canvas.drawLine(x + 2.0f * r, y - r * 0.4f, x + 3.0f * r, y - r, thin)
        canvas.drawLine(x + 2.0f * r, y + r * 0.4f, x + 3.0f * r, y + r, thin)
    }

    private fun drawGhost(canvas: Canvas, cx: Float, cy: Float, span: Float, move: Float) {
        // Coordinates are a simple side-view illustration; the user aligns the
        // start position and framing, not individual pixel-perfect joints.
        val joints = if (profile.exercise == CoachingExercise.SQUAT) {
            val bend = move * 0.13f
            arrayOf(
                point(cx - span * 0.04f, cy - span * (0.52f - bend)), // shoulder
                point(cx + span * 0.03f, cy + span * (-0.02f + bend)), // hip
                point(cx + span * (0.08f + bend), cy + span * (0.36f + bend)), // knee
                point(cx + span * 0.04f, cy + span * 0.72f), // ankle
                point(cx + span * 0.20f, cy - span * (0.27f - bend)), // elbow
                point(cx + span * 0.30f, cy - span * (0.23f - bend)) // wrist
            )
        } else {
            val drop = move * 0.17f
            arrayOf(
                point(cx - span * 0.49f, cy - span * (0.15f - drop)), // shoulder
                point(cx + span * 0.06f, cy - span * (0.06f - drop)), // hip
                point(cx + span * 0.50f, cy + span * 0.23f), // knee/ankle
                point(cx + span * 0.66f, cy + span * 0.23f),
                point(cx - span * (0.36f + move * 0.08f), cy + span * 0.10f), // elbow
                point(cx - span * 0.45f, cy + span * 0.34f) // wrist
            )
        }
        val links = arrayOf(0 to 1, 1 to 2, 2 to 3, 0 to 4, 4 to 5)
        for ((a, b) in links) canvas.drawLine(joints[a][0], joints[a][1],
            joints[b][0], joints[b][1], line)
        for (joint in joints) canvas.drawCircle(joint[0], joint[1], 6f * density, fill)
        canvas.drawCircle(joints[0][0], joints[0][1] - 19f * density, 16f * density, line)
    }

    private fun point(x: Float, y: Float) = floatArrayOf(x, y)

    private fun drawArrow(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val length = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / length
        val uy = dy / length
        val head = 18f * density
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
            moveTo(toX, toY)
            lineTo(toX - ux * head - uy * head * 0.55f,
                toY - uy * head + ux * head * 0.55f)
            moveTo(toX, toY)
            lineTo(toX - ux * head + uy * head * 0.55f,
                toY - uy * head - ux * head * 0.55f)
        }
        canvas.drawPath(path, line)
    }
}
