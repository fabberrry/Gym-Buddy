# Visual exercise coaching architecture

## Pipeline

`MainActivity` crops and rotates each CameraX analysis frame, records its dimensions against the MediaPipe timestamp, and submits it asynchronously. A returned frame becomes a `PoseObservation`. `PoseTemporalFilter` validates confidence, rejects implausible single-frame displacement, applies causal time-based EMA smoothing, and passes the filtered observation to camera setup and the exercise analyzer. Analysis code has no CameraX or View dependency, so recorded observations can be replayed later.

The concerns remain separate:

- `ExerciseSetupController`: DEMO, ALIGN, COUNTDOWN, ACTIVE, TEMPORARILY_LOST.
- `SquatAnalyzer`: NOT_READY, STANDING, DESCENDING, BOTTOM, ASCENDING.
- `WorkoutSessionManager`: workout and set progress.

`ExerciseProfile` is the exercise analysis profile. It declares accepted camera views, required landmarks, normalized setup template, camera height, and permitted body scale. Squat and push-up both accept either side; new front or three-quarter profiles can use the same camera pipeline.

## Camera setup and alignment

The normalized ghost is drawn from vector joints, so it scales to the screen. Detected pose comparison removes translation and body scale by expressing each required joint relative to the selected shoulder-to-ankle span. The alignment score is:

- required-landmark visibility: 30%
- body center: 15%
- body scale: 15%
- normalized setup-template similarity: 40%

A score of at least 0.72 must remain valid for 1,200 ms. A two-second visual countdown then changes the camera state to ACTIVE automatically. One good frame cannot start analysis. The visual ring communicates score; arrows communicate distance, horizontal placement, and rotation. The ghost becomes green when ready.

Setup rejects required joints outside the 6%-94% safe frame, shoulder-to-ankle span below 0.42 of the smaller frame dimension, span above 1.55, a body center outside 38%-62% horizontally, and a visibly front-facing shoulder/hip width above 0.28 body spans. These are provisional usability settings.

The more reliable side is selected initially. It switches only if the other side exceeds current confidence by 0.15 for 500 ms. A brief invalid view enters TEMPORARILY_LOST for 350 ms. Scoring and corrections pause, and the next recovered squat sample is not used to infer velocity. A longer loss returns to alignment.

## Temporal pose and squat analysis

`PoseTemporalFilter` is causal. It requires 0.60 landmark visibility, clears history after 350 ms, applies an 80 ms EMA time constant, and marks displacement above four body lengths per second unreliable. It keeps only one prior point per landmark.

`SquatAnalyzer` uses aspect-corrected image geometry and body-relative signals:

- hip height relative to ankle, normalized by upper plus lower leg length
- normalized hip drop from a standing baseline
- knee flexion
- hip velocity in normalized units per second
- torso inclination and deviation from the standing baseline
- timestamps and prior phase

Standing baseline updates slowly only while the person is confidently upright and still. Phase changes combine depth, knee flexion, velocity, direction, the prior phase, and elapsed-time persistence. A bottom hold cannot repeat a rep. A shallow bounce, starting crouched, a discontinuity, or tracking loss cannot complete one. Completed `SquatRepMetrics` contain timestamps, duration, maximum normalized depth, maximum knee flexion, maximum torso deviation, and descent/ascent durations.

## Visual posture evaluation

`SquatPostureEvaluator` is separate from drawing and returns at most one `VisualCue`. It requires all selected-side landmarks to remain reliable.

Shallow depth is considered only when a descent stalls or reverses after meaningful movement, with normalized hip drop from 0.08 to below 0.20 leg lengths. Evidence first paints hip/thigh amber. After 250 ms it paints only the hip-to-knee region red, anchors a downward arrow at the hip, and creates a translucent green hip target calculated from the user's leg length and current depth.

Torso evaluation is phase aware. At standing it uses absolute torso inclination; during descent, bottom, and ascent it uses deviation from the person's standing baseline. Amber begins at 30 degrees. Evidence at 40 degrees for 250 ms produces red on shoulder-to-hip only. Clearing requires 200 ms of good evidence. The primary correction ranking is confirmed depth, confirmed torso, developing depth, then developing torso.

Push-up retains its side-view hip/body-line correction and selected elbow cue. Red push-up cues still require repeated evidence. The app does not claim knee valgus, elbow flare, spinal curvature, joint loading, injury risk, or medical diagnosis from monocular 2D pose.

## Rendering and performance

`SkeletonOverlay` receives structured joint, connection, direction, and target data. Red bones are dashed, incorrect joints include an X, warnings include a triangle, arrows show direction, and targets are translucent green. Unmeasured segments stay neutral. The overlay reuses Paint objects and bounded landmark filters. Camera conversion and MediaPipe work stay off the main thread. Frame geometry and temporal histories are bounded.

## Thresholds requiring phone tuning

All alignment boundaries, the four-body-length outlier speed, 0.20 squat depth, 30/40 degree torso boundaries, 250 ms activation, and 200 ms clearing need recorded-sequence and real-person validation. They are engineering defaults, not universal exercise standards.

## Real phone acceptance plan

Use a rear camera in landscape or portrait with the full side silhouette visible. Record expected and observed results for both left and right sides.

| Scenario | Expected behavior |
|---|---|
| Person too far | Inward scale arrows; no countdown or reps. |
| Person too close or cropped | Outward arrows/safe-region cue; no countdown. |
| Person left/right of useful region | Arrow points toward center. |
| Shoulder, hip, knee, or ankle missing | Body-region frame cue; no scoring. |
| Front-facing squat stance | Rotate cue; no analyzing state. |
| Correct side setup | Alignment ring fills for 1.2 s, then countdown and automatic activation. |
| Brief confidence loss below 350 ms | Corrections clear; phase is preserved; no rep is created. |
| Longer loss | Returns to alignment and requires readiness again. |
| Stand still for 10 s | Stable baseline and zero reps. |
| One slow complete squat | Ordered phases and exactly one rep. |
| Five normal complete squats | Exactly five reps with one metrics record each. |
| Fast squat | No phase leap or duplicate rep; inspect whether filtering adds unacceptable lag. |
| Shallow squat and reverse | Hip/thigh amber then red with downward arrow and body-relative target; no valid full rep. |
| Bottom hold | BOTTOM remains stable and adds no rep until ascent completes. |
| Partial bounce | No valid rep. |
| Persistent excessive torso deviation | Torso alone becomes amber then red; one anomalous frame does not warn. |
| Leave frame during a squat | Evaluation pauses, warnings clear, and no rep completes across a long gap. |
| Re-enter quickly | Safe phase recovery without velocity spike. |
| Re-enter after long loss | Alignment flow restarts. |
| Change camera distance while active | Evaluation pauses and scale alignment cue appears. |

For each clip compare overlay coordinates to the preview at device rotations and aspect ratios. Export MediaPipe landmarks with timestamps for deterministic replay tests before tuning thresholds.

## Next work after squat validation

Collect labeled phone sequences for good, shallow, fast, occluded, and torso-deviation squats; build a replay fixture; tune thresholds against false positives and missed events; then apply the proven temporal/profile architecture to push-up depth and rep timing. Avoid adding more exercises until squat performance is measured across people, clothing, lighting, camera heights, and device orientations.
