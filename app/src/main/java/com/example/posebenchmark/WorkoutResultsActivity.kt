package com.example.posebenchmark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import java.util.Locale
import kotlin.math.max

private data class ChartPoint(val label: String, val value: Double)

/** Small native Canvas chart; drawn only on the results screen. */
private class WorkoutLineChart(activity: ComponentActivity, private val values: List<ChartPoint>,
                               private val suffix: String) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (190 * density).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(WorkoutUi.CARD)
        if (values.isEmpty()) return
        val left = 48f * density
        val right = width - 18f * density
        val top = 20f * density
        val bottom = height - 34f * density
        val high = max(0.01, values.maxOf { it.value } * 1.15)
        paint.color = 0xff617182.toInt()
        paint.strokeWidth = density
        canvas.drawLine(left, bottom, right, bottom, paint)
        canvas.drawLine(left, top, left, bottom, paint)
        paint.textSize = 11f * density
        paint.color = Color.LTGRAY
        canvas.drawText(String.format(Locale.US, "%.1f%s", high, suffix), 3f * density, top + 4f * density, paint)
        canvas.drawText("0", 24f * density, bottom + 4f * density, paint)
        val span = right - left
        var previousX = left
        var previousY = bottom
        values.forEachIndexed { index, point ->
            val x = left + if (values.size == 1) span / 2f else span * index / (values.size - 1)
            val y = bottom - ((point.value.coerceAtLeast(0.0) / high) * (bottom - top)).toFloat()
            if (index > 0) {
                paint.color = WorkoutUi.ACCENT
                paint.strokeWidth = 2.5f * density
                canvas.drawLine(previousX, previousY, x, y, paint)
            }
            if (values.size <= 40) {
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 3f * density, paint)
            }
            if (values.size <= 8 || index == 0 || index == values.lastIndex ||
                (values.size <= 24 && index % 4 == 0)) {
                paint.color = Color.LTGRAY
                paint.textSize = 10f * density
                canvas.drawText(point.label, x - 8f * density, height - 12f * density, paint)
            }
            previousX = x
            previousY = y
        }
    }
}

class WorkoutResultsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = WorkoutResultStore.latest
        val root = WorkoutUi.column(this)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        if (result == null) {
            root.addView(WorkoutUi.text(this, "No workout results available", 24f))
            root.addView(WorkoutUi.button(this, "DONE") { finish() })
            return
        }
        val reps = result.setResults.flatMap { it.repResults }
        val squats = reps.mapNotNull { it.metrics as? SquatRepMetrics }
        val durations = squats.map { it.durationSeconds }
        val flexions = squats.map { it.maxKneeFlexion }
        fun number(value: Double) = String.format(Locale.US, "%.1f", value)
        fun average(values: List<Double>) = if (values.isEmpty()) 0.0 else values.average()
        root.addView(WorkoutUi.text(this, "${result.exercise.label.uppercase()} COMPLETE", 28f))
        root.addView(WorkoutUi.text(this,
            "${result.completedSets} Sets    ${result.totalReps} Reps\n" +
            "Total time: ${number(result.durationSeconds)}s    Avg rep: ${number(average(durations))}s", 20f))

        root.addView(WorkoutUi.text(this, "REP DURATION", 22f))
        root.addView(WorkoutLineChart(this, reps.mapIndexed { index, rep ->
            ChartPoint("${index + 1}", (rep.metrics as SquatRepMetrics).durationSeconds)
        }, "s"))
        if (durations.isNotEmpty()) {
            val fastest = durations.indices.minBy { durations[it] }
            val slowest = durations.indices.maxBy { durations[it] }
            root.addView(WorkoutUi.text(this,
                "Fastest: #${fastest + 1} ${number(durations[fastest])}s    " +
                "Slowest: #${slowest + 1} ${number(durations[slowest])}s\n" +
                "Average: ${number(average(durations))}s"))
        }

        root.addView(WorkoutUi.text(this, "SQUAT DEPTH / KNEE FLEXION", 22f))
        root.addView(WorkoutLineChart(this, reps.mapIndexed { index, rep ->
            ChartPoint("${index + 1}", (rep.metrics as SquatRepMetrics).maxKneeFlexion)
        }, "°"))
        if (flexions.isNotEmpty()) root.addView(WorkoutUi.text(this,
            "Average: ${number(average(flexions))}°    " +
            "Deepest: #${flexions.indices.maxBy { flexions[it] } + 1}    " +
            "Shallowest: #${flexions.indices.minBy { flexions[it] } + 1}"))

        root.addView(WorkoutUi.text(this, "SQUAT MOVEMENT OVER TIME", 22f))
        root.addView(WorkoutLineChart(this, result.movementSamples.map {
            ChartPoint(number(it.elapsedSeconds), it.depth)
        }, ""))
        root.addView(WorkoutUi.text(this,
            "Normalized hip descent • ${result.movementSamples.size} sampled points", 14f))

        root.addView(WorkoutUi.text(this, "SET ANALYTICS", 22f))
        result.setResults.forEach { set ->
            val metrics = set.repResults.mapNotNull { it.metrics as? SquatRepMetrics }
            root.addView(WorkoutUi.text(this,
                "Set ${set.setNumber}    ${set.completedReps}/${set.targetReps} reps\n" +
                "Avg duration ${number(average(metrics.map { it.durationSeconds }))}s    " +
                "Avg flexion ${number(average(metrics.map { it.maxKneeFlexion }))}°"))
        }
        root.addView(WorkoutUi.button(this, "DONE") { finish() })
    }
}
