# Drishti Saathi: design spec

Branch `feature/ui-polish`. This file is the spec. The app and the optometrist portal implement exactly what it says. The redesign changes only the UI layer: screens, components, navigation, wording and look. It leaves the engine, the safety rules, the lens engine, the measurements and the backend logic alone.

**Who it is for**
- **Patients:** Hindi first, often low literacy, in small towns, on a mid-range Android phone, often in poor light.
- **Optical-shop staff:** they run the check for a customer at the counter.
- **Optometrists:** they review reports and advise remotely, in the web portal.

**Bar:** calm, clean, trustworthy, health-app quality. One clear thing to do on every screen.

---

## 1. Design principles

1. **One primary action per screen.** It is the only filled button, at the bottom, at thumb height. Everything else is secondary (outlined) or a text button.
2. **At most 2 lines of helper text** under a title. Longer explanations go behind "Why we ask?" in a bottom sheet, or in an expandable "What we share".
3. **Plain words, Hindi first.** Each string has a natural Hindi version. Where both languages show together (Welcome, role cards), Hindi comes first, then English. Say "eye test", not "refraction"; "phone estimate", not "power". No banned wording (`StringsWordingTest`).
4. **Show, don't explain.** An illustration, one instruction line, then the action. Numbers are big and tabular, and their method is in a caption.
5. **Safety messages are unmissable.** An urgent signal shows a full-width red Banner with an icon. It is read aloud and gives a "What to do now" list. Nothing competes with it: no lens cards, no promotional tiles, no second primary action. Colour is never the only signal: there is always an icon and text.
6. **No more than 3 choices visible at once** in the guided flow. Longer lists scroll in groups of 3, or move to a second step.
7. **Never a dead end.** Every empty state has one CTA, and every error has a retry and plain words.
8. **Respect the person.** Text scales to 1.5× without clipping. Touch targets are at least 48 dp. Motion is reduced when the phone asks for it.

---

## 2. Screen inventory

Role: P = patient, S = shop staff, O = optometrist (web), A = admin (web), All = everyone.

### App: first run and account (new in Phase 2)

| Screen | Purpose | Primary action | Role |
|---|---|---|---|
| Splash (SplashScreen API) | Brand mark while the app starts | none | All |
| Welcome | Name, tagline, language toggle | "शुरू करें / Get started" | All |
| Role choice | Pick patient, optical shop or optometrist | Tap a role card | All |
| Patient: Login or create | Choose between the two | "खाता बनाएँ / Create account" | P |
| Sign-up step 1 | Name + mobile | Next | P |
| Sign-up step 2 | Age + gender (optional) | Next | P |
| Sign-up step 3 | Password + consent ("What we share" expandable) | "खाता बनाएँ / Create account" | P |
| Profile ready | Success check, first-test CTA | "पहली जाँच शुरू करें / Start first test" | P |
| Login | Mobile + password, show/hide, forgot-password hint | "लॉग इन / Log in" | P |

### App: role homes (Phase 3)

| Screen | Purpose | Primary action | Role |
|---|---|---|---|
| Patient Home (tab) | Greeting, start test, last result, my optometrist, tips | "आँखों की जाँच शुरू करें (10 मिनट)" | P |
| Consultations (tab) | My consultations with status and last message | Open a consultation | P |
| Profile (tab) | Avatar, details, language, switch role, delete account, about | none (settings list) | P / S |
| Shop Home (tab) | Start new customer, connection pill, tiles | "नया ग्राहक शुरू करें / Start new customer" | S |
| Customers (tab) | Past sessions on this phone | Open a session | S |
| Tools (tab) | Frames, phone setup, demo, Advanced | Open a tool | S |
| Advanced | Server URL, token, calibration, diagnostics, staff mode | Save | S |
| Bridge settings | Server URL + token, test connection | Save | S |
| Phone setup | One-time card calibration | Finish | S |
| Diagnostics | Camera check, precision mode, 3D alignment | Open one | S |
| Camera check | Live camera gates | none | S |
| Demo | Sample customers (always marked DEMO) | Pick a customer | S |

### App: guided test (Phase 4)

| Screen | Purpose | Primary action | Role |
|---|---|---|---|
| Before the test (checklist) | Good light, phone charged, bank card / glasses | "मैं तैयार हूँ / I'm ready" | P / S |
| Consent | What happens, spoken and tapped | "मैं सहमत हूँ / I agree" | P / S |
| Language | हिंदी or English for the questions | Pick one | P / S |
| AI voice consent | Voice interview yes/no | Continue | P / S |
| AI voice interview | Spoken questions, live transcript | Answer (mic) | P / S |
| Interview (scripted) | One question per screen | Pick an answer | P / S |
| Old numbers | Enter a known power | Save | P / S |
| Section intro | Work / Digital / Mobility / Leisure, in the section colour | Continue | P / S |
| Survey question | One question, big ChoiceCards or slider | Pick an answer | P / S |
| "Why we ask?" sheet | Reason for the question | Close | P / S |
| Red-flag banner | "Please see an eye doctor today", read aloud | "What to do now" list, then Continue | P / S |
| Forehead card | Optional card on the forehead | Done / Skip | P / S |
| Face capture | Distance + PD | Capture (automatic) | P / S |
| Capture outcome | OK / try again | Continue | P / S |
| Glasses off / compare offer | Remove glasses, optional compare | Continue | P / S |
| Eye check (far letters, reading, near) | Immersive, per eye, live distance pill | "Tap when letters blur" | P / S |
| Screening: Amsler grid | Illustration → instruction → test → success | Done | P / S |
| Screening: contrast | Same template | Answer | P / S |
| Screening: colour | Same template | Answer | P / S |
| Edit answers (staff) | Change answers, staff measurements | See results | S |
| Referral | Referral details | Continue | P / S |

### App: results and consult (Phase 5)

| Screen | Purpose | Primary action | Role |
|---|---|---|---|
| Result | Rings, measurements, lens, actions, QR | "ऑप्टोमेट्रिस्ट से सलाह लें / Consult optometrist" | P / S |
| Compare | Two lens options side by side | none | S |
| Try-on 2D / 3D catalogue / viewer / live 3D / avatar | Frames | Try on | P / S |
| Choose optometrist | "Any available" first, then list rows | "अनुरोध भेजें / Send request" | P |
| Consultation (chat) | Bubbles, day separators, pinned video card | Send | P |
| Advice | Prescription-style card | "PDF डाउनलोड करें / Download PDF" | P |
| Past sessions | Saved sessions with reply status | Open | S |
| Bridge consent | Consent for the anonymous report | Send | S |

### Web portal (Phase 6)

| Screen | Purpose | Primary action | Role |
|---|---|---|---|
| Login | Mobile/email + password | Log in | O / A |
| Register | Optometrist registration | Create account | O |
| Pending / rejected | Waiting for approval | Check again | O |
| Queue | Waiting cases, urgent pinned | Open a case | O |
| My active | Accepted / in progress | Open a case | O |
| Completed | Completed / closed | Open a case | O |
| Case | Report (left) + actions panel (right) | Accept, then "Write advice" | O |
| Advice form | SPH/CYL/AXIS steppers, lens picker, preview | "Preview" → "Send to patient" | O |
| Profile | Edit details, availability | Save | O |
| Admin: registrations | Approve or reject | Approve | A |

---

## 3. Information architecture and navigation

```
Splash ─► (first run) Welcome ─► Role choice ─┬─► Patient ─► Login | Create account (3 steps) ─► Profile ready ─► Patient shell
          (later runs: straight to the saved   ├─► Optical shop ───────────────────────────────────────────────► Shop shell
           role's shell)                       └─► Optometrist ─► browser: <server>/portal
```

The role and the patient's login are remembered on the phone. **Profile → Switch role** goes back to Role choice; logging out keeps the role.

### Patient (bottom nav: Home · Consultations · Profile)

```
Home ─► Before the test ─► Consent ─► Language ─► Interview (AI or scripted) ─► Survey ─► Face ─► Eye check ─► Screening ─► Result
  │                                                                       (red flag at any point ─► Red-flag banner ─► the flow continues)
  ├─► Last result card ─► Result
  └─► My optometrist card ─► Consultation
Result ─► Consult optometrist ─► Choose optometrist ─► Consultation (chat, video, advice, PDF)
Consultations ─► Consultation
Profile ─► Language · Switch role · Server (Advanced) · Log out · Delete account (confirm sheet) · About / disclaimer
```

### Shop staff (bottom nav: Home · Customers · Tools)

```
Home ─► Start new customer ─► Before the test ─► (the same guided test) ─► Result ─► Edit answers · Compare · Try-on · PDF · Share
  ├─► tiles: Past sessions (= Customers) · Frames · Tools
  └─► connection pill (tap: send the queued reports now)
Customers ─► Result of that session
Tools ─► Frames · Phone setup · Demo customers · Advanced (server URL, token, calibration, diagnostics, precision / staff mode) · Profile
```

### Optometrist

The **"I'm an optometrist"** card opens `https://drishti-saathi-backend.onrender.com/portal` in the browser. The portal's left sidebar holds Queue · My active · Completed · Profile. The top bar holds the availability switch and the avatar.

---

## 4. Design system (`ui/designsystem`)

### Type
Two families are merged into one file per weight, so that Hindi and English mix on one line without falling back to the phone's font:
- **Plus Jakarta Sans** for Latin (SIL OFL 1.1)
- **Noto Sans Devanagari** for Hindi (SIL OFL 1.1)

Weights: Regular 400, Medium 500, SemiBold 600, Bold 700. Built by `tools/build_fonts.py`; files `res/font/jakarta_deva_*.ttf`. Tabular figures (`tnum`) are used for numbers. Line heights leave room for matras.

| Role | Size / line | Weight | Material slot |
|---|---|---|---|
| Display | 32 / 40 | Bold | displaySmall |
| Headline | 24 / 32 | SemiBold | headlineSmall |
| Title | 20 / 28 | SemiBold | titleLarge |
| Body | 17 / 26 | Regular | bodyLarge |
| Label | 14 / 20 | Medium | labelLarge |
| Caption | 13 / 18 | Regular | bodySmall |

### Colour (light default; full dark theme)

| Token | Light | Dark |
|---|---|---|
| background | `#F6F8FB` | `#0B121C` |
| surface | `#FFFFFF` | `#131D2A` |
| surface-alt | `#F0F3F8` | `#1A2636` |
| ink | `#0E1A2B` | `#EEF3F9` |
| ink-muted | `#5B6B7F` | `#9FB0C2` |
| hairline | `#E4E9F0` | `#243243` |
| primary / on-primary / primary-container | `#1F5EFF` / `#FFFFFF` / `#E8EEFF` | `#7FA2FF` / `#05122B` / `#16284A` |
| primary-ink (text on primary-container) | `#1D4ED8` | `#9DB8FF` |
| success (+ ink, container) | `#16A34A` (`#166534`, `#E7F6EC`) | `#4ADE80` (`#6EE7A0`, `#0F2E1C`) |
| warning (+ ink, container) | `#D97706` (`#92400E`, `#FEF3E2`) | `#FBBF24` (`#FCD34D`, `#33270C`) |
| urgent (+ ink, container) | `#DC2626` (`#B91C1C`, `#FDECEC`) | `#F87171` (`#FCA5A5`, `#3A1616`) |
| Work / Digital / Mobility / Leisure | `#2F80ED` / `#84CC16` / `#64748B` / `#F59E0B` | `#5B9BF3` / `#A3E635` / `#94A3B8` / `#FBBF24` |

The `*-ink` tones exist because the brand success, warning and urgent colours are not dark enough for text (and brand blue on its own container measures 4.4:1, so selected chips and "why" pills use primary-ink). Text always uses the ink tone on its container, or on the surface. Text on a section colour uses `DS.on(bg)` (dark ink or white, whichever has the higher contrast). **A unit test checks WCAG AA (4.5:1) for every text/background pair** in both themes (`DesignTokensTest`).

### Space, shape, elevation
- **Spacing:** 4 / 8 / 12 / 16 / 24 / 32. Screen padding 24 dp.
- **Radius:** cards 20 dp, buttons 16 dp, chips and pills full.
- **Elevation:** flat cards with a 1 dp hairline. One soft shadow level, used only for HeroCard and bottom sheets.

### Icons and illustrations
- **Icons:** androidx Material Icons Extended, **Rounded set only**, 24 dp. On cards they sit in a 40 dp circle tinted with the accent at 14 %. No emoji, no mixed styles.
- **Custom vector icons** in the same 2 dp rounded-stroke style: eye mark (logo), lens, Amsler grid, colour plate.
- **Spot illustrations:** original flat vectors in the brand palette.

  | Illustration | Used on |
  |---|---|
  | eye | onboarding, empty states |
  | phone at arm's length | checklist, eye test |
  | bank card on screen | checklist, calibration |
  | glasses | checklist, results |
  | light / sun | checklist |
  | battery | checklist |
  | chat | consultations empty state |

  No stock images, no copied art.

### Components (Compose, each with a `@Preview`)

| Component | Notes |
|---|---|
| PrimaryButton | 56 dp, filled, full width, press scale 0.97 / 120 ms, busy state |
| SecondaryButton | 56 dp, outlined |
| DsTextButton | |
| DsIconButton | 48 dp target |
| DsCard | 20 dp radius, hairline |
| HeroCard | primary or tinted, one shadow level |
| ListRow | leading icon circle, title, subtitle, trailing chevron or pill |
| ChoiceCard | big tappable answer, selected = primary border + check circle |
| SegmentedControl | 2–3 options |
| FrequencySlider | Never / Sometimes / Often, 3 stops |
| ProgressDots | step k of n; per-eye R ●○ L |
| DsTopBar | title, back, actions |
| DsBottomNav | 3 items, icon + label |
| StatusPill | Connected, Offline, Urgent, Waiting, In progress, Completed. Always icon + text |
| Banner | info / warning / urgent, with icon |
| EmptyState | illustration + title + one line + CTA |
| ErrorState | icon + plain words + Retry |
| Skeleton | shimmer block, no shimmer when reduce motion is on |
| DsSnackbar | |
| DsBottomSheet | |
| PinField | OTP-style boxes, for later |
| Avatar | initials on primary-container |
| ChatBubble | me right / primary, them left / surface, time |
| DsTextField | label above, 56 dp, helper or error below, show/hide for passwords |

### Motion and haptics
- **Screens:** 200 ms fade + 24 dp slide between screens.
- **Press:** scale 0.97 over 120 ms.
- **Success:** check draws in over 400 ms. The profile rings draw in over 900 ms.
- **Reduce motion:** when the phone's animator scale is 0 ("Remove animations"), every animation snaps to its end.
- **Haptics:** light tick on selection, a confirm pattern on success, a reject (warning) pattern when an urgent banner shows.

---

## 5. Screen specs

### Phase 2: first run and auth
- **Splash:** Android 12 SplashScreen API (`core-splashscreen`). The eye mark on primary blue. No extra splash screen of our own.
- **Welcome:**
  - Layout: language toggle `हिंदी | English` (SegmentedControl) at the top; centred eye mark; "दृष्टि साथी / Drishti Saathi"; tagline "आँखों की देखभाल, आसान / Eye care, made simple"; eye illustration.
  - PrimaryButton "शुरू करें / Get started".
- **Role choice:** three large cards, each with an icon circle, a title and one line.
  - "मैं मरीज़ हूँ / I'm a patient" → Login or create.
  - "ऑप्टिकल दुकान / Optical shop" → Shop shell.
  - "मैं ऑप्टोमेट्रिस्ट हूँ / I'm an optometrist" → opens the portal in the browser (and stays on this screen).
- **Sign-up wizard:**
  - Layout: ProgressDots (1–3) and a back arrow in the top bar; one step per screen.
  - (1) Name + mobile. (2) Age, then gender as three ChoiceCards (optional, can be skipped). (3) Password (show/hide) + consent.
  - Consent: a plain-language sentence with a checkbox, and an expandable "हम क्या भेजते हैं / What we share" list: report numbers and answers, your name, only to the optometrist you choose; no photos.
  - Each step validates with the existing `TeleText` rules and shows the error under the field.
  - Then the **Profile ready** screen: an animated success check, "प्रोफ़ाइल तैयार है / Your profile is ready", and the PrimaryButton "पहली जाँच शुरू करें / Start your first test" (secondary: "Later").
- **Login:**
  - Fields: mobile, password with show/hide.
  - Error text in plain words under the button: "मोबाइल नंबर या पासवर्ड ग़लत है".
  - "पासवर्ड भूल गए? अपनी दुकान या ऑप्टोमेट्रिस्ट से पूछें / Forgot password? Ask your shop or optometrist". There is no OTP yet.
- **Remember:** the role and the login. Profile → Settings → Switch role.

### Phase 3: homes
- **Patient Home:**
  - "नमस्ते, {name}" (Display).
  - HeroCard (primary): "आँखों की जाँच शुरू करें" with the line "लगभग 10 मिनट".
  - "Last result" card: mini rings and the date, or an EmptyState "No test yet".
  - "My optometrist" card: the latest consultation's name and StatusPill, or "Consult after your test".
  - Tips row: 3 small cards that scroll sideways (20-20-20 rule, good light, yearly check).
- **Shop Home:**
  - The shop's greeting.
  - HeroCard "नया ग्राहक शुरू करें".
  - Connection StatusPill (Connected / Offline · N queued; tap = send now).
  - Tiles: Past sessions, Frames, Tools.
  - When phone setup is not done, a warning Banner "Set up this phone (2 min)".
- **Tools:** ListRows for Frames, Phone setup, Demo customers, and Advanced.
  - Advanced holds the server URL, token, calibration, diagnostics, precision mode and the 3D alignment check, hidden from normal use.
  - The default server URL is `https://drishti-saathi-backend.onrender.com` (already the app default).
- **Profile:**
  - Avatar with initials, name, mobile, age.
  - Language SegmentedControl, Switch role, Server (Advanced), Log out.
  - Delete account: a destructive row that opens a confirm bottom sheet.
  - About / disclaimer: version and the bilingual disclaimer.

### Phase 4: guided test (logic unchanged)
- **Before the test:**
  - One screen with 3 illustrated ListRows: "अच्छी रोशनी / Good light", "फ़ोन चार्ज हो / Phone charged", "बैंक कार्ड पास रखें, चश्मा पहनते हैं तो साथ रखें / Keep a bank card, and your glasses if you wear them".
  - PrimaryButton "मैं तैयार हूँ / I'm ready".
- **Survey:**
  - Layout: one question per screen; big ChoiceCards with a check when selected; ProgressDots per section.
  - Frequency questions use the three-stop FrequencySlider.
  - The voice mic is a FAB with a pulse while listening and a live transcript above it.
  - "Why we ask?" opens a bottom sheet.
  - Section intro screens use the section colour: icon, section name and one line.
- **Eye tests:**
  - Immersive, full screen: one instruction line plus an illustration.
  - A live distance StatusPill shows "45 cm ✓" or "Move closer".
  - Per-eye progress: R ●○ L.
  - The main button "Tap when the letters blur" sits at thumb height.
  - A 3-2-1 countdown shows only before a test starts. It is visual; the procedure starts only after it, as before.
- **Screening (Amsler, contrast, colour):** one template for all three: illustration → instruction → test → a small success state with a check.
- **Urgent / red flag:**
  - Full-screen urgent colour, with a 64 dp warning icon in a white circle.
  - "Please see an eye doctor today" (Display), read aloud.
  - A "What to do now" list: (1) see an eye doctor today, (2) take this report, (3) don't drive if your vision is affected.
  - Then the reasons, then one button. No other content.

### Phase 5: results, consult, chat
- **Result:**
  - Header: date and language.
  - Then, in order: urgent Banner (if any) → Vision Profile rings → Measurements card → Main lens card → Second-pair cards → actions row → QR (expandable "QR for the shop").
  - Measurements card: big tabular numbers, R | L, the range, and the method in a caption.
  - Main lens card: illustration, name, 2 "why" bullets, and the caption "Optician confirms".
  - Actions row: Consult optometrist [primary], Share, PDF.
- **Choose optometrist:**
  - "Any available" first, highlighted.
  - Then ListRows: Avatar, name, qualification · city, language chips, fee.
- **Consultations:** list rows with a StatusPill (Waiting / In progress / Completed), a last-message preview (when the server sends one) and the time. EmptyState with the chat illustration and the CTA "Start eye test".
- **Chat:**
  - Bubbles: mine right and primary, theirs left and surface; timestamps; day separators ("आज / Today", dates).
  - A sticky input with a send icon button.
  - Top bar actions: Video, WhatsApp (when allowed).
  - When the call starts, a pinned "Join video call" card.
- **Advice:**
  - A prescription-style card: R/L table SPH / CYL / AXIS / ADD, PD, lens advice, referral, follow-up date, the optometrist's name and reg. no.
  - Then "Download PDF".
- **States:**
  - Empty states: illustration + one CTA.
  - Loading: skeleton rows.
  - Errors: ErrorState with Retry.

### Phase 6: optometrist portal (same design language)
- **Tokens:** the same as the app (CSS variables), the Plus Jakarta + Devanagari font, the same radius. Icons are Material Symbols Rounded (web font, Apache-2.0, bundled).
- **Layout:**
  - Left sidebar (Queue, My active, Completed, Profile); it collapses to a bottom bar under 860 px.
  - Top bar: availability switch, avatar with initials, theme, log out.
- **Queue:** a table on desktop and cards on mobile. Columns: priority pill (icon + text), patient, age, language, wait time. Urgent is pinned at the top.
- **Case page:**
  - Left column, the report: rings, measurements, screening, red flags, lens suggestions.
  - Right column, the sticky actions panel: Accept, Chat, Video, WhatsApp, Write advice.
- **Advice form:**
  - SPH / CYL / AXIS steppers (−/+ in 0.25 D steps; axis in 1° steps) per eye.
  - Lens picker chips from the catalogue.
  - **Preview** shows the advice exactly as the patient sees it, then "Send to patient".
- **Auth:** login, register and pending screens match the app. Responsive down to 360 px.

### Phase 7: brand and quality
- **App icon:** adaptive icon (original eye mark on primary blue) and a monochrome icon for Android 13.
- **Accessibility:**
  - A TalkBack label on every control, and headings marked.
  - Focus order is reading order.
  - Targets ≥ 48 dp.
  - Font scale 1.3 and 1.5 without clipping: no fixed heights on text containers.
  - Status is always icon + text.
- **Hindi review:** every new string has natural Hindi (no word-for-word phrasing) and no truncation (no `maxLines = 1` on Hindi titles except the top bar, which ellipsises).
- **Visual QA:** Roborazzi screenshot tests (Robolectric, JVM) for the key screens in light/dark × Hindi/English × font scale 1.0/1.3. Saved to `docs/screens/` and listed below.
- **Performance:**
  - Lazy lists with stable keys.
  - No work in composition.
  - App start under 2 s: SplashScreen plus no blocking I/O on the main thread before the first frame.

---

## 6. Screenshots

Filled in by Phase 7 (`docs/screens/`).
