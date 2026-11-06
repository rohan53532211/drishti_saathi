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
| 11 | **Two-frame comparison screen** (addition 3) | Next |
| 12 | **New interview fields → frame scoring** (addition 4) | |

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
