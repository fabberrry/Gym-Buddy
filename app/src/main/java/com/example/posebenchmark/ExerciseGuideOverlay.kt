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
        val demonstrate = display.stage == SetupStage.DEMO ||
            display.hint == SetupHint.CAMERA_VIEW || display.hint == SetupHint.BODY_NOT_VISIBLE
        val progress = if (demonstrate)
            (sin(android.os.SystemClock.uptimeMillis() / 800.0) * 0.5 + 0.5).toFloat() else 0f
        val good = display.stage == SetupStage.COUNTDOWN || display.hint == SetupHint.READY
        line.color = if (good) 0xcc6fe0b5.toInt() else 0xbb6fe0ff.toInt()
        thin.color = line.color
        fill.color = line.color

        val cameraY = if (profile.exercise == CoachingExercise.SQUAT)
            cy - span * 0.02f else cy
        // The preview is already the phone's point of view. A camera icon beside
        // the person incorrectly suggests that they should face the lens.
        val floorY = if (profile.exercise == CoachingExercise.SQUAT) cy + span * 0.73f
            else cy + span * 0.36f
        canvas.drawLine(w * 0.12f, floorY, w * 0.88f, floorY, thin)
        drawGhost(canvas, cx, cy, span, progress)
        drawInstructions(canvas, w, h, demonstrate, progress)
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
        if (demonstrate) postInvalidateDelayed(33)
    }

    private fun drawInstructions(canvas: Canvas, w: Float, h: Float, demo: Boolean, move: Float) {
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xee101b29.toInt() }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = minOf(16f * density, w / 25f)
        }
        val top = h * .13f
        val row = text.textSize * 1.6f
        canvas.drawRoundRect(w * .035f, top, w * .965f, top + row * 4.6f,
            14f * density, 14f * density, card)
        text.isFakeBoldText = true
        canvas.drawText("SIDE VIEW • Either side is OK", w / 2, top + row, text)
        text.isFakeBoldText = false
        canvas.drawText("Turn 90°: shoulder and hip toward phone", w / 2, top + row * 2, text)
        val placement = if (profile.exercise == CoachingExercise.SQUAT)
            "Prop phone at hip height • Step back" else "Prop phone low • Show your whole body"
        canvas.drawText(placement, w / 2, top + row * 3, text)
        canvas.drawText("Keep head and both feet inside the picture", w / 2, top + row * 4, text)
        val message = when {
            display.stage == SetupStage.COUNTDOWN -> "Position found • Hold still"
            display.hint == SetupHint.TOO_CLOSE -> "Step farther back"
            display.hint == SetupHint.TOO_FAR -> "Move a little closer"
            display.hint == SetupHint.MOVE_LEFT -> "Move left in the picture"
            display.hint == SetupHint.MOVE_RIGHT -> "Move right in the picture"
            display.hint == SetupHint.TURN_SIDEWAYS -> "Turn sideways • Face left or right"
            demo && profile.exercise == CoachingExercise.SQUAT ->
                if (move > .55f) "DEMO: Sit hips back • Bend your knees" else "DEMO: Stand tall • Face to the side →"
            demo -> "DEMO: Side view • Lower and push up"
            else -> "Match the side view • Hold your start position"
        }
        val bottom = h * .82f
        canvas.drawRoundRect(w * .035f, bottom, w * .965f, bottom + row * 2.5f,
            14f * density, 14f * density, card)
        text.color = 0xff6fe0b5.toInt()
        canvas.drawText(message, w / 2, bottom + row, text)
        text.color = Color.WHITE
        canvas.drawText("The model shows the angle, not your exact size", w / 2, bottom + row * 2, text)
    }

    private fun drawCamera(canvas: Canvas, x: Float, y: Float) {
        val r = 15f * density
        canvas.drawRoundRect(x - 2.0f * r, y - r, x + 2.0f * r, y + r, r * 0.3f, r * 0.3f, line)
        canvas.drawCircle(x, y, r * 0.58f, thin)
        canvas.drawLine(x + 2.0f * r, y - r * 0.4f, x + 3.0f * r, y - r, thin)
        canvas.drawLine(x + 2.0f * r, y + r * 0.4f, x + 3.0f * r, y + r, thin)
    }

    private fun drawGhost(canvas: Canvas, cx: Float, cy: Float, span: Float, move: Float) {
        if (profile.exercise == CoachingExercise.SQUAT) {
            drawSquatPerson(canvas, cx, cy, span, move)
            return
        }
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

    /** Filled side-profile athlete with a visible nose, clothing and planted feet. */
    private fun drawSquatPerson(canvas: Canvas, cx: Float, cy: Float, span: Float, bend: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(span, span)
        val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffedbd98.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val shirt = Paint(skin).apply { color = 0xff6fe0b5.toInt() }
        val pants = Paint(skin).apply { color = 0xff527ab5.toInt() }
        val shoe = Paint(skin).apply { color = 0xffedf3ff.toInt() }
        val hipX = -.02f - .25f * bend
        val hipY = .03f + .34f * bend
        val shoulderX = -.04f + .10f * bend
        val shoulderY = -.43f + .36f * bend
        val kneeX = .04f + .20f * bend
        val kneeY = .37f + .04f * bend
        fun limb(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, paint: Paint) {
            paint.strokeWidth = width
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        // Far leg is offset and darker so the profile reads as a human body.
        val far = Paint(pants).apply { color = 0xff354e78.toInt() }
        limb(hipX - .04f, hipY, kneeX - .09f, kneeY, .13f, far)
        limb(kneeX - .09f, kneeY, -.06f, .68f, .09f, far)
        limb(-.09f, .71f, .06f, .71f, .065f, shoe)
        limb(hipX, hipY, kneeX, kneeY, .16f, pants)
        limb(kneeX, kneeY, .04f, .68f, .105f, pants)
        limb(.02f, .71f, .18f, .71f, .07f, shoe)
        val torso = Path().apply {
            moveTo(shoulderX - .105f, shoulderY)
            quadTo(shoulderX + .04f, shoulderY - .08f, shoulderX + .115f, shoulderY + .02f)
            lineTo(hipX + .11f, hipY + .025f)
            quadTo(hipX, hipY + .09f, hipX - .11f, hipY + .02f)
            close()
        }
        canvas.drawPath(torso, shirt)
        limb(shoulderX + .01f, shoulderY - .02f, shoulderX + .01f, shoulderY - .12f, .07f, skin)
        canvas.drawOval(shoulderX - .08f, shoulderY - .29f,
            shoulderX + .09f, shoulderY - .09f, skin)
        val nose = Path().apply {
            moveTo(shoulderX + .065f, shoulderY - .22f)
            lineTo(shoulderX + .13f, shoulderY - .17f)
            lineTo(shoulderX + .06f, shoulderY - .15f); close()
        }
        canvas.drawPath(nose, skin)
        val hair = Paint(skin).apply { color = 0xff263345.toInt() }
        canvas.drawArc(shoulderX - .085f, shoulderY - .30f,
            shoulderX + .085f, shoulderY - .12f, 160f, 210f, true, hair)
        canvas.drawCircle(shoulderX + .065f, shoulderY - .215f, .011f, hair)
        val elbowX = shoulderX + .15f + .10f * bend
        val elbowY = shoulderY + .20f - .13f * bend
        limb(shoulderX + .035f, shoulderY + .025f, elbowX, elbowY, .078f, skin)
        limb(elbowX, elbowY, elbowX + .17f, elbowY - .055f, .065f, skin)
        canvas.restore()
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
