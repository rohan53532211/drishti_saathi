# Drishti Saathi · दृष्टि साथी

**Voice-first eye pre-consultation for small optical shops in India**  
ZEISS India Hackathon BeyondCode’26 · Theme 3 — No-Device Vision Intelligence & Accessible Clinical UX  
**Team Kanpur_duos** · Rohan Kumar and Divyansh Gupta · IIT Kanpur

A customer walks into an optical shop and says, “The board is blurry, but I can read my phone.” Drishti Saathi helps staff turn that answer into a useful starting point for an eye exam. In about ten minutes, an Android phone guides the customer through a Hindi or English interview, checks each eye and prepares a report. On a routine visit, the shop can discuss a starting trial lens, lens options and frames. If the customer reports a warning sign, the app changes course immediately and shows the reason for referral.

### Download and install (Android 8+)

[Download the v1.3 APK](https://github.com/rohan53532211/drishti_saathi/releases/download/v1.3/Drishti_saathi-final_v1.3.apk) · [Open the optometrist portal](https://drishti-saathi-backend.onrender.com/portal) · [Get the v1.2 submission build](https://github.com/rohan53532211/drishti_saathi/releases/tag/v1.2)

**The shop phone is already configured for the hosted demo.** With internet access, the v1.3 APK sends shop reports to the [deployed optometrist bridge](https://drishti-saathi-backend.onrender.com/). Judges can install the app and start with its default settings.

1. Download the APK on an Android 8.0+ ARM phone. Open it, allow **Install unknown apps** for the browser or file manager if prompted, then tap **Install**.
2. Open **Drishti Saathi**, choose a role and follow the sign-up or login prompts. Allow camera access for measurements and microphone access for spoken answers.
3. **For a quick judging demo**, choose **Optical shop → Tools → Demo mode: sample customers → Student**. Open the results, read **Why for you**, then try the frame comparison. Repeat with **Driver** to see how the same app handles an urgent referral. The sample results open immediately and carry a **DEMO** label.

### What changed after v1.1?

| Release | What to look for |
|---|---|
| [v1.1 vision-profile build](https://github.com/rohan53532211/drishti_saathi/releases/tag/v1.1) | The shop workflow added vision-profile rings, the ZEISS lens-family rules, new screen tests and a lens-assisted check for stronger minus powers. |
| [v1.2 teleconsult demo](https://github.com/rohan53532211/drishti_saathi/releases/tag/v1.2) | Patients gained accounts and consultation history. They can send a report, chat, join a video call and download an optometrist’s advice. The browser portal lets optometrists accept cases and admins approve optometrists. This build also includes fixes from the November 15 phone test. |
| [v1.3 redesign](https://github.com/rohan53532211/drishti_saathi/releases/tag/v1.3) | A new eye-mark icon and welcome screen lead to separate patient and shop homes. The guided check adds a readiness checklist, countdown and right/left progress. Results bring **Consult**, **Share** and **PDF** together. The portal adds a two-column case view and advice preview. The measurement, referral and lens logic carries forward from v1.2. |

## Six-step guided demo

These screenshots come from demonstration sessions. Follow the sequence to see what each role does. The case numbers can differ between examples.

### 1. Choose who is using the phone

**I’m a patient** opens a personal home with **Start eye test**, **Consultations** and **Profile**. **Optical shop** opens a workspace for customers, past sessions and shop tools. **I’m an optometrist** directs the reviewer to the browser portal. Before starting, the app asks the user to sit facing good light and have their glasses and an ATM-sized card nearby for setup.

| Choose a role | Start a patient check | Prepare the phone |
|:---:|:---:|:---:|
| <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.36.jpeg" width="215" alt="Patient, optical shop and optometrist role choices"> | <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.46.jpeg" width="215" alt="Patient home with Start eye test and consultation status"> | <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.38.jpeg" width="215" alt="Before you start checklist"> |

### 2. Explain the problem in Hindi or English

After consent, the customer answers one question at a time. The app can read the question aloud, accept a spoken answer or offer large tap targets. Questions cover symptoms, existing glasses and daily activities. **Why we ask?** explains the purpose of a follow-up. With separate consent, the optional Sarvam AI interview runs through the backend. Scripted questions are available as a fallback.

| Answer by voice or tap | Identify a warning sign |
|:---:|:---:|
| <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.37.jpeg" width="215" alt="Interview asks about the customer’s glasses power"> | <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.47.jpeg" width="215" alt="Follow-up asks which warning signs the customer noticed"> |

### 3. Check each eye and record how the estimate was obtained

The front camera tracks viewing distance. The customer covers one eye, reads the letters, moves the phone away and taps **Can’t read clearly** when they blur. The app uses that distance to estimate a range of minus power in dioptres (D). Staff can repeat the check through a known lens for stronger minus powers. The results show the method and range for each eye so the optometrist knows what to confirm.

| During the reading task | What appears in the report |
|:---:|:---:|
| <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.46(1).jpeg" width="215" alt="Right-eye reading task with a live distance of 36 cm"> | <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.50(1).jpeg" width="215" alt="Per-eye estimates, ranges, method and low-confidence PD"> |

The **36 cm** on the left is a live distance while the task is running. In the example report on the right, the recorded right-eye endpoint is **46 cm**, with an estimate centred on **−2.25 D**. The report also labels the **63.3 mm** pupillary distance (PD) as a **low-confidence population average**. A reviewer can distinguish a fallback value from a camera measurement.

### 4. Follow the routine or referral path

| What the app records | What happens next |
|---|---|
| Routine adult check | The full sequence can include an Amsler grid for distorted lines, contrast letters, colour plates, a tumbling E for orientation and a clock dial for differences in line clarity. Staff can skip individual checks. |
| Warning sign | The app shows **Please see an eye doctor today**, names the reason and offers to read the message aloud. It withholds lens and frame suggestions and labels power estimates **For the optometrist only**. For emergency signs, the shortened check covers each eye’s power and the grid. |
| Under 18 with a minus-power estimate | The report flags the case for a childhood myopia assessment and withholds lens selection. An urgent warning takes priority. |

| What the customer is told | What the result offers next |
|:---:|:---:|
| <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.48(1).jpeg" width="215" alt="Urgent referral with read-aloud option and short-check instructions"> | <img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.58.50.jpeg" width="215" alt="Consult, Share and PDF actions beneath the same-day referral"> |

### 5. Read the routine result with the customer

Open the **Student** sample for distance blur and prolonged screen use, or **Shopkeeper** for reading difficulty. The four rings summarise **work, digital use, mobility and leisure**. Open the lens-family card and read **Why for you** to trace the suggestion back to the customer’s answers. Staff can then review the trial-lens starting point, compare frames ranked for fit and budget, and show the customer a live 2D or 3D try-on.

The summary offers a shop PDF, a Hindi customer PDF, a shareable summary and a QR code behind **Show QR code**. The consented shop report goes to the remote optometrist bridge. If the phone is offline, it queues the report and sends it after reconnection.

### 6. Follow a consultation from the phone to the browser

1. **Patient:** From results, tap **Consult an optometrist**, select a clinician by language or choose **Any available**, and send the request.
2. **Optometrist:** Sign in to the [hosted consultation portal](https://drishti-saathi-backend.onrender.com/portal) with an approved account. Open **Urgent** and read the reason on the case card. In the example below, one case shows sudden loss of vision and double vision. Another specifies a left-eye grid finding. The reviewer can prioritise them before opening the reports.
3. **Optometrist:** Open a case, review the report and choose **Accept this case**. Chat in the portal, open WhatsApp, or start a Jitsi call. A pinned video-call card lets the patient join from the app.
4. **Optometrist:** Choose **Write advice / e-prescription**, enter findings for each eye and any referral or follow-up, preview the patient’s copy, then send it.
5. **Patient:** Reopen **Consultations** to read the optometrist’s advice and download the PDF. The same-day referral stays visible for urgent cases.

<img src="screenshots/WhatsApp%20Image%202026-11-16%20at%2006.37.33.jpeg" width="850" alt="Browser portal showing two urgent demo cases and their referral reasons">

*Optometrist queue. Urgent, Child myopia, Routine, My active and Completed let the reviewer find a case by priority or status.*

## Accessibility, privacy and clinical scope

- **Accessibility.** Hindi and English screens support read-aloud prompts, voice or tap input, light and dark themes, and status icons paired with words. The v1.3 accessibility tests check TalkBack labels and 48 dp touch targets on key screens at three font scales.
- **Shop report.** The bridge accepts consented answers and measurements under a case ID and rejects direct identifiers such as a name, photo or phone number. Camera frames are processed in memory. Saved sessions use SQLCipher with an Android Keystore-protected key.
- **Patient consultation.** The patient agrees to share their name and report. The accepting optometrist can see the contact number. AI transcription has its own consent step and the Sarvam key stays on the server. WhatsApp and Jitsi are external services. Payments are placeholders in this demo.
- **Clinical scope.** The phone provides screening observations and a starting trial lens. A qualified professional confirms refraction and any prescription. The custom colour plates, contrast steps and phone estimates still require clinical validation. The project team authored the rules for suggesting ZEISS lens families.

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.

## How the project is built

| Component | Implementation |
|---|---|
| [Android app](app/app/) | Kotlin and Jetpack Compose for Hindi and English screens. CameraX, MediaPipe and OpenCV support camera measurements. Filament renders 3D frames. Room stores sessions with SQLCipher encryption and an Android Keystore-protected key. |
| [Decision engine](app/engine/) | Pure Kotlin implements power estimation, referral checks and frame ranking. The [app assets](app/app/src/main/assets/) contain the interview questions, thresholds and lens catalogue. |
| [Backend and browser portal](backend/) | FastAPI and SQLite serve the shop bridge, patient consultations and admin approval workflow. The backend holds the optional Sarvam AI key. WhatsApp and Jitsi open as external services. |

**What gets shared.** The shop bridge receives consented interview answers and measurements under a case ID and rejects direct identifiers such as names, photos and phone numbers. In patient teleconsultation, patients consent to share their report and name, and the accepting optometrist can see their contact number. Camera frames are processed in memory. The optional AI interview has separate consent because speech is sent through the backend for transcription.

## Optional developer setup

### Android

Use JDK 17 or 21, Android SDK Platform 36 and the SDK platform tools. Set `ANDROID_HOME` to your SDK directory or set `sdk.dir` in `app/local.properties`. From the repository root on Linux or macOS:

```sh
cd app
bash gradlew :engine:test :app:testDebugUnitTest
bash gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat` in place of `bash gradlew`. The optional frame photographs are distributed separately. A source build can run without them, but photo-based try-on needs the cut-outs generated by [the frame preparation script](tools/frames_cutout.py).

### Use a different backend

To test against another deployment, enter its base URL under **Tools → Optometrist bridge settings**. To switch back to the project deployment, enter `https://drishti-saathi-backend.onrender.com/`.

<details>
<summary>Run your own backend and portal locally</summary>

Use Python 3.11. From the repository root on Linux or macOS:

```sh
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
```

Set your own `ADMIN_EMAIL`, `ADMIN_PASSWORD` and `DEMO_PASSWORD` in `.env`. Supply `SARVAM_API_KEY` only to enable the AI interview. If you configure `BRIDGE_TOKEN`, enter the same token in the app's bridge settings. Then start your local server.

```sh
python -m app.seed_demo
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

| To try | Open |
|---|---|
| Optometrist queue | [Local portal](http://localhost:8000/portal). Sign in with seeded optometrist `9000000101` and your `DEMO_PASSWORD`. |
| Approve an optometrist | [Local admin page](http://localhost:8000/portal/admin). Use your configured admin credentials. |
| Test this local server from a shop phone | Put it on the same Wi-Fi as the server. Under **Tools → Optometrist bridge settings**, enter `http://<laptop-LAN-IP>:8000`. |
| Run backend tests | With the virtual environment active, run `python -m pytest` from `backend/`. |

</details>

Use sample data for demos. The [Render configuration](render.yaml) uses temporary storage, so hosted demo records can disappear after a restart.

## Testing and project notes

The [v1.2 pre-submission test report](TEST_REPORT.md) records **899 passing tests** across the engine, app and backend, including checks that an urgent case suppresses product suggestions in reports and exports. The [v1.3 build log](PLAN.md) records **955 passing tests** after the redesign (397 engine, 440 app and 118 backend). These counts describe automated software checks. The v1.3 release notes leave physical-device startup and scrolling performance unmeasured.

[Architecture](docs/ARCHITECTURE.md) · [Design and accessibility](docs/DESIGN.md) · [App and portal screenshots](docs/screens/) · [Backend reference](backend/README.md) · [Project brief](PROJECT_BRIEF.md) · [Build history and open work](PLAN.md)
