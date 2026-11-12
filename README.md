# Drishti Saathi

An Android app for small optical shops. It does a voice-guided, bilingual (Hindi/English) pre-consultation on one phone before the shop's own trial-lens test.

The app:
- interviews the customer
- measures face width and PD with the front camera
- runs simple screen tests: far point, near point, Amsler grid
- gives an **estimated band** and a **starting trial lens** for the staff
- flags anyone who should be referred to an eye doctor
- ranks frames for the face and budget, with a live try-on
- produces a shop PDF and a Hindi customer PDF

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.
> The result is a starting point for the shop's trial-lens test, not a replacement for it.

**Privacy:** everything stays on the phone.
- Camera frames are processed in memory and never saved.
- Nothing is uploaded without spoken and tapped consent, and uploads never include the customer's name or photo.
- Any session can be deleted.
- Stored sessions are encrypted (SQLCipher, with the key protected by the Android Keystore), and backups are turned off.

`PROJECT_BRIEF.md` is the spec. `PLAN.md` holds the build status, the decisions made since the brief, and the open items.

## Repository layout

| Path | What |
|---|---|
| `app/engine` | Pure Kotlin engine: priors, far-point protocol, Bayesian band, referral rules, frame ranking. No Android dependencies. |
| `app/app` | Android app (Kotlin, Jetpack Compose, minSdk 26): interview, voice, camera (MediaPipe face landmarker), screen tests, try-on, Room storage, PDFs |
| `app/app/src/main/assets/rules.json` | Every threshold and clinical constant (strictly parsed) |
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

## Demo mode

Demo mode is for demonstrations and recordings without a real customer:

1. On the home screen, turn on **staff mode**.
2. Tap **Demo mode: sample customers** and pick one:
   - Student, 21: distance blur, long screen use
   - Shopkeeper, 48: reading difficulty
   - Driver, 56: sudden flashes and floaters (shows the referral path)
3. The flow opens at the confirmation screen with answers, camera measurements and screen-test results already filled in. From there you can go through the summary, frame ranking, try-on, comparison and the PDFs.

Every demo screen and PDF is marked **DEMO**. The sample customers are not real people.

Staff mode also has the camera check, screen calibration and the staff voice check line on the interview screen.

## Status

This is a Phase 1 prototype. The clinical numbers in `rules.json` still need an optometrist's review, and the Hindi needs a native-speaker proofread, before any use in a shop. See `PLAN.md`.
