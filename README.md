# Drishti Saathi

**Drishti Saathi** is a phone-only helper for small optical shops in India. A voice-guided Hindi/English interview, a compulsory camera-measured reading eye check (random letters for far, a random sentence for near, the power shown as a ±0.25 D range) and a face scan give the staff a starting trial lens, referral flags, a lens package and ranked frames with live try-on, in about 5 minutes on one phone. Results go into a shop PDF and a Hindi customer PDF, and, with consent, to a remote optometrist who can reply from a browser. No name or photo ever leaves the phone. It is a starting point for the shop's trial-lens test, not a replacement for it.

### Download the APK

Get **DrishtiSaathi-v1.0.apk** from [Releases](https://github.com/rohan53532211/drishti_saathi/releases) (tag `v1.0-hackathon`).

### Install (Android 8+)

1. Download the APK on the phone (or copy it over USB).
2. Tap the file. When Android asks, allow **Install unknown apps** for your browser or file manager (Settings → Apps → Special app access → Install unknown apps).
3. Tap **Install**, then open **Drishti Saathi**. Allow camera and microphone when asked.
4. First launch opens **Phone setup** (bank-card screen size, quick camera test). You can skip it for now.

### 5-step demo

1. **Demo mode:** Home → **Shop tools** → **Demo mode: sample customers** → pick the Student, Shopkeeper or Driver. The full results open at once (every screen marked DEMO).
2. **Real customer:** Home → **Start new customer** → consent → language → answer the questions by voice or tap.
3. **Eye check (everyone):** glasses off, cover one eye, start close and move the phone away; tap **Can't read clearly** (or say "dhundhla") when you can no longer read the letters. Then, for near problems or 38+, both eyes: hold far, move closer, tap when the sentence blurs.
4. **Results:** power range per eye, reading add range (38+), referral at the top if urgent, lens package, frames, try-on; share the shop and customer PDFs.
5. **Optometrist bridge:** start the backend (below), set its URL in **Shop tools → Optometrist bridge settings**, tap **Send to optometrist** on the results, open the same URL in a browser as the optometrist, reply, and watch the reply appear on the phone with its case number.

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
- interviews the customer
- measures face width and PD with the front camera
- runs simple screen tests: far point, near point, Amsler grid
- gives an **estimated band** and a **starting trial lens** for the staff
- flags anyone who should be referred to an eye doctor
- ranks frames for the face and budget, with a live try-on
- produces a shop PDF and a Hindi customer PDF
- can send the profile to a remote optometrist, with the customer's consent, and show the reply (`backend/`)

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.
> The result is a starting point for the shop's trial-lens test, not a replacement for it.

**Privacy:** everything stays on the phone.
- Camera frames are processed in memory and never saved.
- Nothing is uploaded without spoken and tapped consent, and uploads never include the customer's name or photo. There are two optional uploads, each with its own consent screen. The Remote Optometrist Bridge sends the profile; its server rejects names, photos and contact fields too. The Voice interview (AI) sends the customer's spoken answers to the shop's backend, which transcribes them with Sarvam AI. The Sarvam key stays on the backend; the transcript stays in the shop (staff view and shop PDF).
- Any session can be deleted.
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

Home has two big buttons, **Start new customer** and **Past sessions**, and a small **Shop tools** link. There is no staff switch.

1. Consent, then the language (Hindi or English).
2. Questions: the AI voice interview when the backend answers, otherwise the scripted questions. Red flags come right after the main problem; the power question ends this part. No question numbers. Texts, follow-up rules and voice keywords are in `app/app/src/main/assets/anamnesis_questions.json`; the backend reads its interview topics from the same file.
3. Power: known → enter it, then the eye check anyway (both are shown). Not sure / No → the eye check, at once. The eye check is compulsory for everyone, also after a red flag.
4. Eye check, one test style only (reading). Far, right eye then left, other eye covered: 5 random Sloan letters (C D H K N O R S V Z) at 20/20 size, distance-locked, a new set every 3 cm; "Can't read clearly" freezes the distance d → power range centre −1/d rounded up to the 0.25 D step, shown ± 0.25 D (30 cm → −3.25 to −3.75 D). Near, both eyes, for NEAR / BOTH or 38+: a new random sentence each attempt (`assets/reading_sentences.json`, chosen language) in small print; 38+ gives a reading add range (1/WD − A/2 ± 0.25 D); under 38 no plus number, the near point is compared with the age-expected one. The range centre feeds lens and frame suggestions. Each step has a header, Skip, a short result and Do it again. "Straight lines look bent" = yes refers (there is no grid in the eye check).
5. Face measurement for frame fit (optional card on the forehead, can be skipped).
6. The remaining lifestyle and frame questions (only unanswered ones).
7. Results: vision profile, power and source, referral, lens package, frames, try-on and compare. Staff details are folded under **For the shop** (notes, measurements, transcript, verification, **Edit answers**).
8. Send to optometrist, share PDFs, **Finish**.

An urgent red flag no longer ends the flow early: the eye check still runs, then the results open with "Please see an eye doctor today" at the top (and on both PDFs). Everyone else gets the closing line recommending a complete eye check-up with an optometrist.

**Shop tools:** Phone setup (one time: bank-card screen size and a quick camera test; it opens by itself on first launch, "Skip for now" leaves a reminder on home), Demo mode, Optometrist bridge settings, Device checks (camera check, and Precision mode: full eye test = the same reading test, far for both eyes and near, ending on the same results screen with PDFs, saved as a session).

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

This is a Phase 1 prototype. The clinical numbers in `rules.json` still need an optometrist's review, and the Hindi needs a native-speaker proofread, before any use in a shop. See `PLAN.md`.
