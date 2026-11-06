# Drishti Saathi — Project Brief for Claude Code

Read this whole file before writing code. It contains every decision, formula, rule and dataset finding made so far. Spec doc (same content, more prose): https://claude.ai/artifact/FJ2JQiZatsBTRmqmRpX9q1

---

## 0. Context and deadlines

- Competition: ZEISS hackathon, theme **"No-Device Vision Intelligence & Accessible Clinical UX"**.
  Problem statement: *"In many retail and rural optical stores, there is no dedicated eye-testing hardware — just a phone or a counter. Design a lightweight way to capture enough information (via voice interview, photo, or simple on-screen tests) to generate a useful lens/eyewear recommendation for a walk-in customer, without any specialized device."*
- Build window: 30 Oct – 15 Nov 2026.
- **Mid-sprint progress submission: by 9 Nov 2026** → Phase 1 (typed flow) must work and be screen-recorded.
- **Final working prototype + deck: 15 Nov 2026, 11:59 PM IST.**
- Local data folder: `D:\drishti-data\`
  - `ansur2.csv` — ANSUR II anthropometry (downloaded)
  - `glasses\` — Kaggle Glasses dataset, 3.22 GB (downloaded)
  - App project goes in `D:\drishti-data\app\`

---

## 1. Product in one paragraph

Drishti Saathi is a native Android app that turns a small optical shop's ordinary phone into a calibrated, guided pre-consultation. It runs a Hindi voice/typed interview, a face capture, and distance-calibrated on-screen vision tests, then gives the shop: a structured visual-needs profile, an **estimated power band with confidence** (myopia and reading add only), a **starting trial lens** for the shop's existing hand test, a **lens package** (type, coatings, material), **ranked frames with predicted lens edge thickness in mm**, and a **referral flag** for warning symptoms.

**Positioning (important):** shops already do a manual trial-lens test. We do NOT replace it or the optometrist. We fill every gap around it (history, near needs, astigmatism flag, red flags, fit, thickness), and when no hand test exists we give a starting band the shop verifies. We never diagnose disease and never output a prescription.

---

## 2. Research basis (cite these; do not overclaim)

| Capability | Method | Reported accuracy | Source |
|---|---|---|---|
| Myopia (SE) | Far point with selfie-camera distance, SE = −1/d | R = 0.91, mean abs error 0.65 D, 201 eyes | Luo et al. 2022, TVST — https://pmc.ncbi.nlm.nih.gov/articles/PMC8899852 |
| Myopia, blue light | Blue OLED focuses ~0.67 D in front of white; three-line target | Mean diff −0.08 D, LoA ≈ ±0.6–0.8 D, 144 eyes | Salmerón-Campillo et al. 2024, J Optom — https://www.journalofoptometry.org/en-measuring-visual-acuity-spherical-refraction-articulo-S1888429623000420 |
| Reading add | Push-up near point, camera distance ~20 Hz, blue target | 82.5% within 0.50 D, n = 20 | J Optom 2025 — https://pmc.ncbi.nlm.nih.gov/articles/PMC12205635 |
| Distance from camera | MediaPipe Iris, iris diameter 11.7 ± 0.5 mm | Depth error 4.3% mean (4.8% with glasses) | https://research.google/blog/mediapipe-iris-real-time-iris-tracking-depth-estimation/ |
| App quality | 24 store apps reviewed; only 3 clinically validated; size + brightness calibration mattered most | — | https://www.reviewofoptometry.com/news/article/most-smartphone-apps-fare-poorly-at-vision-testing |

Known limits: single targets cannot detect astigmatism; hyperopia not measurable at arm's length; worse accuracy in children and young accommodating adults.

India priors (adults 30+, systematic review): myopia 27.7%, hyperopia 22.9%, uncorrected presbyopia 33% — https://research.sightsavers.org/?p=28171
→ Hyperopia is nearly as common as myopia and the phone cannot measure it, so "distance blur reported but no myopia found" MUST route to the hand test.

---

## 3. Innovations (the pitch — implement all by Phase 3)

1. **Bank-card calibration** of screen and camera (ID-1 card width = 85.60 mm).
2. **Distance-locked optotypes**: letters redrawn every frame to constant angular size.
3. **Two-colour far-point self-check**: measure far point with blue and white; disagreement > 0.50 D after LCA correction → unreliable, repeat.
4. **Fogged clock dial** just beyond the far point → astigmatism flag + rough axis.
5. **Near-point add fused with age expectation**.
6. **Starting trial lens** printed for staff.
7. **Thickness in mm per frame** for this customer's band, PD and frame size.
8. **Learning loop**: shop enters final trial-lens result + PD-meter reading → paired data → per-phone-model correction.
9. **Live try-on** of real frame photos (from the Kaggle dataset) on the measured face.
10. **Bayesian band output**: age prior × measurement likelihood (σ ≈ 0.8 D) → "−1.00 to −2.00 D, 78% likely" (78% is only an illustration).

---

## 4. Tech stack

| Component | Choice |
|---|---|
| App | Kotlin, Jetpack Compose, minSdk 26 (Android 8.0), targetSdk latest stable |
| Architecture | Single-activity, Compose Navigation, MVVM (ViewModel + StateFlow), Hilt optional |
| Camera | CameraX, front camera 720p |
| Face/iris | MediaPipe Tasks **Face Landmarker** (478 landmarks incl. iris), TFLite |
| Image checks | OpenCV Android (blur via Laplacian variance, brightness, card edges) |
| Frame shape | MobileNetV3-small TFLite (optional; can hand-label 30 frames instead) |
| Speech | Android TextToSpeech hi-IN; Android SpeechRecognizer hi-IN or cloud ASR; offline IndicConformer via sherpa-onnx later. **Typed + button fallback always on.** |
| Engine | Pure Kotlin rules + formulas; constants in `assets/rules.json` (editable without code) |
| Storage | Room, encrypted (SQLCipher or Keystore); store measurements only, never face images |
| Summary export | PDF (Android PdfDocument) + share intent (WhatsApp) |
| Backend (optional, Phase 4) | Python FastAPI + PostgreSQL over HTTPS; catalogue sync, consented paired records |

Minimum phone: Android 8.0, 3 GB RAM, front camera, screen ≥ 5.5".

---

## 5. Modules (full spec)

### M0 Setup and calibration
- Force max brightness, keep screen on during tests.
- Screen scale: user drags rectangle to match a bank card on screen → `screenMmPerPx = 85.60 / rectWidthPx`. Fallback: `DisplayMetrics.xdpi` (mark lower confidence).
- Camera scale: one photo with card flat on forehead; OpenCV Canny + contour + 4-corner fit → `camMmPerPx = 85.60 / cardWidthPx`.
- No card → iris prior (11.7 mm), lower confidence.

### M1 Interview (Hindi + English toggle)
Each question: TTS reads it, user answers by voice OR big buttons, answer read back for yes/no confirmation. Keyword lexicon maps speech to fields.

| Field | Hindi prompt | Type |
|---|---|---|
| age | आपकी उम्र क्या है? | int |
| wearsGlasses | क्या आप अभी चश्मा पहनते हैं? | bool |
| glassesAgeYears | चश्मा कितने साल पुराना है? | int? |
| knownRx | क्या आपके पास पुराना पर्चा/नंबर है? (optional entry: SPH/CYL/AXIS/ADD per eye) | Rx? |
| distanceBlur | क्या दूर की चीज़ें धुंधली दिखती हैं? | bool |
| nearBlur | क्या पास की चीज़ें या मोबाइल पढ़ने में दिक्कत होती है? | bool |
| screenHours | आप रोज़ कितने घंटे मोबाइल या कंप्यूटर देखते हैं? | enum 0-2 / 2-4 / 4+ |
| nightDriving | क्या आप रात में गाड़ी चलाते हैं? | bool |
| outdoorWork | क्या आप ज़्यादातर बाहर धूप में काम करते हैं? | bool |
| occupation / nearWork | आपका काम क्या है? (sets working distance) | enum |
| headacheStrain | क्या सिरदर्द या आँखों में थकान होती है? | bool |
| diabetesOrBp | क्या आपको शुगर या बीपी है? | bool |
| redFlagFlashes | क्या अचानक रोशनी की चमक, तैरते धब्बे या आँख के आगे पर्दा जैसा दिखा है? | bool |
| redFlagOther | क्या अचानक नज़र कम हुई, आँख में दर्द-लाली, या दो-दो दिखता है? | bool |
| budget, style | बजट और पसंद का स्टाइल | enum |

(Have a native speaker proofread the Hindi before demo.)

### M2 Face capture
- CameraX + Face Landmarker. Quality gates: yaw & roll < 5°, eyes open, Laplacian variance above threshold, mean brightness in range. Failed gate → spoken instruction.
- 30 accepted frames, take median, drop outliers.
- `PD_mm = irisCentreDistancePx × camMmPerPx`; `faceWidth_mm = faceContourLeftRightPx × camMmPerPx`.
- **Plausibility gate** from ANSUR II (Section 7): PD/faceWidth ratio outside p1–p99 → re-scan; PD more than 3 × residual SD from predicted-from-face-width → low confidence.
- Tap confirmation of glasses on/off as each test requires.

### M3 Vision tests (one eye at a time, other eye covered by palm)
Distance `d` from iris size, corrected by card scale. 20/20 letter (5 arcmin):

```
h_mm = 2 · d_mm · tan(2.5') ≈ 0.001454 · d_mm
```

1. **Near acuity at 40 cm**: tumbling E, 4-AFC staircase → screen out very poor vision (refer).
2. **Far point, blue then white (no glasses)**: start close, move away until blur, tap. d_b, d_w in metres.
   ```
   SE_blue  = −1/d_b + 0.67
   SE_white = −1/d_w
   reliable if |SE_blue − SE_white| ≤ 0.50 D
   ```
   Use white only if d_w ≤ 0.8 m. No blur at full arm's length → no significant myopia (if distance blur was reported → hyperopia possible → hand test).
3. **Fogged clock dial**: hold ~10 cm beyond far point; user taps darkest spoke → astigmatism flag + approx axis.
4. **Near point (age ≥ 38, distance-corrected or after myopia known)**: move closer until blur → d_np.
   ```
   A = 1/d_np − |SE|
   Add = 1/WD − A/2        (WD default 0.40 m; 0.33 m fine handwork)
   A_min_expected = 15 − 0.25 × age   (Hofstetter)
   ```
   Round Add to 0.25 D, clamp +0.75 … +3.00.
5. **Amsler grid** each eye → any distortion/missing area = urgent referral.

Each test twice; median; spread ≤ 0.25 D high confidence, ≤ 0.50 D medium, else low → repeat.

### M4 Fusion and recommendation rules (put in `assets/rules.json`)

| Condition | Output |
|---|---|
| SE band reliable, no astig flag | Starting trial lens = band midpoint (e.g. −1.50 DS) |
| Astig flag | Start with sphere; staff must check cylinder, or refer |
| age ≥ 40 && nearBlur && !distanceBlur | Reading glasses, add from M3 or age table |
| age ≥ 40 && nearBlur && distanceBlur | Progressive (office/screen users) or bifocal / separate pairs (low budget) |
| screenHours 4+ | Anti-reflective; blue-filter offered as comfort option |
| nightDriving | Anti-reflective |
| outdoorWork | Photochromic or tinted |
| child or sport | Polycarbonate |
| abs(SE) ≥ 4 D | High index 1.60 / 1.67, show thickness |
| Any red flag, or low confidence twice | Referral only, no lens advice |

Age add fallback table (draft, optometrist must review): 40–44 → +0.75 to +1.00; 45–49 → +1.25 to +1.50; 50–54 → +1.75 to +2.00; 55+ → +2.25 to +2.50.

Bayesian band (Phase 3): prior over SE bands from age + India prevalence; likelihood N(measured, 0.8 D); report most probable 1 D band + probability.

### M5 Frame ranking
Catalogue entry: id, name, lensWidth A (mm), bridge DBL (mm), temple (mm) — from the printed "52-18-140" mark — shape, rim type, material, price, image path.

Thickness (minus lens), y = A/2 + decentration, decentration = (A + DBL − PD)/2, n = 1.50 CR-39 / 1.59 polycarbonate / 1.60 / 1.67:
```
t_edge ≈ t_centre + y² · |F| / (2000 · (n − 1))
```
Plus lens: centre thickness = minimum edge + same sag. Default t_centre minus ≈ 2.0 mm CR-39, 1.5 mm high index (tunable).
Check: −4.00 D, PD 62, frame 52-18 → y = 30 mm → edge ≈ 5.6 mm CR-39, ≈ 4.2 mm 1.67. **Write a unit test for this.**

Score = w1·fit(frame total width vs face width) + w2·thinness + w3·style match + w4·budget (weights tunable in rules.json). Show top 5 with numeric reason: "fits your face width, edge about 4 mm in 1.60".

### M6 Shop summary and verification
- One staff screen: profile, bands + confidence, starting trial lens, lens package, top frames, referral status.
- Staff enter final trial-lens result + PD-meter reading → stored as paired record.
- PDF export + share; customer copy in Hindi.
- "Delete this session" button.

---

## 6. Safety and wording (non-negotiable)

Referral triggers:
- Urgent same day: sudden vision loss; new flashes/floaters; curtain/shadow; painful red eye; halos with pain; Amsler distortion; new double vision.
- Routine optometrist: diabetes with blur; child under 12; near acuity below threshold; low confidence twice; astig flag; distance blur with no myopia found.

Wording: say "estimated band", "starting trial lens". NEVER "prescription", "diagnosis", "power is". Every summary prints: "यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription."

Privacy: on-device by default; face frames processed in memory only; uploads only with spoken + tapped consent, no name or photo; per-session delete.

---

## 7. Data and datasets

No dataset links phone measurements to eye power → **no refraction model is trained**. Pretrained models + formulas + rules, plus our own paired pilot data.

| Dataset | Location | Use | Status |
|---|---|---|---|
| ANSUR II (6,068 adults, 93 measures; columns `interpupillarybreadth`, `bizygomaticbreadth`, `headbreadth`, gender column) | `D:\drishti-data\ansur2.csv` | Plausibility gate + face→frame width mapping. Script: `tools/ansur_analysis.py` → `app/src/main/assets/anthro_priors.json` | Downloaded; run script |
| Kaggle Glasses dataset (egorovlvan) | `D:\drishti-data\glasses\` — `eyeglasses/<id>/product/<sku>_0.jpg` (front), `_1` angled, `_2` side, `_3` top, `_m0` worn by model; also `sunglasses/` | Pick ~30 front views → background removal → try-on PNG overlays + demo catalogue. Hand-label shape. **Licence unknown: demo use only.** No mm sizes in it. | Downloaded |
| India refractive error review | numbers above | Prior | Use numbers |
| Indian head & facial anthropometry for spectacle frames — https://search.bvsalud.org/gim/resource/pt/sea-224912 | — | Deck argument; sex-specific defaults | Cite only |
| Skipped for now | IndicVoices, face-shape set, MeGlass | — | Not worth it before 15 Nov |

Paired calibration data (our own): app estimate + shop final trial lens + PD meter, per phone model.

---

## 8. Build phases

**Phase 1 — by 9 Nov (mid-sprint): typed flow, no camera**
1. Create project `D:\drishti-data\app` (Kotlin, Compose, minSdk 26). Package `com.drishtisaathi`.
2. Language toggle Hindi/English; large buttons; staff mode.
3. Interview screens (M1 fields) with typed/button input; confirmation screen.
4. Optional manual entry of known prescription (to exercise thickness + frame ranking now).
5. Rules engine (M4) reading `assets/rules.json`; pure Kotlin, fully unit-tested.
6. Thickness + frame ranking (M5) on a placeholder `assets/frames.json` of 10 frames.
7. Summary screen (M6) + referral screen + PDF export + delete session.
8. Room storage of sessions (measurements only).
9. Unit tests: rules, thickness example, add formula, far-point formulas.
10. `tools/ansur_analysis.py` → `anthro_priors.json`.

**Phase 2 — Nov 10–11:** TTS/ASR voice with read-back; CameraX + Face Landmarker; card calibration; PD + face width + quality gates + ANSUR plausibility gate.

**Phase 3 — Nov 10–12:** distance-locked tests (near acuity, blue/white far point, clock dial, near point, Amsler); fusion + confidence + Bayesian band; 30-frame catalogue from Kaggle with real sizes; try-on overlay.

**Phase 4 — Nov 13–15:** pilot on ~20 people, 2–3 budget phones (log PD error vs PD meter, band vs shop trial lens, time per session); bug fixes; demo video; update deck from "proposed" to "built" with screenshots and pilot numbers; optional FastAPI backend.

Validation targets (hypotheses, report honestly): PD MAE ≤ 1.5 mm with card; ≥ 70% of myopia bands within 0.50 D; ≥ 70% of adds within 0.50 D; session ≤ 10 min; all urgent scripted cases referred.

---

## 9. Coding rules
- Keep the engine (rules, formulas, fusion) in a pure Kotlin module with no Android dependencies so it is unit-testable.
- All clinical constants live in JSON, never hard-coded.
- Every output carries a confidence level.
- Commit after each working step.
