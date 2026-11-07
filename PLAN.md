# Drishti Saathi: Build Plan

`PROJECT_BRIEF.md` is the main spec. This file tracks build order and the additions agreed after the brief was written.
Where they conflict, the brief wins unless an item here says it replaces a brief item.

## Phase 1 (typed flow, no camera): due 9 Nov 2026

| Step | What | Status |
|---|---|---|
| 1 | Gradle scaffold: `app` (Android, Compose) + `engine` (pure Kotlin) | Done |
| 2 | ANSUR II priors → `anthro_priors.json` | Done |
| 3 | Engine formulas: far point, near-point add, Hofstetter, thickness, plausibility gate; `rules.json` | Done |
| 4 | M4 rules engine + Section 6 referral | Done |
| 5 | M5 frame ranking + placeholder `frames.json` (10 frames) | Done |
| 6 | Hindi/English interview, old-numbers entry, confirmation, wording test | Done |
| 7 | Full summary + referral screens, staff verification, Room storage | Done |
| 8 | PDF export + share (shop copy, Hindi customer copy), delete session, history | Done |
| 9 | **Consent screen** (addition 1) | Done |
| 10 | **Ranking labels + reason bullets** (addition 2) | Done |
| 11 | **Two-frame comparison screen** (addition 3) | Done |
| 12 | **New interview fields → frame scoring** (addition 4) | Done |

### Addition 1: Consent screen
- Shown before any capture: what is collected (answers, measurements; never name or face images), why (pre-consultation for the shop's own trial-lens test), where it stays (on this phone), how to delete it. Two buttons: **Continue** / **Cancel** (Cancel returns home, nothing stored).
- Phase 1: shown once at the start of each session, before the interview. Phase 2: shown again before the camera opens, covering face frames processed in memory only.
- Bilingual, read aloud by TTS in Phase 2. Consent timestamp stored with the session.

### Addition 2: Ranking output labels
- Each ranked frame gets a label from its total score: **Highly recommended** / **Moderate match** / **Lower match**. Score cut-offs live in `rules.json` (`frameScoring.labelHighFrom`, `labelModerateFrom`).
- 2–4 bullet reasons generated from the score components (fit, thinness, style, budget, and the addition 4 components), positive and negative, e.g. "fits your face width (134 mm frame, 136 mm face)", "edge about 4.2 mm in 1.60", "₹600 over budget". Built in the engine as codes plus numbers; wording in the string table so the wording test covers it.

### Addition 3: Side-by-side comparison (2 frames)
- From the summary, select any 2 ranked frames → comparison screen: image or shape, size mark, total width vs face width, thickness in the recommended material (and in the alternatives), price vs budget, label, reason bullets, per-component scores (staff mode).

### Addition 4: New interview fields
| Field | Type | Scoring use (weights in `rules.json`) |
|---|---|---|
| colourPreference | enum: DARK, BROWN_TORTOISE, METALLIC, COLOURFUL, ANY | colour match component; frames get a `colours` list |
| lightweightPreference | bool | weight component; frames get `weightGrams`; rimless/half-rim, titanium, TR90 favoured |
| lookPreference | enum: PROFESSIONAL, CASUAL, BOTH | matched against frame `looks` tags |
| firstTimeWearer | bool | favours light, comfortable frames; adds a staff note on adaptation (and on progressives) |
| readingFrequency | enum: RARELY, SOMETIMES, DAILY_LONG | feeds lens-type rules (reading pair vs multifocal) and a minimum lens height for progressives in frame scoring |

`frames.json` gains `colours`, `weightGrams`, `looks`, `lensHeightMm`. Weights stay summing to 1; the existing validation enforces it.

## Phase 2: Nov 10–11 (from the brief)
TTS/ASR with read-back; CameraX + Face Landmarker; card calibration; PD + face width + quality gates + ANSUR plausibility gate; consent before capture (addition 1).

**Addition 5: Bridge width estimate.** From nose landmarks (nasal bridge width at the frame's resting height, scaled by camMmPerPx). Adds a bridge-fit component to the frame score (frame DBL vs estimated bridge, tolerance in `rules.json`). Confidence-tagged like PD.

**Addition 6: Face shape (style only).** Rule-based from landmark ratios (face width/length, jaw/cheekbone width) → ROUND / OVAL / SQUARE / LONG / HEART. Used only for style suggestions (e.g. angular frames for round faces), never for fit or lens advice. Thresholds in `rules.json`.

## Phase 3: Nov 10–12 (from the brief)
Distance-locked tests, fusion, Bayesian band, 30-frame catalogue, try-on.

**Addition 7: Temple length (experimental).** Optional ~45° side capture; estimate ear-to-frame-front distance → snap to 135 / 140 / 145 mm. Labelled **Experimental** everywhere it appears; never used to drop a frame, only as a staff hint.

**Addition 8: Live try-on.** Frame PNG overlay on the live front camera, posed with the Face Landmarker facial transformation matrix so it follows head turns (yaw/pitch/roll) and scales with distance. 2D overlay with perspective transform; no 3D avatar.

## Status (7 Nov 2026, end of day)

**Built and on `main`:** Phase 1 plus all of Phase 2/3, built in parallel tracks and merged:
- A engine: VisionMath, DistanceEstimator, BayesianBand, FarPointProtocol, FaceGeometry/aggregator, PlausibilityGate.
- B camera: CameraX + MediaPipe Face Landmarker, quality gates with spoken retries, bank-card calibration (screen drag + OpenCV forehead card), camera debug screen.
- C screen tests: far point (blue/white, self- and staff-held), clock dial, near-point push-up, tumbling-E acuity, Amsler grid.
- D voice: TTS hi-IN, speech recognition with lexicon parser and spoken read-back, buttons always available.
- E try-on: 30 frame cut-outs, live overlay posed by the transformation matrix.

Full flow wired: consent → interview → confirm → forehead card (optional) → face capture → capture outcome/retry → screen tests → fusion → referral/result → try-on → comparison → PDFs. Interview red flags skip camera and tests. Staff tools on the home screen: camera check, screen calibration, demo mode.

**Numbers:** 442 unit tests pass (engine + app). Debug APK ~54 MB. Database v3 (adds `measurementsJson`).

**Verified on a phone:** nothing yet beyond TTS. User is testing on a Samsung Galaxy S23 FE; voice recognition did not work there (fix built, not yet installed: no adb connection).

## Decisions made on 7 Nov

**Simulation-tuned rules (`rules_update.md`, from `tools/far_point_simulation.py`), all applied:**
1. Customer cannot reach blur within 0.60 m → staff hold the phone and move it away (Luo method); blur taps count up to 2.0 m.
2. Beyond 0.60 m, distance comes from the card-calibrated PD (f·PD/interpupillary px), not the iris.
3. Blue vs white SE differ by more than 0.50 D → retake once, then LOW confidence.
4. Two repeats of one colour differ by more than 0.50 D → third reading, median.
5. Bayesian band sigma 0.80 D (real studies); India prior 30+: 0.277 / 0.229 / 0.494; myopia magnitude exponential, mean 1.5 D (assumption, replace with pilot data); `priorWeight` 0.5 kept.
6. PD without card: "about X mm (±3 mm)"; with card or staff ruler: ±1 mm. No "fits" claims from uncalibrated measurements.
7. Every reading add says "confirm with a ready-reader at the counter".

**User decisions after the agent's report:**
- Band width 1.5 D (`bayes.bandWidthD`), sigma 0.8 and the 0.60 show cut-off kept. Typical bands now P 0.60–0.70, shown at MEDIUM. (At 1 D no band ever reached 0.60.)
- One reach setting: `farPoint.selfHeldReachM` = 0.60 m (`optics.armsLengthM` removed).
- Never "fits" for a population-average face either: "Frame N mm; average face about M mm (not measured), try it on".
- Staff PD-ruler entry counts as calibrated (±1 mm).
- Staff-held white readings count up to `staffHeldMaxM` (2.0 m), so the blue/white check works there.

**Earlier the same day:**
- F11–F30 live in `assets/frames/more_frames.json` (sizes estimated from photo proportions); engine ranks all 30.
- F01–F10 names, shape, rim and colours corrected to match their photos (sizes, prices, weights unchanged).
- Bridge fit (addition 5): 30 % of the fit score when the nose bridge was measured.
- Face shape (addition 6): style suggestion only, staff section.
- Staff ruler entry beats the camera for PD; camera beats population average.

**Demo mode (for the 9 Nov mid-sprint recording):** staff tools → three sample customers (21 y myope with screen use, card-calibrated; 48 y reading need, no card; 56 y flashes → urgent). Pre-filled answers, camera and test results. DEMO badge on every screen, DEMO in history, DEMO title and line on both PDFs, `DEMO_` in file names.

**Voice on Samsung:**
- Manifest `<queries>` for RecognitionService and RECOGNIZE_SPEECH.
- Recogniser order: Google component (`com.google.android.tts`, then `com.google.android.googlequicksearchbox`) → default → Android 13+ on-device → system voice dialog → buttons.
- Recognisers that fail to bind or don't support Hindi are skipped within the same tap.
- hi-IN with `EXTRA_PREFER_OFFLINE` false; online consent before use.
- Staff voice check line lists all services, the recogniser in use, mode and last error.

## Open items
1. Install the latest APK on the S23 FE (adb not connected) and confirm voice works; read the staff voice check line.
2. Run the phone checklist:
   - camera check: fps, landmark alignment, distance vs tape at 300/400/600 mm, one eye covered
   - card calibration (screen and forehead) and letter size with a ruler
   - far point incl. staff-held mode, retake and third reading
   - try-on yaw direction
   - PDFs
   - demo mode
3. Tune on-device guesses in `rules.json` from real readings: sharpness/brightness gates, face-shape thresholds, bridge landmarks, landmark pose ratios.
4. Under-30 Bayesian prior row is a draft; myopia magnitude prior is an assumption: replace with pilot data.
5. Hindi proofread by a native speaker (all S*, SFlow, SVision, SVoice, SCamera, STryOn strings and the voice lexicon).
6. Room encryption for stored sessions.
7. APK size (~54 MB, mostly OpenCV + MediaPipe): consider dropping armeabi-v7a (~15 MB).
8. Frame images (Kaggle Glasses dataset) have an unknown licence: demo use only.
9. Every clinical number in `rules.json` still needs optometrist review before field use.

## Next steps
1. Phone: install via adb, voice check, then the checklist above; feed measured values back into `rules.json`.
2. 9 Nov: record the mid-sprint update with demo mode (try-on uses the live camera).
3. Fix whatever the phone test finds; re-tune thresholds; native-speaker Hindi pass.
4. Then: Room encryption, APK size, pilot data for the priors.
