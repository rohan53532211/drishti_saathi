# Drishti Saathi

**Drishti Saathi** is a phone-only helper for small optical shops in India. On one phone, in about 10 minutes, it runs a Hindi/English voice survey, measures the power of each eye with the camera (through a known lens for strong powers), runs screening tests (grid, contrast, colour, E test, clock dial), and gives the staff a vision profile, a starting trial lens, a suggested ZEISS lens family with the customer's own reasons, and ranked frames with live try-on. Warning signs come first: a red flag stops every lens suggestion and sends the customer to an eye doctor the same day. Every report goes to the shop's optometrist, who can confirm the power and reply from a browser. No name or photo ever leaves the phone. It is a starting point for the shop's trial-lens test, not a replacement for it.

### Download the APK

Get **DrishtiSaathi-v1.0.apk** from [Releases](https://github.com/rohan53532211/drishti_saathi/releases) (tag `v1.0-hackathon`).

### Install (Android 8+)

1. Download the APK on the phone (or copy it over USB).
2. Tap the file. When Android asks, allow **Install unknown apps** for your browser or file manager (Settings → Apps → Special app access → Install unknown apps).
3. Tap **Install**, then open **Drishti Saathi**. Allow camera and microphone when asked.
4. First launch opens **Phone setup** (bank-card screen size, quick camera test). You can skip it for now.

### 6-step demo

1. **Demo mode:** Home → **Shop tools** → **Demo mode: sample customers** → pick the Student, Shopkeeper or Driver. The full results open at once (every screen marked DEMO).
2. **Real customer:** Home → **Start new customer** → consent (read aloud) → language → the core questions and the vision-profile survey, by voice or tap.
3. **Camera distance + PD,** then the **power of each eye:** glasses off, cover one eye, start close and move the phone away; tap **Can't read clearly** (or say "dhundhla"). From −3.75 D the app asks staff to hold a known minus lens and repeats the test through it.
4. **Screening tests:** grid at 30 cm, contrast letters, colour plates, E test, clock dial. Each has Skip.
5. **Results:** urgent banner (if any), "Your Vision Profile" rings, measurements with the method, the lens card with "Why for you", second pairs, frames and try-on, QR, **Share on WhatsApp**, PDFs.
6. **Optometrist bridge:** start the backend (below) and set its URL in **Shop tools → Optometrist bridge settings**. Reports are sent by themselves (queued while offline). Open the same URL in a browser as the optometrist: urgent cases are at the top in red; confirm the power, reply, and watch the reply appear on the phone.

### Screenshots

See [`screenshots/`](screenshots/) (placeholder, to be filled).

### Run the backend (optometrist bridge, optional AI voice interview)

```sh
cd backend
python -m venv .venv
.venv\Scriptsctivate            # macOS/Linux: source .venv/bin/activate
pip install -r requirements-dev.txt
set BRIDGE_TOKEN=pick-a-long-random-string     # macOS/Linux: export BRIDGE_TOKEN=...
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

The optometrist page is `http://<laptop IP>:8000/`. For the AI voice interview, put `SARVAM_API_KEY` in `backend/.env` (never in the APK). Details: [`backend/README.md`](backend/README.md).

---

## About

An Android app for small optical shops. It does a voice-guided, bilingual (Hindi/English) pre-consultation on one phone before the shop's own trial-lens test.

The app:
- asks the core questions and a vision-profile survey (voice or tap, Hindi or English)
- checks for warning signs first and sends anyone with one to an eye doctor the same day
- measures PD and face width with the front camera
- measures the power of each eye by blur distance, through a known lens for strong powers
- runs screening tests: Amsler grid, contrast, colour plates, tumbling E, clock dial
- shows a vision profile (work, digital, mobility, leisure) and a suggested ZEISS lens family with reasons from the customer's own answers
- gives an **estimated band** and a **starting trial lens** for the staff
- ranks frames for the face and budget, with live 2D and 3D try-on
- produces a shop PDF, a Hindi customer PDF, a QR summary and a picture to share on WhatsApp
- sends every report to the shop's optometrist and shows the reply (`backend/`)

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.
> The result is a starting point for the shop's trial-lens test, not a replacement for it.

**Privacy:**
- Camera frames are processed in memory and never saved.
- **The report of every check is sent to the shop's optometrist** (the Remote Optometrist Bridge, `backend/`). The consent at the start says so and is read aloud. The report holds answers and measurements only: never a name, a photo or a phone number, and the server rejects those fields too. While the phone is offline or no server is set, the report waits in a queue and is sent when the backend connects.
- The Voice interview (AI) is optional and has its own consent screen. It sends the customer's spoken answers to the shop's backend, which transcribes them with Sarvam AI. The Sarvam key stays on the backend; the transcript stays in the shop (staff view and shop PDF).
- The WhatsApp picture is shared only after a consent checkbox.
- Any session can be deleted; its server copy, PDFs and shared picture go with it.
- Stored sessions are encrypted (SQLCipher, with the key protected by the Android Keystore), and backups are turned off.

`PROJECT_BRIEF.md` is the spec. `PLAN.md` holds the build status, the decisions made since the brief, and the open items.

## Repository layout

| Path | What |
|---|---|
| `app/engine` | Pure Kotlin engine: priors, far-point protocol, Bayesian band, referral rules, frame ranking. No Android dependencies. |
| `app/app` | Android app (Kotlin, Jetpack Compose, minSdk 26): interview, voice, camera (MediaPipe face landmarker), screen tests, try-on, Room storage, PDFs |
| `app/app/src/main/assets/rules.json` | Every threshold and clinical constant (strictly parsed) |
| `backend/` | Remote Optometrist Bridge (FastAPI + SQLite, optometrist web page) and the Sarvam AI voice interview (`/anamnesis/*`). See `backend/README.md`. |
| `tools/` | Python scripts: ANSUR II priors analysis, far-point simulation, frame cut-outs for try-on, bundled font build (`build_fonts.py`) |

## Build

You need JDK 17 or newer (developed on JDK 21) and the Android SDK. `app/local.properties` is not committed; create it with `sdk.dir=<path to Android SDK>`.

```sh
cd app
./gradlew :engine:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                     # app/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Try-on frame images (not in git)

The frame photos F01–F30 come from the Kaggle Glasses dataset (egorovlvan). Its licence is unknown, so the images are for demo use only and are not committed.

Without them the app still builds and runs; try-on just shows no frame images. To create them, download the dataset into `glasses/` and run:

```sh
python -I tools/frames_cutout.py glasses/<photo folder> app/app/src/main/assets/frames <meta.json>
```

The script writes the transparent PNGs that the try-on loads, plus their measurements. The frame data itself (`frames.json`, `frames/more_frames.json`) is committed.

## Customer flow

Home has a hero card **Start new customer**, three tiles (**Past sessions**, **Shop tools**, **Frames**) and the optometrist bridge chip ("Connected" or "Offline – queued N").

1. **Consent** (read aloud; it covers the report to the optometrist), then the language.
2. **Core questions:** the AI voice interview when the backend answers, otherwise the scripted questions. The red-flag question stays question 3. Texts, follow-up rules, voice keywords, the survey and the backend's interview topics are all in `app/app/src/main/assets/anamnesis_questions.json`.
3. **Red banner, if a red flag was reported:** "Please see an eye doctor today" with the reasons, spoken. The check continues behind it.
4. **Vision-profile survey:** seven colour-coded sections, one question per screen, "Why we ask?" on every question. Blocks that don't apply are skipped. After a red flag it first asks which signs.
5. **Camera distance + PD:** face measurement (optional card on the forehead).
6. **Power of each eye:** known power → enter it, then the eye check anyway. Far, right eye then left, other eye covered: 5 random Sloan letters, distance-locked; "Can't read clearly" (or "dhundhla") freezes the distance d → power range centre −1/d, shown ± 0.25 D. Near, both eyes, for near problems or 38+: a random sentence; 38+ gives a reading add range.
   - **Lens-assisted mode:** from −3.75 D the blur point is closer than about 27 cm, where 1 cm is about 0.18 D. Staff hold a known minus lens about 2 D weaker (trial lens or the customer's old glasses), type its power on a number pad, and the test is repeated through it. Total = lens + residual, with a vertex correction from 4 D. "Use a weaker lens" when the letters never blur. Stronger than −8 D adds "High myopia – optometrist must confirm; eye-health check advised". Every output states the method, e.g. "Measured through −2.00 D lens, residual −2.25 D, total −4.25 D (range −4.00 to −4.50)".
7. **Screening tests** (one eye at a time, live distance pill, full brightness; staff turn off Night light): Amsler grid at 27–33 cm with tap-to-mark, contrast letters (100 % down to 1.5 %), colour plates (our own generated dot plates with a control plate), then the tumbling-E test and the clock dial. All results are labelled "Screening, not a diagnosis".
8. The remaining frame and budget questions (only unanswered ones).
9. **Results:** urgent banner (if any) → "Your Vision Profile" rings → measurements (power per eye with range and method, PD, trial-lens start, screening) → lens card ("Suggested ZEISS lens family – optician confirms") with "Why for you" → second pairs → frames, try-on, compare → QR → optometrist card, **Share on WhatsApp**, PDFs. Staff details are folded under **For the shop**.

### Safety rules

- **Everyone gets the check, and the report always goes to the optometrist.**
- **Red flags:** sudden loss of vision, eye pain or redness, new flashes or floaters, a curtain or shadow, double vision, wavy or missing lines on the grid. With a red flag: the red banner first, the check continues, **no lens or frame is suggested**, the power is labelled "For the optometrist only", and the report is marked URGENT with the reasons at the top.
- **Emergency signs** (sudden vision loss, curtain or shadow, new flashes): short path only, the power of each eye and the grid (about 2 minutes), then the urgent referral.
- **Under 18 with any minus power:** the power is measured, no lens is chosen, "Get your child checked by an optometrist (myopia control)", report tag CHILD_MYOPIA. Under 18 without myopia may get a children's lens family, labelled "optometrist confirms".
- Rules and thresholds: `rules.json` → `safety`, `lensAssisted`, `screening`, `visionProfile`, `lensEngine`. Products and their texts: `lens_catalog.json`.

**Shop tools:** Phone setup (one time: bank-card screen size and a quick camera test), Demo mode, Optometrist bridge settings, Frame catalogue, Device checks (camera check, Precision mode, 3D alignment).

## Demo mode

Demo mode is for demonstrations and recordings without a real customer:

1. On the home screen, tap **Shop tools**.
2. Tap **Demo mode: sample customers** and pick one:
   - Student, 21: distance blur, long screen use
   - Shopkeeper, 48: reading difficulty
   - Driver, 56: sudden flashes and floaters (shows the referral path)
3. The results open with answers, camera measurements and test results already filled in. From there you can go through the summary, frame ranking, try-on, comparison and the PDFs.

Every demo screen and PDF is marked **DEMO**. The sample customers are not real people.


## Status

This is a hackathon prototype. Before any use in a shop:
- every clinical number in `rules.json` needs an optometrist's review, and the Hindi needs a native-speaker proofread;
- the colour plates and the contrast steps are our own and are not clinically validated; a phone screen cannot show the full 10 cm grid (whole columns are left out at the sides);
- the lens triggers are Drishti Saathi's own rules, not ZEISS's; the product descriptions on the lens cards are in English;
- the vision-profile branch (survey, lens-assisted mode, screening tests, new screens) has unit tests but has not been tried on a phone yet.

See `PLAN.md` for the build status and the open items.
