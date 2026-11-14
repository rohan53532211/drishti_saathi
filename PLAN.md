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

**Addition 7: Temple length (experimental).** Optional ~45° side capture; estimate ear-to-frame-front distance → snap to 135 / 140 / 145 mm. Labelled **Experimental** everywhere it appears; never used to drop a frame, only as a staff hint. **Status: not implemented** (no side capture or temple estimate in the code yet; frames keep their catalogue temple length).

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

**Per-device correction (learning loop, added 11 Nov):**
- Each verified session gives up to two pairs: the raw far-point SE the phone measured and the SE (sph + cyl/2) of the final trial lens the staff typed in (e.g. `-1.75 / -0.50 x 180`; unreadable entries are skipped).
- Once 5 or more verified sessions exist for this phone model (`Build.MANUFACTURER Build.MODEL`), the app fits final = intercept + slope × measured by least squares and applies it to new far-point estimates before the Bayesian band. Staff see "corrected with N shop records" under the band, on screen and in the shop PDF. The customer copy is unchanged.
- Guards in `rules.json` `deviceCorrection`:
  - pairs more than 3 D apart are treated as entry errors and left out
  - when measured values spread less than 0.5 D, only an offset is fitted
  - the slope is clamped to 0.7–1.3
  - the change to one estimate is capped at ±1 D
- Old-glasses numbers and demo customers are never corrected. Demo sessions never count towards the fit.
- The fit always uses the raw measured SE, so it cannot feed on itself. It is refitted at app start, after a verification is saved and after a session is deleted, and stored in Room table `device_corrections` (database v4).

**Fixes from phone testing (12 Nov):**
1. Old-glasses steppers: the value sits in a fixed-width, single-line slot, with the buttons on their own row, so "−1.75" no longer wraps.
2. AXIS stepper (0–180, steps of 5) per eye, shown only when CYL is not 0.
3. Saving with every value at 0 asks "No numbers available?": either "No numbers" or "Save as plain (0.00)".
4. Referral screen, summary and both PDFs: urgent reasons go in a red "Urgent" group and routine reasons in a separate amber "Routine" group.
5. A "Please take off your glasses" step (also read aloud) comes before the card check, face capture and screen tests. Staff mode can choose "continue with glasses on". That choice is stored with the session and shown as a note on the summary and the shop PDF. The engine does not adjust for it.
6. Bundled font: Noto Sans + Noto Sans Devanagari merged into one file per weight (`tools/build_fonts.py`, SIL OFL), used by every text style, the canvas labels and the PDFs. A unit test checks that every character in the sources and assets has a glyph.
7. Room database encrypted with SQLCipher 4.6.1:
   - The passphrase is 32 random bytes, wrapped by an AES-GCM key in the Android Keystore.
   - An existing plain database is encrypted in place with `sqlcipher_export`. The copy is checked (row counts and schema version) before it replaces the plain file.
   - On failure the plain database is opened unchanged and encryption is retried at the next start.
   - If the key can no longer be read, an encrypted database is moved aside, not deleted.
8. "Demo catalogue: sizes are typical, not measured" on the frame list (staff mode), the comparison screen (staff mode) and the shop PDF.

**Adaptive anamnesis and power path (12 Nov):**
- Interview order:
  - Age.
  - "Trouble seeing far, near, or both?" (DISTANCE / NEAR / BOTH / NONE). It also fills the old `distanceBlur` and `nearBlur` fields, so every existing rule still works. The two old yes/no questions are gone.
  - Far: where it shows (driving, TV, signboards, classroom board) and since when.
  - Near: which task (phone, newspaper, sewing, accounts) and the usual distance (25 / 35 / 45 cm). The distance sets the working distance for the add.
  - Glasses: wearers get glasses age and "happy with them?"; non-wearers get "first pair?".
  - Headache, diabetes/BP, then the red flags.
  - An urgent flag asks onset (today / this week / longer) and which eye. Nothing after that is asked: no power path, lifestyle or frames.
- Power path: "Do you remember your glasses power, or have the slip?" (YES / NOT SURE / NO). It is skipped after an urgent flag, and when there is no trouble and no glasses.
  - YES: the old-glasses screen opens. Those numbers become the power source (`Source.KNOWN_POWER`, "customer's known power").
    - If the glasses are older than 2 years, or blur or unhappiness is still reported, an optional quick check is offered.
    - Its values are shown to staff next to the known power (summary and shop PDF) and never used for the suggestions.
    - "No numbers available" continues as NO.
  - NOT SURE / NO: glasses off, then the quick estimate runs right away. The result is the power source (`Source.PHONE_ESTIMATE`, "phone estimate, approximate", LOW confidence, band ±0.75 D).
    - Far point, DISTANCE/BOTH, each eye: distance-locked 20/20 letters (`quickEstimate.letterLogMar`).
      - The live line "If blur starts here: about −X.XX D" appears once the phone has been close. White: −1/d. Blue: −1/d + 0.67. Rounded to 0.25 D.
      - "Blur", or saying "dhundhla" / "धुंधला" / "blur", freezes it.
      - "Still clear at arm's length" (from 0.60 m) gives "No myopia found at arm's length, check with trial lens".
    - Near, age 38 and over: one near-point reading gives the reading add, with "Confirm with a ready-reader at the counter".
    - Near under 38: no plus estimate, "Possible long-sightedness, check with trial lens" plus the hand test.
- Screen tests after the power path leave out the full far point and clock dial; staff can tick "also run the full far-point test" on the confirmation screen. The near point is left out when the quick estimate already took it.
- The quick estimate is stored in the session's measurements JSON (`quick`). No schema change.
- The summary and shop PDF state the power source.
- The functional vision profile card (summary + shop PDF) shows:
  - daily hours at near (<40 cm), intermediate (40–100 cm) and distance
  - the main zone (share ≥ 0.45, else mixed)
  - the lens it points to
  - thresholds in `rules.json` → `functionalProfile`
- Voice: keywords for all new choice questions. NOT SURE outranks NO, so "पता नहीं" is not read as "नहीं". The blur keyword listener runs without a read-back (the tap does the same) and is not used with the system voice dialog, because it covers the screen.

**Remote Optometrist Bridge (12 Nov):**
- `backend/`: FastAPI + SQLite and a plain web page for the optometrist (list, open, write advice, "needs in-person exam", submit).
  - API: `POST/GET /profiles`, `GET /profiles/{id}`, `POST/GET /profiles/{id}/review`, `DELETE /profiles/{id}`, `/health`.
  - An optional shared token (`BRIDGE_TOKEN`, header `X-Bridge-Token`).
  - The server rejects a profile without spoken and tapped consent, any name / photo / image / phone / email / address key at any depth, strings over 2,000 characters and bodies over 256 KB.
- App:
  - "Send to optometrist" on the summary opens a consent screen. The text is read aloud; staff tick "the customer said yes" (spoken), then the customer taps "I agree, send" (tapped).
  - The summary card shows Waiting / Reviewed and the reply, and is red when an in-person exam is needed. It refreshes once when the summary opens and on "Check for reply".
  - Server address and token are in staff mode → Optometrist bridge settings, with "Test connection".
- The profile contains:
  - answers and the functional profile
  - the power source and values (bands, starting trial lens, add, the known power or the phone estimate)
  - measurements: PD, face width, face numbers, screen tests
  - estimates: referral, lens type, material, coatings, notes
  - never the frame list
- Room v5 table `remote_reviews` (one row per sent session). Deleting the session deletes the row and, best effort, the server copy.
- Plain `http://` is allowed in the debug build only (`src/debug/AndroidManifest.xml`).
- Contract: `backend/tests/fixtures/app_profile.json` is produced by the app's `BridgeProfileTest` and must be accepted by the server.

**Sarvam AI voice interview (12 Nov, branch `feature/sarvam-voice-anamnesis`):**
- Optional "Voice interview (AI)" under the first interview question, shown when a backend address is set. The standard interview stays the default and the fallback.
- Backend `app/anamnesis.py` + `app/sarvam.py`:
  - `/anamnesis/start|turn|finish`; per turn Sarvam speech-to-text (saaras:v4), then chat (sarvam-105b, JSON mode), then text-to-speech (bulbul:v3).
  - The key is only in `backend/.env`.
  - Sessions are in memory, 30 min.
- A coverage tracker in code chooses the topic and stops at 8 questions (or when everything is covered).
  - Red flags are always question 3, with fixed wording; a "yes" ends the interview as urgent.
  - The model's question is checked for diagnosis / power / lens words.
  - Bad JSON or a failed call gives the scripted question.
  - A red-flag keyword the model missed makes the red flag "unclear".
- App (`aivoice/`):
  - consent ("Your voice will be sent to a secure server for transcription. No name or photo is sent.") with Hindi / English
  - 16 kHz WAV recording, auto-stop at 15 s
  - the backend's audio plays (or the phone's own voice reads it if TTS failed)
  - "You said: …", "Thinking…", "Question n of ~8", End, Retry
- `AiProfileMapper` writes the answers into the existing `InterviewAnswers` through the interview's own setters. The existing functional profile, power path, tests and rules engine then run unchanged.
  - The power question is pre-selected but asked again, so the power path still opens.
  - An unclear or unasked red flag is left for the phone to ask.
  - A red-flag "yes" is urgent in the rules engine exactly as before.
- Two backend failures in a row, or a permanent one (no key, wrong token, no server), switch to the standard interview with the answers so far.
- The transcript (text only) is stored in the session's measurements JSON (`aiInterview`). It appears only in staff mode on the summary and on the shop PDF. It is never on the customer copy and never in the optometrist profile, and it is deleted with the session.

## Vision profile branch (`feature/vision-profile`, from `feature-3d-avatar`, 14 Nov 2026)

Rules for this branch: push only `feature/vision-profile`; thresholds live in `rules.json` or `lens_catalog.json`; Gradle runs with `--no-daemon --max-workers=2 -Xmx2g`.

| Phase | What | Status |
|---|---|---|
| A | Safety rules: full check for everyone, red flags, emergency short path, child myopia, report always queued for the optometrist | Done |
| B | Lens-assisted mode (over-refraction) for strong minus powers | Done |
| C | Screening tests: Amsler grid, contrast, colour plates (E test and clock dial kept) | Done |
| D | Survey upgrade (sections, skip logic, "Why we ask") | Done |
| E | Vision profile rings + ZEISS lens engine from `lens_catalog.json` | Done |
| F | Premium UI redesign (tokens, fonts, all screens) | Done (see the limits below) |
| G | QR, WhatsApp share, PDF additions, backend page | Done |
| H | All tests, APK, README, architecture doc | Done |

### Phase A: safety rules (done)
- **Order of the check:** core questions → (red banner if a red flag) → vision-profile survey → camera distance + PD → power of each eye → screening tests → remaining questions → results. Before this branch the power came before the PD.
- **Red flags** (`engine/profile/Safety.kt`, `rules.json` → `safety`): sudden loss of vision, eye pain or redness, new flashes, new floaters, curtain or shadow, double vision, wavy or missing lines on the grid.
  - The two red-flag questions stay where they were (question 3 in the AI interview). After a "yes" the survey asks "which of these?" (`redFlagSigns`); the survey's Digital section asks about double vision.
  - A "yes" without that follow-up is kept as "unspecified" and counts as an emergency.
- **When a red flag is found:**
  - a red banner comes first: "Please see an eye doctor today" with the reasons, in Hindi or English, spoken;
  - the check continues, no lens or frame is suggested, the power is labelled "For the optometrist only";
  - the report is tagged URGENT with the reasons first (`triage` block of the profile).
- **Emergency signs** (sudden vision loss, curtain / shadow, new flashes): short path only (power of each eye + grid), then the results.
- **Under 18 with any minus power:** the power is measured, no lens choice, "Get your child checked by an optometrist (myopia control)", tag CHILD_MYOPIA. Under 18 without myopia may get a lens family, labelled "optometrist confirms" (phase E).
- **The report is always sent** (`bridge/ReportQueue.kt`): every finished session is queued at once and sent when the backend answers (app start, after each result, "Send to optometrist"). The queue holds session ids only and survives restarts. The consent at the start of the session now says so, is read aloud, and covers the report; the separate send-consent screen is no longer used.
- **Changed on purpose:** urgent sessions used to show lens and frame suggestions "for after the eye doctor visit". They no longer do (screen, both PDFs, optometrist report).
- Tests: `SafetyTest`, `SurveyTest` (engine), `SafetyOutputTest` (app).

### Phase B: lens-assisted mode (done)
- **Why:** power = −1/d. At −4.25 D the blur point is at 23.5 cm, where 1 cm is about 0.18 D.
- The first pass runs without a lens as before. From −3.75 D (`lensAssisted.triggerSeD`) the eye switches to lens-assisted mode.
- Staff hold a known minus lens (trial lens or the customer's old glasses). The app suggests one about 2 D weaker, rounded towards the weaker lens to 0.5 D (−4.25 → −2.00, −6.00 → −4.00), and staff type the lens actually held on a number pad (0.25 D steps).
- The blur-distance test is repeated through the lens. Total = lens + residual; from 4 D the lens gets the vertex correction for a 12 mm hold, Fe = F / (1 − 0.012·F).
- Letters sharp at every distance through the lens: "Use a weaker lens", never a guess. "No lens here" keeps the first reading.
- Stronger than −8 D: still measured through a lens, with "High myopia – optometrist must confirm; eye-health check advised".
- Every output (result screen, shop PDF, optometrist report) states the method, e.g. "Measured through −2.00 D lens, residual −2.25 D, total −4.25 D (range −4.00 to −4.50)".
- Learning loop: the phone-model correction is applied to the residual only; lens, residual (raw and rounded), first pass and method are stored with the session, and a verified lens-assisted eye gives a pair for the residual.
- Tests: `LensAssistedTest` (engine), `LensAssistedFlowTest` (app).

### Phase C: screening tests (done)
- The screening stage runs after the power of each eye: full brightness, a note for staff to turn off Night light / Eye comfort mode, then one eye at a time with a live distance pill (green inside the range, amber outside), a 3-2-1 countdown and Skip on every test. System bars are hidden.
- **Grid (Amsler):** squares of 5 real mm from the display dpi or the bank-card scale, centre dot, starts only at 27–33 cm. "Are any lines wavy, missing, blurred or broken?" by voice or tap; on yes the person taps the squares. Any yes is a red flag and the tapped squares are stored and sent.
  - A phone screen is narrower than 10 cm, so whole columns are left out at the sides (e.g. 12 × 20 on a 66 mm screen); the squares are never scaled and the screen says which grid was shown.
- **Contrast:** one Sloan letter at 0.5 logMAR for the measured distance, steps 100 / 50 / 25 / 12.5 / 6 / 3 / 1.5 %, 2 of 3 right passes a step. Result: lowest step passed, Normal (3 % or better) or Reduced. Reduced with age 50+ or low-light / glare complaints adds "Suggest cataract / eye-health check".
- **Colour:** our own generated dot plates (`ColourPlates`, no copied test images): 1 control + 5 red-green + 2 blue-yellow, digit by voice or pad. Normal / possible red-green / possible blue-yellow, "confirm with optometrist" and "Ask about colour needs for driving licence/work". A missed control plate gives "Test not valid, repeat". Palettes are in `rules.json` and are not clinically validated.
- **E test and clock dial:** the earlier screens run after the colour test; the dial is placed from the power check's far point.
- All results show on the result screen (status dots), the shop PDF and the optometrist report under "Screening, not a diagnosis".
- Emergency short path: the grid only.
- Tests: `ScreeningTest` (engine), `ScreeningFlowTest` (app).

### Phase D: survey upgrade (done)
- `anamnesis_questions.json` → `survey`: 7 colour-coded sections (warning signs, about your eyes, work & daily life, screens, driving & travel, free time & sun, your wishes), 22 questions, each with a "Why we ask" line that is spoken when the customer taps the chip.
- Kinds: never / sometimes / often (labelled slider), yes / no, single choice, multi-select, hours (slider with − / +). Voice or tap; numbers by tap only.
- Skip rules look back only: the driving follow-ups are skipped for "never", the first-progressive question is for 45+, the "which signs" question appears only after a red flag, and emergency signs ask that one question only. Answers of a skipped block are dropped.
- Age, first glasses, known power and budget stay in the core / later questions and are not asked again. The survey fills the older headache, screen-hours, outdoor-work and occupation questions, so nobody is asked twice. Planned time: 22 questions × 10 s, inside the 4-minute limit.
- **Sarvam coverage tracker:** three optional topics (driving, sun time, work day) and their fields (`driveFrequency`, `glareOncoming`, `tiredEyes`, `indoorOutdoor`, `screenHoursDay`, `workHours`, `outdoorHours`). They are extract-only: picked up when the customer mentions them, never asked, so the interview keeps its 8 questions and the red-flag question stays question 3. The phone's survey then asks only what is still open.
- Tests: `SurveyTest` (engine), `SurveyBridgeTest` (app), `test_survey_topics_are_optional_extract_only_and_never_asked` (backend).

### Phase E: vision profile and lens engine (done)
- **Rings** (`engine/profile/VisionProfile.kt`, `rules.json` → `visionProfile`), score 0–100 and a label each:
  - Work: Near-focused / Balanced / Distance-focused (share of near work in the activities)
  - Digital: Light / Connected / Digital Native (under 3, 3–6, over 6 screen hours)
  - Mobility: Rarely / Weekly / Daily, plus "Night-glare sensitive"
  - Leisure: Low / Medium / High Sun & UV Exposure
  - a ring whose questions were not answered says "not answered" (never an invented score).
- **Lens engine** (`engine/profile/LensEngine.kt`): reads the products, texts, "why" templates and links from `lens_catalog.json` (unchanged) and follows its selection order: safety gate → lens type → design → materials (UVProtect always) → coating → up to 2 second pairs. The numbers of the trigger rules are in `rules.json` → `lensEngine`.
  - Each of the 27 catalogue entries has one trigger in `LensEngine.triggers`; a test fails when the catalogue and the engine differ.
  - Budget: low → ClearView / Light 2 + Chrome; mid → SmartLife + Silver (Platinum for heavy screen users, AntiVirus Platinum UV for public-facing work); high → ClearMind (busy work or focus complaints) or SmartLife + Gold. An unknown budget counts as mid.
  - DriveSafe is the main design for daily drivers with glare or poor low-light vision (with its own coating) and a second pair for weekly drivers. A reduced contrast test counts as poor low-light vision.
  - Exclusions: never BlueProtect with BlueGuard (or PhotoFusion X, which contains it); PhotoFusion X only for outdoor hours + "one pair" wish, never for night glare; myopia management is a referral, never a lens.
  - Our own choices where the catalogue leaves room: DriveSafe main vs second pair, one sun lens per customer (polarised first), a mid-budget progressive gets Light 2 only as a first progressive.
- Each suggestion has the product name, what it does (catalogue text, English), 1–2 "Why for you" lines from this customer's answers (Hindi and English) and the ZEISS link. Label: "Suggested ZEISS lens family – optician confirms".
- The result screen shows the rings card and the lens cards; the shop PDF and the optometrist report carry the rings, lens ids and reasons. Red flag or child myopia: rings stay, lens cards are replaced by the referral card.
- Tests: `LensEngineTest` (one per trigger and exclusion, 5 personas: 25-year student, 35-year IT worker, 52-year shopkeeper who drives at night, 60-year farmer, 10-year child with −2.00), `ProfileViewTest`.

### Phase F: UI redesign (done)
- **Tokens** (`ui/Tokens.kt`, `ui/Theme.kt`): the colours from the brief (background #F7F9FC, surface #FFFFFF, hairline #E3E8EF, ink #0B1B2B, secondary ink #5A6B7D, primary #1A5CFF, success #1E9E6A, warning #E8A317, urgent #D93838; section colours Work #2F80ED, Digital #A3D33A, Mobility #6B7785, Leisure #F2A93B) and a full dark set. The app follows the phone's light / dark setting.
  - Added for contrast: darker "ink" versions of success, warning and urgent for text on their soft tints. A test checks every text pair for 4.5:1 in both themes.
  - The vision tests (power check, screening) always run in the light theme: black targets on white at full brightness.
- **Fonts:** Inter (Latin) + Noto Sans Devanagari (Hindi), both OFL, merged into one TTF per weight (`res/font/inter_deva_*.ttf`, built by `tools/build_fonts.py`), because Compose does not fall back glyph by glyph inside one font family. Tabular figures are on everywhere. The glyph-coverage test still passes.
- **Type and shape:** display 32 sp semibold, title 24 sp, body 18 sp, label 15 sp; cards 24 dp, buttons 16 dp, pill chips, hairline borders, a soft shadow only on hero cards, 24 dp screen padding.
- **Home:** hero card "Start new customer", three tiles (Past sessions, Shop tools, Frames), bridge chip "Connected" / "Offline – queued N" (tap = try to send now; checked every 20 s while Home is open).
- **Guided flow:** segmented progress bar and "Step n of m" in the interview and the survey; the survey has full-colour section intros, large answer cards with a haptic tick, labelled Never / Sometimes / Often sliders, a "Why we ask?" chip that opens a sheet and speaks, and a 72 dp floating mic with a pulsing ring. Steps slide and fade in 250 ms.
- **Test screens:** system bars hidden, live distance pill (green inside the range, amber outside), 3-2-1 countdown, a drawn-in tick after each screening test.
- **Result screen, top to bottom:** urgent banner (if any) → "Your Vision Profile" rings (900 ms draw-in) → Measurements card (power per eye in large numerals, range, method, PD, trial-lens start, screening with status dots) → lens card with a drawn lens (blue tint for BlueGuard, grey gradient for PhotoFusion X, amber edge for DriveSafe) and "Why for you" bullets → second-pair cards → frames → optometrist card → actions. The older detailed cards (functional profile, trial-lens bands, generic lens package) moved into "For the shop". Red flag or child myopia: the referral card replaces the lens cards.
- **All older screens** take the new look through the shared scaffold, buttons and cards and the theme colours; their logic is unchanged.
- **Limits:**
  - Icons are drawn in code (mic, lens, tick, dots); the Material Symbols Rounded font is not bundled.
  - The older screens were restyled through the shared components, not redesigned one by one; skeleton loaders were not added (the empty history has a friendly card).
  - The interview keeps its existing voice strip; the 72 dp mic is on the survey.
  - Nothing here has been seen on a phone yet: spacing at font scale 1.3 and the dark theme need a look on the S23 FE.
- Tests: `DesignTokensTest`, `BundledFontTest`.

### Phase G: outputs and bridge (done)
- **QR** (ZXing core 3.5.3, Apache-2.0, encoding only): a QR card on the result screen.
  - Report sent and backend reachable: the QR is the link `<server>/profiles/<id>`.
  - Otherwise a compact JSON summary that needs no network: `caseId`, `ageBand`, `tag` (URGENT / CHILD_MYOPIA / ROUTINE), the 4 ring labels and scores, per-eye power range + method + lens used, PD, screening results, lens ids, `appVersion`. No name, photo or phone number; the exact age is not in it. About 600 bytes.
- **WhatsApp share:** "Share on WhatsApp" opens a consent checkbox first, then shares a PNG of the summary (tag, rings, power ranges with the method, screening, lens family, the QR) to WhatsApp, or the phone's share sheet when WhatsApp is not installed. The file sits with the session's PDFs and is deleted with the session.
- **Shop PDF:** the four rings drawn, the screening results under "Screening, not a diagnosis", the measurement method per eye, the lens cards with their reasons, and the referral block for red flags and child myopia.
- **Backend 1.2** (`backend/app/main.py`, `static/index.html`):
  - accepts `triage`, `visionProfile`, `screening`, `lens`, `survey` (older apps without them still work; the privacy rules cover them);
  - tags every report URGENT / CHILD_MYOPIA / ROUTINE (an urgent referral level is always URGENT) and lists URGENT first, then child myopia, then routine, newest first inside each group;
  - the optometrist page shows urgent cases in red with the red-flag reasons at the top, the rings, the phone measurement with its method, the screening lines with status dots, the grid with the squares the person tapped, and the lens suggestion with its reasons;
  - the optometrist can confirm or correct the power (right, left, add) with the reply; the app shows it as the last line of the advice. Older databases get the new column at start.
- Tests: `QrPayloadTest` (payload, QR read back, share lines, PDF rings, confirmed power), `backend/tests/test_vision_profile.py`.

### Phase H: finish (done, 14 Nov 2026)
- Tests: engine 375, app 370, backend 83, all passing (`--no-daemon --max-workers=2 -Xmx2g`).
- Debug APK: `D:\drishti-data\drishti-vision-profile.apk` (85.7 MB, not in git).
- `README.md` (features, safety rules, lens-assisted mode, screening tests, new flow) and `docs/ARCHITECTURE.md` (section 6) updated.

### Phase I: fixes from the first phone test (done, 15 Nov 2026)
Found on the phone in reports S2 (routine) and S32 (emergency short path). Each fix has a unit test in `PhoneTestFixesTest` (engine) and `PhoneTestFixesAppTest` (app).
1. **Both eyes always printed.** A skipped eye is stored with its reason and every output (result screen, PDF, WhatsApp picture, QR, optometrist report) shows "L: not measured – skipped". The eye check's last screen lists both eyes and its main button goes back to the eye that has no reading.
2. **Lens-assisted mode.** The suggested lens stays about 2 D weaker than the first pass (`lensAssisted.suggestOffsetD`). New: if the residual through the lens is still at or beyond `repeatIfResidualFromSeD` (−3.75 D), staff are asked for a stronger lens (about 2 D weaker than the new total) and the test is repeated, at most `maxStrongerRepeats` (1) times.
3. **Near point of a short-sighted customer.** "Possible long-sightedness" is never shown next to a measured minus power (also for sessions stored earlier). Staff are asked to put the distance glasses or trial lens on for the near check; with the correction on the far power counts as 0 D, without it the near point is not judged.
4. **Contrast.** Failing even the 100 % step = "Test not valid – repeat with glasses/trial lens on" (no "Reduced", no cataract note, and it no longer counts as poor low-light vision for the lens engine); the screen offers the repeat at once. An eye with 1.00 D or more (`screening.contrast.wearCorrectionFromAbsD`) is told to wear its correction first. `rules.json` must start the steps at 100 %.
5. **Ring labels.** The Leisure label now comes from the ring's own score (`visionProfile.leisure.mediumFromScore` 34, `highFromScore` 67), so 17 is "Low".
6. **Report header and disclaimer.** The header has the date, the age band and the session's language. The disclaimer is printed once (top of each PDF, end of the WhatsApp picture).
7. **Grid red flag.** `AMSLER_DISTORTION` comes only from a grid result and names the eye ("… on the grid test – left eye"). The customer's own answer "straight lines look bent" is a separate flag, `BENT_LINES_REPORTED`, worded as an answer, so the referral is not lost.
8. **Emergency short path.** Rings say "Skipped – emergency short path"; the header says "Short path (emergency)".
9. **URGENT and CHILD_MYOPIA reports.** The power lines read "For the optometrist only: R … D" instead of "Your approximate power…", and that result is not read aloud during the check.
- Tests: engine 383, app 379, backend 83 = 845, all passing.
- Debug APK rebuilt: `D:\drishti-data\drishti-vision-profile.apk`; pre-release v1.1 asset replaced.
- Still to check on the phone: the stronger-lens repeat, the "glasses on" step before the near check, the contrast repeat screen. S2's own case (−1.50 lens, residual −3.25 D) is below the −3.75 D repeat limit: lower `repeatIfResidualFromSeD` (for example to −3.00) if the optometrist wants a repeat there too.

## Teleconsult (`feature/teleconsult`, from `feature/vision-profile`, 15 Nov 2026)
Patient registers in the app, takes the test, asks for a consultation; a verified optometrist accepts in a web portal, talks by chat / WhatsApp / video, and sends advice or an e-prescription the patient sees in the app. Payments are a placeholder. Demo runs on the laptop over LAN (http); production needs HTTPS hosting, real OTP login and payment integration.

### Teleconsult phase 1: backend accounts and data (done)
- `backend/app/teleconsult.py`, mounted under `/api` by `create_app`. Tables `tc_users`, `tc_consultations`, `tc_messages`, `tc_prescriptions`, `tc_settings` in the same SQLite file.
- **Roles:** patient, optometrist, admin. Passwords hashed with bcrypt; JWT (HS256, 7 days) in `Authorization: Bearer`; a role check on every endpoint. The JWT key is `JWT_SECRET`, or a random one kept in the database.
- **Patient:** name, phone, age, gender, language; the consent sentence is stored with its time. `DELETE /api/me` removes the account with every consultation, report, message and prescription.
- **Optometrist:** registers as PENDING; only APPROVED ones see cases or are offered to patients. Phone and email are never shown to patients.
- **Admin:** one account from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `backend/.env` (never committed); approve / reject endpoints.
- **Consultation:** REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED / CLOSED; priority from the report's own tag; `payment_status = "not_required (demo)"`. The report is the app's existing optometrist report and passes the same privacy check (no name, photo or phone inside it). The patient's phone is shown only to the optometrist who accepted.
- **Chat** (text, optional picture up to 600 KB), **video** (a `meet.jit.si/drishti-<12 chars>` room posted into the chat), **WhatsApp** links (`wa.me`, prefilled Hindi / English greeting with the case number; to the optometrist only if they allow it).
- **Advice / e-prescription:** sphere, cylinder, axis per eye, add, PD, lens advice, referral advice, follow-up date, notes, optometrist name + registration number → PDF (fpdf2, the app's Inter + Devanagari font).
- Tests: `backend/tests/test_teleconsult.py` (13): auth, roles, approval gate, consultation flow, chat pictures, data deletion. Backend total 96.

### Teleconsult phase 2: optometrist web portal (done)
- `backend/app/static/portal.html`, served at `/portal` and `/portal/admin`: one page, no build step, no outside scripts. Same design tokens as the app (Inter + Devanagari font served from the backend, #1A5CFF, 24 px cards, light / dark, works on a phone).
- Register, login, "pending approval" screen (checks again every 10 s).
- Dashboard: tabs Urgent | Child myopia | Routine | My active | Completed with counts, urgent cases with a red edge, refresh every 10 s, NEW badge and a sound for a new case (after "Sound: on"), availability switch.
- Case view: patient name, age, language; rings, power with the method, PD, screening lines, the grid with the marked squares, red-flag reasons, lens suggestions with their reasons, interview answers; Accept.
- After accepting: chat (polls every 3 s, pictures), WhatsApp button, video call (Jitsi room, link posted into the chat), prescription form with the lens catalogue as chips → PDF, close.
- Profile page (fee, clinic, languages, WhatsApp permission). Admin page: approve / reject.
- `python -m app.seed_demo`: 2 approved + 1 pending optometrist and 3 patients, all named DEMO, with one URGENT, one CHILD_MYOPIA and one ROUTINE request. Password from `DEMO_PASSWORD`.
- Tried in Chrome against the running backend: queue, accept, chat, prescription, admin page. All text is inserted as text nodes (no HTML from patients or chat).
- Backend tests: 97.

### Vision profile branch: open items
1. **Tried once on a phone (15 Nov), fixes above.** Still to check on the S23 FE: the survey by voice, the lens-assisted steps, the grid's real square size (5 mm with a ruler) and the 27–33 cm gate, the contrast letters at 3 % and 1.5 % (Night light off), the colour plates, the dark theme, font scale 1.3, the QR scan and the WhatsApp picture.
2. Colour-plate palettes, contrast steps and the "Normal at 3 %" limit are our own starting values: an optometrist should review them. A phone display cannot guarantee low contrast levels.
3. A phone screen is narrower than 10 cm, so the grid shows fewer columns than 20 (the rows are complete).
4. Lens triggers are our own rules. Lens product descriptions on the cards are English only (the reasons are in Hindi too).
5. Survey numbers (hours) are tap only; voice works for the choices.
6. The profile survey is not editable from "Edit answers" after the results.
7. The report is sent under the session's consent. Before real use this needs the same review as the bridge itself (DPDP, per-optometrist login).
8. Icons are drawn in code; Material Symbols Rounded is not bundled. Older screens were restyled through shared components, not redesigned one by one.
9. On this PC Gradle is sometimes refused access to its own build-output folders; clearing the named folder and running again works.

## Open items
1. ~~Install the latest APK on the S23 FE and confirm voice works~~ **Done (11 Nov 2026):** voice works on the S23 FE.
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
6. ~~Room encryption for stored sessions~~ **Done (12 Nov):** SQLCipher with a Keystore-wrapped key. The plain-to-encrypted migration still has to be checked on the phone with existing sessions (open History after installing).
7. APK size (~59 MB since SQLCipher and the fonts, mostly OpenCV + MediaPipe): consider dropping armeabi-v7a (~15 MB).
8. Frame images (Kaggle Glasses dataset) have an unknown licence: demo use only.
9. Every clinical number in `rules.json` still needs optometrist review before field use.
10. Addition 7 (temple length from a ~45° side capture) is not implemented.
11. `deviceCorrection` thresholds (5 sessions, 0.5 D spread, slope 0.7–1.3, 3 D entry-error cut-off, ±1 D cap) are starting values: review with pilot data. The Room DAO and migration are checked against the exported schema in unit tests, not on a device.
12. Power path on the phone:
    - Check the quick far point against the full test on a few myopes.
    - Check that "dhundhla" is recognised while the phone moves, and that the TTS instruction does not trigger it.
    - The `functionalProfile` hours are starting guesses.
    - Fixed after the 12 Nov phone test: "Not sure" / "No" now opens the camera measurement straight away (no glasses-off screen, no intro or Start button; the camera permission dialog appears by itself the first time). Glasses off and which eye to cover are spoken on the measuring screen. The distance is shown large, and the live line shows from the first reading (Blur still waits until the phone has been close). The staff Camera check also shows the live line.

13. Remote Optometrist Bridge is a demo setup:
    - one shared token, no per-optometrist login, no audit log
    - the token is in plain app preferences (backups are off)
    - before real use: proper authentication, hosting with encryption at rest, a retention rule, a DPDP Act review
    - the optometrist's reply is shown as written (not translated)

14. Sarvam AI voice interview:
    - Not yet tried with a real Sarvam key on the phone. Check the speech-to-text quality for older voices and shop noise, the latency per turn (3 calls), and the cost per interview.
    - The customer's voice goes to Sarvam (a third party): the consent says so, but a data-processing agreement and a DPDP review are needed before real use.
    - Free speech may include a name. The transcript stays in the shop (staff view and shop PDF), but it is not filtered.
    - The red-flag keyword list and the banned-word list need clinical review and a native-speaker check.
    - If the customer answers many topics at once, it can end before 5 questions (nothing left to ask).

## Next steps
1. Phone: install via adb, voice check, then the checklist above; feed measured values back into `rules.json`.
2. 9 Nov: record the mid-sprint update with demo mode (try-on uses the live camera).
3. Fix whatever the phone test finds; re-tune thresholds; native-speaker Hindi pass.
4. Then: APK size, pilot data for the priors.
