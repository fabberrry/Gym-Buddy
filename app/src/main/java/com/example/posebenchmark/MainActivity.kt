package com.example.posebenchmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.ByteBuffer


class MainActivity : ComponentActivity() {

    // =========================================================
    // EXERCISE MODE
    // =========================================================

    private enum class ExerciseMode {
        SQUAT,
        PUSH_UP
    }


    /*
     * Volatile because the selector is changed on the UI thread,
     * while pose results may arrive on another thread.
     */
    @Volatile
    private var selectedExercise =
        ExerciseMode.SQUAT


    /*
     * Protect analyzer state while switching exercise modes.
     */
    @Volatile
    private var exerciseSession = 0L

    private val exerciseLock =
        Any()


    // =========================================================
    // UI
    // =========================================================

    private lateinit var previewView: PreviewView

    private lateinit var skeletonOverlay: SkeletonOverlay
    private lateinit var guideOverlay: ExerciseGuideOverlay
    private val setupController = ExerciseSetupController()
    private val poseTemporalFilter = PoseTemporalFilter()

    private lateinit var statusText: TextView

    private lateinit var exerciseText: TextView

    private lateinit var squatButton: Button

    private lateinit var pushUpButton: Button
    private lateinit var nextSetButton: Button
    private var workoutSession: WorkoutSessionManager? = null
    private var workoutResultsOpened = false


    // =========================================================
    // EXERCISE ANALYZERS
    // =========================================================

    private val squatExercise =
        SquatExercise()


    private val pushUpExercise =
        PushUpExercise()
    private val visualCoaching = VisualCoaching()
    private var lastSquatReps = 0
    private var lastPushUpReps = 0
    private var pushUpRepStartMs = -1L
    private var pushUpRepMinElbow = Double.POSITIVE_INFINITY


    private var lastExerciseUiUpdate =
        0L


    // =========================================================
    // CAMERA / MEDIAPIPE
    // =========================================================

    private lateinit var cameraExecutor: ExecutorService

    private var poseLandmarker: PoseLandmarker? = null
    private val pushUpAnalyzer = PushUpAnalyzer()




    // =========================================================
    // FPS COUNTER
    // =========================================================

    private val frameGeometry = LinkedHashMap<Long, Pair<Int, Int>>()
    private var lastFrameTimestamp = -1L
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastPoseUiMs = 0L
    private val poseWatchdog = object : Runnable {
        override fun run() {
            if (::skeletonOverlay.isInitialized && lastPoseUiMs > 0 &&
                SystemClock.elapsedRealtime() - lastPoseUiMs > 800) {
                lastPoseUiMs = 0
                synchronized(exerciseLock) {
                    setupController.retry()
                    visualCoaching.reset()
                    squatExercise.onPoseLost(SystemClock.uptimeMillis())
                    pushUpExercise.onPoseLost()
                    pushUpRepStartMs = -1L
                    pushUpRepMinElbow = Double.POSITIVE_INFINITY
                }
                skeletonOverlay.clear()
                guideOverlay.show(currentProfile(),
                    SetupDisplay(SetupStage.ALIGN, SetupHint.CAMERA_VIEW))
            }
            uiHandler.postDelayed(this, 300)
        }
    }

    private var poseFrameCount =
        0

    private var fpsWindowStart =
        0L


    companion object {
        const val EXTRA_WORKOUT_EXERCISE = "workout_exercise"
        const val EXTRA_WORKOUT_SETS = "workout_sets"
        const val EXTRA_WORKOUT_REPS = "workout_reps"

        private const val TAG =
            "PoseBenchmark"


        private const val MODEL_NAME =
            "pose_landmarker_full.task"
    }


    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startCamera()

            } else {

                Toast.makeText(
                    this,
                    "Camera permission is required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        val requestedExercise = intent.getStringExtra(EXTRA_WORKOUT_EXERCISE)
        if (requestedExercise == ExerciseType.SQUAT.name ||
            requestedExercise == ExerciseType.PUSH_UP.name) {
            val sets = intent.getIntExtra(EXTRA_WORKOUT_SETS, 3).coerceIn(1, 20)
            val reps = intent.getIntExtra(EXTRA_WORKOUT_REPS, 10).coerceIn(1, 100)
            val exercise = if (requestedExercise == ExerciseType.PUSH_UP.name)
                ExerciseType.PUSH_UP else ExerciseType.SQUAT
            selectedExercise = if (exercise == ExerciseType.PUSH_UP)
                ExerciseMode.PUSH_UP else ExerciseMode.SQUAT
            workoutSession = WorkoutSessionManager(WorkoutExercise(exercise, sets, reps))
            workoutSession!!.start(SystemClock.uptimeMillis())
        }


        // =====================================================
        // ROOT
        // =====================================================

        val root =
            FrameLayout(this)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }


        // =====================================================
        // CAMERA PREVIEW
        // =====================================================

        previewView =
            PreviewView(this)


        previewView.scaleType =
            PreviewView.ScaleType.FIT_CENTER


        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )


        // =====================================================
        // SKELETON OVERLAY
        // =====================================================

        skeletonOverlay =
            SkeletonOverlay(this)


        root.addView(
            skeletonOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        guideOverlay = ExerciseGuideOverlay(this)
        root.addView(guideOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        guideOverlay.show(currentProfile(),
            SetupDisplay(SetupStage.DEMO, SetupHint.CAMERA_VIEW))


        // =====================================================
        // FPS / MEDIAPIPE STATUS
        // =====================================================

        statusText =
            TextView(this).apply {

                text =
                    "MediaPipe Full\nLoading model..."


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    16f


                setBackgroundColor(
                    Color.argb(
                        150,
                        0,
                        0,
                        0
                    )
                )


                setPadding(
                    24,
                    16,
                    24,
                    16
                )
            }


        val statusParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START


                leftMargin =
                    20


                topMargin =
                    20
            }


        root.addView(
            statusText,
            statusParams
        )
        statusText.visibility = View.GONE


        // =====================================================
        // EXERCISE SELECTOR
        // =====================================================

        squatButton =
            Button(this).apply {

                text =
                    "SQUAT"


                isAllCaps =
                    false


                setOnClickListener {

                    selectExercise(
                        ExerciseMode.SQUAT
                    )
                }
            }


        pushUpButton =
            Button(this).apply {

                text =
                    "PUSH-UP"


                isAllCaps =
                    false


                setOnClickListener {

                    selectExercise(
                        ExerciseMode.PUSH_UP
                    )
                }
            }


        val selectorLayout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL


                gravity =
                    Gravity.CENTER


                setPadding(
                    12,
                    8,
                    12,
                    8
                )


                setBackgroundColor(
                    Color.argb(
                        135,
                        0,
                        0,
                        0
                    )
                )


                addView(
                    squatButton
                )


                addView(
                    pushUpButton
                )
            }


        val selectorParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.CENTER_HORIZONTAL


                topMargin =
                    28
            }


        root.addView(
            selectorLayout,
            selectorParams
        )

        val retryButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            contentDescription = "Retry camera alignment"
            setOnClickListener {
                synchronized(exerciseLock) {
                    exerciseSession++
                    setupController.retry()
                    visualCoaching.reset()
                    squatExercise.onPoseLost(SystemClock.uptimeMillis())
                    pushUpExercise.onPoseLost()
                }
                skeletonOverlay.clear()
                guideOverlay.show(currentProfile(),
                    SetupDisplay(SetupStage.ALIGN, SetupHint.CAMERA_VIEW))
            }
        }
        root.addView(retryButton, FrameLayout.LayoutParams(
            (52 * resources.displayMetrics.density).toInt(),
            (52 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 20
            rightMargin = 20
        })

        if (workoutSession != null) selectorLayout.visibility = View.GONE


        // =====================================================
        // EXERCISE INFORMATION UI
        // =====================================================

        exerciseText =
            TextView(this).apply {

                text = if (selectedExercise == ExerciseMode.SQUAT)
                    "SQUAT   0 reps" else "PUSH-UP   0 reps"


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    16f


                gravity =
                    Gravity.CENTER


                setBackgroundColor(
                    Color.argb(
                        175,
                        0,
                        0,
                        0
                    )
                )


                setPadding(
                    28,
                    18,
                    28,
                    18
                )
            }


        val exerciseParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.BOTTOM or
                            Gravity.CENTER_HORIZONTAL


                leftMargin =
                    20


                rightMargin =
                    20


                bottomMargin =
                    40
            }


        root.addView(
            exerciseText,
            exerciseParams
        )

        nextSetButton = Button(this).apply {
            text = "START NEXT SET"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                synchronized(exerciseLock) {
                    if (workoutSession?.state == WorkoutSessionState.SET_COMPLETE) {
                        squatExercise.resetSession()
                        pushUpExercise.resetSession()
                        pushUpRepStartMs = -1L
                        pushUpRepMinElbow = Double.POSITIVE_INFINITY
                        setupController.retry()
                        workoutSession!!.startNextSet(SystemClock.uptimeMillis())
                    }
                }
                visibility = View.GONE
            }
        }
        root.addView(nextSetButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = 20
            rightMargin = 20
            bottomMargin = (180 * resources.displayMetrics.density).toInt()
        })


        setContentView(
            root
        )


        /* Show the selected exercise's guide before the first camera result. */
        skeletonOverlay.setGuidance(PostureGuidance())
        guideOverlay.show(currentProfile(),
            SetupDisplay(SetupStage.DEMO, SetupHint.CAMERA_VIEW))
        updateSelectorUi()


        // =====================================================
        // BACKGROUND THREAD
        // =====================================================

        cameraExecutor =
            Executors.newSingleThreadExecutor()


        cameraExecutor.execute {

            setupPoseLandmarker()
        }


        // =====================================================
        // CAMERA PERMISSION
        // =====================================================

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startCamera()

        } else {

            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }


    // =========================================================
    // EXERCISE SELECTOR
    // =========================================================

    private fun selectExercise(
        newExercise: ExerciseMode
    ) {

        if (
            selectedExercise ==
            newExercise
        ) {

            return
        }


        synchronized(
            exerciseLock
        ) {

            /*
             * Switching exercises starts a clean session.
             *
             * This prevents an unfinished squat/push-up state
             * from being carried into another exercise.
             */
            exerciseSession++
            squatExercise.resetSession()

            pushUpExercise.resetSession()
            lastSquatReps = 0
            lastPushUpReps = 0
            pushUpRepStartMs = -1L
            pushUpRepMinElbow = Double.POSITIVE_INFINITY

            setupController.reset()
            visualCoaching.reset()
            poseTemporalFilter.reset()


            selectedExercise =
                newExercise
        }


        lastExerciseUiUpdate =
            0L


        skeletonOverlay.setGuidance(PostureGuidance())
        skeletonOverlay.clear()
        guideOverlay.show(currentProfile(),
            SetupDisplay(SetupStage.DEMO, SetupHint.CAMERA_VIEW))
        updateSelectorUi()


        exerciseText.setTextColor(
            Color.WHITE
        )


        exerciseText.text =

            when (newExercise) {

                ExerciseMode.SQUAT ->

                    "SQUAT   0 reps"


                ExerciseMode.PUSH_UP ->

                    "PUSH-UP   0 reps"
            }


        Log.d(
            TAG,
            "Exercise selected: $newExercise"
        )
    }

    private fun currentProfile(): ExerciseProfile = ExerciseProfile.forExercise(
        if (selectedExercise == ExerciseMode.SQUAT) CoachingExercise.SQUAT else CoachingExercise.PUSH_UP
    )

    private fun detectedBodyScale(profile: ExerciseProfile,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        frameWidth: Int, frameHeight: Int, side: Int?): Float {
        val offset = side ?: return 1f
        val shoulder = landmarks.getOrNull(11 + offset) ?: return 1f
        val ankle = landmarks.getOrNull(27 + offset) ?: return 1f
        val span = kotlin.math.hypot((ankle.x() - shoulder.x()) * frameWidth,
            (ankle.y() - shoulder.y()) * frameHeight)
        val reference = if (profile.exercise == CoachingExercise.SQUAT)
            frameHeight * 0.60f else frameWidth * 0.62f
        return if (reference > 0f) span / reference else 1f
    }


    private fun updateSelectorUi() {

        val squatSelected =
            selectedExercise ==
                    ExerciseMode.SQUAT


        squatButton.alpha =
            if (squatSelected) {
                1.0f
            } else {
                0.50f
            }


        pushUpButton.alpha =
            if (squatSelected) {
                0.50f
            } else {
                1.0f
            }


        squatButton.isEnabled =
            !squatSelected


        pushUpButton.isEnabled =
            squatSelected
    }


    // =========================================================
    // MEDIAPIPE SETUP
    // =========================================================

    private fun setupPoseLandmarker() {

        try {

            val baseOptions =
                BaseOptions.builder()

                    .setDelegate(
                        Delegate.CPU
                    )

                    .setModelAssetPath(
                        MODEL_NAME
                    )

                    .build()


            val options =
                PoseLandmarker
                    .PoseLandmarkerOptions
                    .builder()

                    .setBaseOptions(
                        baseOptions
                    )

                    /*
                     * ONE PERSON ONLY.
                     */
                    .setNumPoses(
                        1
                    )

                    .setMinPoseDetectionConfidence(
                        0.5f
                    )

                    .setMinPosePresenceConfidence(
                        0.5f
                    )

                    .setMinTrackingConfidence(
                        0.5f
                    )

                    .setRunningMode(
                        RunningMode.LIVE_STREAM
                    )

                    .setResultListener { result, _ ->

                        onPoseResult(
                            result
                        )
                    }

                    .setErrorListener { error ->

                        Log.e(
                            TAG,
                            "MediaPipe error",
                            error
                        )
                    }

                    .build()


            poseLandmarker =
                PoseLandmarker.createFromOptions(
                    this,
                    options
                )


            fpsWindowStart =
                SystemClock.elapsedRealtime()


            runOnUiThread {

                statusText.text =
                    "MediaPipe Full\nWaiting for pose..."
            }


            Log.d(
                TAG,
                "MediaPipe Pose Landmarker loaded"
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Could not load Pose Landmarker",
                e
            )


            runOnUiThread {

                statusText.text =
                    "MediaPipe model failed to load"
                statusText.visibility = View.VISIBLE
            }
        }
    }


    // =========================================================
    // CAMERAX
    // =========================================================

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(
                this
            )


        cameraProviderFuture.addListener({

            try {

                val cameraProvider =
                    cameraProviderFuture.get()


                // -------------------------------------------------
                // Preview
                // -------------------------------------------------

                val preview =
                    Preview.Builder()
                        .build()
                        .also {

                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }


                // -------------------------------------------------
                // Image Analysis
                // -------------------------------------------------

                val imageAnalysis =
                    ImageAnalysis.Builder()

                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )

                        .setOutputImageFormat(
                            ImageAnalysis
                                .OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )

                        .build()


                imageAnalysis.setAnalyzer(
                    cameraExecutor
                ) { imageProxy ->

                    analyzeFrame(
                        imageProxy
                    )
                }


                // -------------------------------------------------
                // Bind camera
                // -------------------------------------------------

                cameraProvider.unbindAll()


                val viewPort = previewView.viewPort
                if (viewPort == null) {
                    previewView.post { startCamera() }
                    return@addListener
                }
                val useCases = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(imageAnalysis)
                    .build()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCases
                )


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Camera failed",
                    e
                )


                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_LONG
                ).show()
                statusText.text = "Unable to start camera"
                statusText.visibility = View.VISIBLE
            }


        }, ContextCompat.getMainExecutor(this))
    }


    // =========================================================
    // CAMERA FRAME -> MEDIAPIPE
    // =========================================================

    private fun analyzeFrame(
        imageProxy: ImageProxy
    ) {

        val landmarker =
            poseLandmarker


        if (
            landmarker ==
            null
        ) {

            imageProxy.close()

            return
        }


        val rotationDegrees =
            imageProxy
                .imageInfo
                .rotationDegrees


        // -------------------------------------------------
        // RGBA camera frame -> bitmap
        // -------------------------------------------------

        val cropRect = android.graphics.Rect(imageProxy.cropRect)
        val bitmapBuffer =
            Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )


        try {

            val plane = imageProxy.planes[0]
            val source = plane.buffer.duplicate()
            val packed = ByteBuffer.allocate(imageProxy.width * imageProxy.height * 4)
            val row = ByteArray(imageProxy.width * 4)
            for (y in 0 until imageProxy.height) {
                if (plane.pixelStride == 4) {
                    source.position(y * plane.rowStride)
                    source.get(row, 0, row.size)
                } else {
                    for (x in 0 until imageProxy.width) {
                        val pixel = y * plane.rowStride + x * plane.pixelStride
                        for (channel in 0..3) row[x * 4 + channel] = source.get(pixel + channel)
                    }
                }
                packed.put(row)
            }
            packed.rewind()
            bitmapBuffer.copyPixelsFromBuffer(packed)


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Frame conversion failed",
                e
            )


            return


        } finally {

            imageProxy.close()
        }


        // -------------------------------------------------
        // Rotate frame
        // -------------------------------------------------

        val matrix =
            Matrix().apply {

                postRotate(
                    rotationDegrees.toFloat()
                )
            }


        val croppedBitmap = Bitmap.createBitmap(bitmapBuffer, cropRect.left, cropRect.top,
            cropRect.width(), cropRect.height())
        val rotatedBitmap =
            Bitmap.createBitmap(
                croppedBitmap,
                0,
                0,
                croppedBitmap.width,
                croppedBitmap.height,
                matrix,
                true
            )


        // -------------------------------------------------
        // Bitmap -> MediaPipe image
        // -------------------------------------------------

        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        // -------------------------------------------------
        // Async inference
        // -------------------------------------------------

        val frameTime = synchronized(frameGeometry) {
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastFrameTimestamp + 1)
            lastFrameTimestamp = timestamp
            frameGeometry[timestamp] = rotatedBitmap.width to rotatedBitmap.height
            while (frameGeometry.size > 12) frameGeometry.remove(frameGeometry.keys.first())
            timestamp
        }
        try {

            landmarker.detectAsync(
                mpImage,
                frameTime
            )


        } catch (e: Exception) {

            synchronized(frameGeometry) { frameGeometry.remove(frameTime) }

            Log.e(
                TAG,
                "Pose detection failed",
                e
            )
        }
    }


    // =========================================================
    // MEDIAPIPE RESULT
    // =========================================================

    private fun onPoseResult(
        result: PoseLandmarkerResult
    ) {

        val frameSize = synchronized(frameGeometry) {
            frameGeometry.remove(result.timestampMs())
        } ?: return

        poseFrameCount++


        val currentTime =
            SystemClock.elapsedRealtime()


        val elapsed =
            currentTime -
                    fpsWindowStart


        val filteredPose = result.landmarks().firstOrNull()?.let { raw ->
            synchronized(exerciseLock) {
                poseTemporalFilter.process(PoseObservation.fromMediaPipe(
                    result.timestampMs(), frameSize.first, frameSize.second, raw))
            }
        }
        val filteredLandmarks = filteredPose?.toMediaPipe()
        val poseDetected = filteredLandmarks != null


        /*
         * Only ONE of these will contain a result per frame.
         */
        var squatResult: SquatExerciseResult? =
            null


        var pushUpResult: PushUpExerciseResult? =
            null


        /*
         * Remember which exercise was used for this specific
         * pose result.
         */
        val activeExercise: ExerciseMode
        val activeSession: Long


        // =====================================================
        // SKELETON
        // =====================================================

        if (poseDetected) {
            val landmarks = filteredLandmarks!!
            val frameWidth = frameSize.first
            val frameHeight = frameSize.second
            val guidance: PostureGuidance
            val setup: SetupDisplay
            val profile: ExerciseProfile

            synchronized(exerciseLock) {
                activeExercise = selectedExercise
                activeSession = exerciseSession
                profile = currentProfile()
                setup = setupController.update(profile, landmarks, frameWidth, frameHeight,
                    result.timestampMs())
                guidance = if (!setup.canScore) {
                    squatExercise.onPoseLost(result.timestampMs())
                    pushUpExercise.onPoseLost()
                    pushUpRepStartMs = -1L
                    pushUpRepMinElbow = Double.POSITIVE_INFINITY
                    visualCoaching.reset()
                    PostureGuidance()
                } else when (activeExercise) {
                    ExerciseMode.SQUAT -> {
                        squatResult = squatExercise.analyze(
                            landmarks, frameWidth, frameHeight, result.timestampMs()
                        )
                        lastSquatReps = squatResult!!.repCount
                        workoutSession?.let { session ->
                            val measurement = squatResult!!
                            session.recordSample(result.timestampMs(),
                                (measurement.standingBaseline ?: measurement.smoothedHipSignal) -
                                    measurement.smoothedHipSignal)
                            measurement.completedRep?.let {
                                session.completedRep(result.timestampMs(), it)
                            }
                        }
                        visualCoaching.squat(landmarks, frameWidth, frameHeight,
                            squatResult!!, result.timestampMs())
                    }
                    ExerciseMode.PUSH_UP -> {
                        pushUpResult = pushUpExercise.analyze(landmarks)
                        val measurement = pushUpResult!!
                        if (measurement.phase == PushUpPhase.DESCENDING &&
                            pushUpRepStartMs < 0) pushUpRepStartMs = result.timestampMs()
                        if (pushUpRepStartMs >= 0 && measurement.averageElbowAngle.isFinite())
                            pushUpRepMinElbow = minOf(pushUpRepMinElbow,
                                measurement.averageElbowAngle)
                        if (measurement.repCount > lastPushUpReps) {
                            workoutSession?.let { session ->
                                session.completedRep(result.timestampMs(), PushUpRepMetrics(
                                    if (pushUpRepStartMs >= 0) pushUpRepStartMs else result.timestampMs(),
                                    result.timestampMs(),
                                    pushUpRepMinElbow.takeIf { it.isFinite() }
                                        ?: measurement.averageElbowAngle))
                            }
                            pushUpRepStartMs = -1L
                            pushUpRepMinElbow = Double.POSITIVE_INFINITY
                        }
                        lastPushUpReps = pushUpResult!!.repCount
                        visualCoaching.pushUp(landmarks,
                            pushUpAnalyzer.analyze(landmarks, frameWidth, frameHeight),
                            pushUpResult!!, result.timestampMs())
                    }
                }
            }

            runOnUiThread {
                // Reject queued results from a previous session, including A -> B -> A switches.
                if (exerciseSession == activeSession) {
                    lastPoseUiMs = SystemClock.elapsedRealtime()
                    skeletonOverlay.setLandmarks(landmarks, frameWidth, frameHeight)
                    // NOT_READY supplies empty guidance without hiding partial landmarks.
                    skeletonOverlay.setGuidance(guidance)
                    guideOverlay.show(profile, setup,
                        detectedBodyScale(profile, landmarks, frameWidth, frameHeight,
                            setup.selectedSide))
                }
            }
        } else {
            val setup: SetupDisplay
            val profile: ExerciseProfile
            synchronized(exerciseLock) {
                activeExercise = selectedExercise
                activeSession = exerciseSession
                profile = currentProfile()
                setup = setupController.update(profile, null, frameSize.first, frameSize.second,
                    result.timestampMs())
                visualCoaching.reset()
                pushUpRepStartMs = -1L
                pushUpRepMinElbow = Double.POSITIVE_INFINITY
                when (activeExercise) {
                    ExerciseMode.SQUAT -> squatExercise.onPoseLost(result.timestampMs())
                    ExerciseMode.PUSH_UP -> pushUpExercise.onPoseLost()
                }
            }
            runOnUiThread {
                if (exerciseSession == activeSession) {
                    lastPoseUiMs = SystemClock.elapsedRealtime()
                    skeletonOverlay.clear()
                    guideOverlay.show(profile, setup)
                }
            }
        }

        // =====================================================
        // EXERCISE UI
        // =====================================================

        if (
            currentTime -
            lastExerciseUiUpdate >=
            100
        ) {

            lastExerciseUiUpdate =
                currentTime


            val compactDisplay = when (activeExercise) {
                ExerciseMode.SQUAT -> "SQUAT   $lastSquatReps reps"
                ExerciseMode.PUSH_UP -> "PUSH-UP   $lastPushUpReps reps"
            }
            runOnUiThread {

                /*
                 * Avoid displaying a stale result from the exercise
                 * that was selected just before the user switched.
                 */
                if (
                    exerciseSession == activeSession
                ) {

                    val session = workoutSession
                    if (session == null) {
                        exerciseText.text = compactDisplay
                    } else {
                        synchronized(exerciseLock) {
                            exerciseText.text = when (session.state) {
                                WorkoutSessionState.ACTIVE_SET ->
                                    "${if (activeExercise == ExerciseMode.SQUAT) "SQUAT" else "PUSH-UP"}   " +
                                        "Set ${session.currentSet}/${intent.getIntExtra(EXTRA_WORKOUT_SETS, 3)}   " +
                                        "Rep ${session.currentRep}/${intent.getIntExtra(EXTRA_WORKOUT_REPS, 10)}"
                                WorkoutSessionState.SET_COMPLETE -> "SET ${session.currentSet} COMPLETE"
                                WorkoutSessionState.WORKOUT_COMPLETE -> "WORKOUT COMPLETE"
                                WorkoutSessionState.NOT_STARTED -> "Preparing workout"
                            }
                            nextSetButton.visibility = if (session.state == WorkoutSessionState.SET_COMPLETE)
                                View.VISIBLE else View.GONE
                            if (session.state == WorkoutSessionState.WORKOUT_COMPLETE && !workoutResultsOpened) {
                                workoutResultsOpened = true
                                WorkoutResultStore.latest = session.result()
                                startActivity(Intent(this, WorkoutResultsActivity::class.java))
                                finish()
                            }
                        }
                    }


                    exerciseText.setTextColor(Color.WHITE)
                }
            }
        }


        // =====================================================
        // FPS
        // =====================================================

        val landmarkCount =

            if (poseDetected) {

                filteredLandmarks!!.size

            } else {

                0
            }


        val latency =
            SystemClock.uptimeMillis() -
                    result.timestampMs()


        if (
            elapsed >=
            1000
        ) {

            val fps =
                poseFrameCount *
                        1000f /
                        elapsed


            poseFrameCount =
                0


            fpsWindowStart =
                currentTime


            val poseText =

                if (poseDetected) {

                    "YES"

                } else {

                    "NO"
                }


            val displayText =
                String.format(
                    Locale.US,

                    "MediaPipe Full\n" +
                            "Pose FPS: %.1f\n" +
                            "Pose: %s\n" +
                            "Landmarks: %d\n" +
                            "Latency: %d ms",

                    fps,
                    poseText,
                    landmarkCount,
                    latency
                )


            runOnUiThread {

                statusText.text =
                    displayText
            }


            // =================================================
            // DEBUG LOG FOR SELECTED EXERCISE
            // =================================================

            when (activeExercise) {

                ExerciseMode.SQUAT -> {

                    if (
                        squatResult !=
                        null
                    ) {

                        Log.d(
                            TAG,

                            "Exercise=SQUAT " +
                                    "FPS=${"%.1f".format(fps)} " +
                                    "Pose=$poseText " +
                                    "Reps=${squatResult!!.repCount} " +
                                    "Phase=${squatResult!!.phase} " +
                                    "Side=${squatResult!!.selectedSide} " +
                                    "Valid=${squatResult!!.bodyVisible} " +
                                    "HipRaw=${squatResult!!.rawHipSignal} " +
                                    "HipSmooth=${squatResult!!.smoothedHipSignal} " +
                                    "Flex=${squatResult!!.kneeFlexion} " +
                                    "Velocity=${squatResult!!.movementVelocity} " +
                                    "Baseline=${squatResult!!.standingBaseline} " +
                                    "Feedback=${squatResult!!.feedback}"
                        )
                    }
                }


                ExerciseMode.PUSH_UP -> {

                    if (
                        pushUpResult !=
                        null
                    ) {

                        Log.d(
                            TAG,

                            "Exercise=PUSH_UP " +
                                    "FPS=${"%.1f".format(fps)} " +
                                    "Pose=$poseText " +
                                    "Reps=${pushUpResult!!.repCount} " +
                                    "Phase=${pushUpResult!!.phase} " +
                                    "Elbow=${"%.1f".format(pushUpResult!!.averageElbowAngle)} " +
                                    "Difference=${"%.1f".format(pushUpResult!!.elbowDifference)} " +
                                    "Feedback=${pushUpResult!!.feedback}"
                        )
                    }
                }
            }


            if (
                squatResult ==
                null &&
                pushUpResult ==
                null
            ) {

                Log.d(
                    TAG,

                    "Exercise=$activeExercise " +
                            "FPS=${"%.1f".format(fps)} " +
                            "Pose=$poseText " +
                            "Landmarks=$landmarkCount " +
                            "Latency=${latency}ms"
                )
            }
        }
    }


    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onStart() {
        super.onStart()
        uiHandler.postDelayed(poseWatchdog, 300)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(poseWatchdog)
        lastPoseUiMs = 0
        synchronized(exerciseLock) {
            exerciseSession++
            setupController.reset()
            visualCoaching.reset()
            poseTemporalFilter.reset()
            squatExercise.onPoseLost(SystemClock.uptimeMillis())
            pushUpExercise.onPoseLost()
            pushUpRepStartMs = -1L
            pushUpRepMinElbow = Double.POSITIVE_INFINITY
        }
        synchronized(frameGeometry) { frameGeometry.clear() }
        if (::skeletonOverlay.isInitialized) skeletonOverlay.clear()
        if (::guideOverlay.isInitialized) guideOverlay.show(currentProfile(),
            SetupDisplay(SetupStage.DEMO, SetupHint.CAMERA_VIEW))
        super.onStop()
    }

    override fun onDestroy() {

        super.onDestroy()


        if (
            ::cameraExecutor
                .isInitialized
        ) {

            cameraExecutor.execute {

                poseLandmarker
                    ?.close()


                poseLandmarker =
                    null
            }


            cameraExecutor.shutdown()
        }
    }
}
