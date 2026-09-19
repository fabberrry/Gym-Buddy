package com.example.posebenchmark

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

internal object WorkoutUi {
    const val BG = 0xff10151d.toInt()
    const val CARD = 0xff1b2430.toInt()
    const val ACCENT = 0xff6fe0b5.toInt()

    fun column(activity: ComponentActivity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 36, 24, 24)
        setBackgroundColor(BG)
    }
    fun text(activity: ComponentActivity, value: String, size: Float = 18f): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(8, 14, 8, 14)
        }
    fun button(activity: ComponentActivity, value: String, action: () -> Unit): Button =
        Button(activity).apply {
            text = value
            isAllCaps = false
            setOnClickListener { action() }
        }
}

class HomeActivity : ComponentActivity() {
    private val occupancyViewModel: GymOccupancyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = WorkoutUi.column(this)
        root.addView(WorkoutUi.text(this, "GYM BUDDY", 32f))
        root.addView(WorkoutUi.text(this, "Choose how you want to train today."))
        val occupancyCard = WorkoutUi.text(this, "GYM RIGHT NOW\nChecking gym occupancy...", 20f)
        occupancyCard.setPadding(24, 20, 24, 20)
        occupancyCard.background = GradientDrawable().apply {
            setColor(WorkoutUi.CARD)
            cornerRadius = 18 * resources.displayMetrics.density
        }
        root.addView(occupancyCard)
        root.addView(WorkoutUi.button(this, "Refresh count") {
            occupancyViewModel.refreshNow()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                occupancyViewModel.state.collect { state ->
                    occupancyCard.text = when (state) {
                        GymOccupancyUiState.Loading -> "GYM RIGHT NOW\nChecking gym occupancy..."
                        GymOccupancyUiState.Unavailable -> "GYM RIGHT NOW\nGym occupancy unavailable"
                        is GymOccupancyUiState.Available -> buildString {
                            append("GYM RIGHT NOW\n${state.room.count} people in gym")
                            if (state.stale) {
                                append("\nLast updated ${formatAge(state.ageSeconds)} ago")
                                if (state.connectionUnavailable) append("\nConnection temporarily unavailable")
                            } else append("\nUpdated just now")
                            if (state.room.status == "uncertain")
                                append("\nOccupancy estimate may be uncertain")
                            if (state.sessionChanged)
                                append("\nCounter restarted; verify baseline")
                        }
                    }
                }
            }
        }
        root.addView(WorkoutUi.button(this, "Quick Analyze") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        root.addView(WorkoutUi.button(this, "Workout Mode") {
            startActivity(Intent(this, WorkoutSetupActivity::class.java))
        })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        occupancyViewModel.startPolling()
    }

    override fun onStop() {
        occupancyViewModel.stopPolling()
        super.onStop()
    }

    private fun formatAge(seconds: Long): String = when {
        seconds < 60 -> "$seconds sec"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} hr"
    }
}

class WorkoutSetupActivity : ComponentActivity() {
    private var selected: WorkoutExercise? = null
    private var sets = 3
    private var reps = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showRoutine()
    }

    private fun showRoutine() {
        val root = WorkoutUi.column(this)
        root.addView(WorkoutUi.text(this, DefaultRoutines.today.name, 30f))
        root.addView(WorkoutUi.text(this, "Select an exercise"))
        for (exercise in DefaultRoutines.today.exercises) {
            root.addView(WorkoutUi.button(this,
                "${exercise.exerciseType.label}  •  ${exercise.sets} sets × ${exercise.reps} reps") {
                selected = exercise
                sets = exercise.sets
                reps = exercise.reps
                showSetup()
            })
        }
        root.addView(WorkoutUi.text(this, "More exercises can be added to future routines.", 14f))
        setContentView(root)
    }

    private fun showSetup() {
        val exercise = selected ?: return
        val root = WorkoutUi.column(this)
        root.addView(WorkoutUi.text(this, "WORKOUT SETUP", 30f))
        root.addView(WorkoutUi.text(this, "Exercise: ${exercise.exerciseType.label}"))
        val setsLabel = WorkoutUi.text(this, "Sets: $sets", 24f)
        val repsLabel = WorkoutUi.text(this, "Reps per set: $reps", 24f)
        root.addView(setsLabel)
        root.addView(stepper("Sets", { sets }, { sets = it; setsLabel.text = "Sets: $sets" }, 1, 20))
        root.addView(repsLabel)
        root.addView(stepper("Reps", { reps }, { reps = it; repsLabel.text = "Reps per set: $reps" }, 1, 100))
        root.addView(WorkoutUi.button(this, "START WORKOUT") {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_WORKOUT_EXERCISE, exercise.exerciseType.name)
                putExtra(MainActivity.EXTRA_WORKOUT_SETS, sets)
                putExtra(MainActivity.EXTRA_WORKOUT_REPS, reps)
            })
        })
        root.addView(WorkoutUi.button(this, "Back to exercises") { showRoutine() })
        setContentView(root)
    }

    private fun stepper(label: String, get: () -> Int, set: (Int) -> Unit,
                        minimum: Int, maximum: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(WorkoutUi.button(this@WorkoutSetupActivity, "− $label") {
            set((get() - 1).coerceAtLeast(minimum))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(WorkoutUi.button(this@WorkoutSetupActivity, "+ $label") {
            set((get() + 1).coerceAtMost(maximum))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected != null) { selected = null; showRoutine() } else super.onBackPressed()
    }
}
