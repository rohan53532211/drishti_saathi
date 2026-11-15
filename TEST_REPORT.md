# Test report: full pass before submission

Date: 15 November 2026 · Branch: `feature/teleconsult` · Laptop run, no phone connected.

## Summary

| Area | Result | Numbers |
|---|---|---|
| 1. Baseline (existing tests) | Pass | engine 383, app 386, backend 97 = 866, 0 failed |
| 2. Safety matrix | Pass | 14 new table-driven tests (8 engine, 5 app, 1 backend), every red flag × every output |
| 3. Stretch / edge | Pass after 2 fixes | 7 randomized tests, 10 000 cases each, fixed seeds |
| 4. Backend load | Pass | 50 patients, 123 s, 13 517 requests, 0 errors, 0 lost messages, 0 duplicate cases |
| 5. Security | Pass after 1 fix | 9 new backend tests, git history and APK scanned for secrets |
| 6. App on a phone (monkey, offline queue) | **Not run** | no phone was connected over adb |
| All tests after the pass | Pass | engine 397, app 396, backend 106 = 899, 0 failed |

## 1. Baseline

Run before any change: engine 383, app 386, backend 97. All passed.

## 2. Safety matrix

Engine: `SafetyMatrixTest`. App: `FullPassAppTest`. Backend: `test_security.py`.

| Case | Checked | Result |
|---|---|---|
| Each red flag alone: sudden vision loss, pain / redness, new flashes, new floaters, curtain / shadow, double vision (two ways of answering), grid distortion right eye, grid distortion left eye, bent lines reported, and the two "yes, sign not picked" answers | URGENT tag; no lens, design, coating or frame in the plan, shop PDF, customer PDF, share text or optometrist report; power printed only as "For the optometrist only"; "Please see an eye doctor today" once; the flag named | Pass |
| Emergency signs (sudden vision loss, new flashes, curtain / shadow, any unpicked sign) | Short path: power of each eye + grid only | Pass |
| Other red flags (pain / redness, floaters, double vision, grid distortion, bent lines) | Full check | Pass |
| Ages 3, 8, 12, 17 with a minus power (strong, −0.25 in one eye, one eye only) | CHILD_MYOPIA, no lens, myopia management shown as referral information only | Pass |
| Ages 3, 8, 12, 17 without myopia, every budget | SmartLife Young design, single vision, kids coating, "optometrist confirms"; no adult lens type or design | Pass |
| Red flag on a child | URGENT wins; still marked child myopia; no product at all | Pass |
| Adult, no red flag (5 personas) | Lens type, design, UVProtect, coating; every card has a "why" | Pass |
| Exclusions | Never BlueProtect with BlueGuard or PhotoFusion X; never PhotoFusion X because of night glare | Pass |
| Report queue | Urgent reports are queued, kept while offline and after an app restart, sent oldest first on reconnect | Pass (unit test of the queue, not a phone) |
| Teleconsult, app | Urgent case shows "Please see an eye doctor today", then the reasons, then "A teleconsult never replaces an urgent visit", before anything else | Pass |
| Teleconsult, portal and server | Urgent cases first in the queue; the urgent banner is the first block of the case view; a priority typed by the client is refused | Pass |

## 3. Stretch / edge (10 000 cases each, fixed seed)

| Test | Property | Result |
|---|---|---|
| Power from a blur distance, 5 cm to 600 cm | Finite, never plus, on the 0.25 D grid, within half a step of −1/d, range ordered and 0.50 D wide | Pass |
| Faulty distances: 0, negative, NaN, ±infinity, under 1 cm | Refused, never turned into a number | **Failed, fixed** (see below) |
| Lens-assisted total, lens 0 to −10 D | Finite, on the grid, never plus, range ordered, vertex correction only from 4 D and always weaker | Pass |
| Lens too strong / faulty distance through the lens | "Use a weaker lens", never a number; plus or off-grid typed lens refused | Pass |
| Stronger-lens repeat | Asked exactly when the residual is −3.00 D or stronger and no repeat was done; never twice; the next lens is always stronger | Pass |
| Survey: random, missing, nonsense and contradictory answers; hours above 24; age 0 and 120; everything skipped | No crash; rings 0 to 100; every ring label matches its thresholds; skipped = "not answered", not a guess | Pass |
| Lens engine: random customers and random safety gates | Only catalogue ids, no product twice, at most 2 second pairs and 1 sun lens, nothing when the gate blocks, exclusions hold | Pass |
| Lens engine: "why" on every card | Every suggested product has a why line | **Failed, fixed in part** (see below) |
| Hindi / English | Every string in both languages, same placeholders, Hindi in Devanagari; survey questions, options, lens catalogue and red-flag texts too | Pass |

## 4. Backend load

`backend/tests/load/loadtest.py`, its own server on port 8010 with a temporary database (the demo server on 8000 was not touched and answered 200 afterwards). 50 patients registered, logged in, each sent one report (5 urgent) and chatted; 3 optometrists accepted and answered.

| Call | Count | p50 | p95 | p99 | Max |
|---|---|---|---|---|---|
| All | 13 517 | 16 ms | 163 ms | 850 ms | 2 144 ms |
| Register | 50 | 1 458 ms | 1 821 ms | 1 921 ms | 1 921 ms |
| Login | 50 | 937 ms | 1 068 ms | 1 073 ms | 1 073 ms |
| Send report | 50 | 79 ms | 153 ms | 156 ms | 156 ms |
| Chat send | 4 647 | 46 ms | 253 ms | 777 ms | 2 144 ms |
| Chat read | 4 157 | 9 ms | 67 ms | 95 ms | 151 ms |
| Poll case | 4 209 | 16 ms | 119 ms | 155 ms | 212 ms |
| Optometrist queue | 204 | 27 ms | 1 120 ms | 1 583 ms | 1 670 ms |
| Accept | 150 | 8 ms | 72 ms | 114 ms | 114 ms |

- 110 requests a second on average. No 5xx, no connection error, no unexpected answer (error rate 0 %).
- 50 of 50 patients registered, 50 cases, exactly one per patient, none accepted twice. The 100 answers with 404 are the two other optometrists losing the race for a case, which is the intended answer.
- 4 647 chat messages sent, 0 lost, 0 stored twice (checked in the database afterwards).
- Register and login take about 1 to 1.5 s when 50 arrive at once, because bcrypt runs on one server process.

## 5. Security

`backend/tests/test_security.py` (9 tests) and `backend/tests/load/secret_scan.py`.

| Check | Result |
|---|---|
| No token, empty, garbage, wrong scheme, expired, signed with another key, tampered payload, tampered signature, `alg: none` (two spellings), role raised, unknown user: all 21 protected endpoints | 401 every time |
| Patient on optometrist and admin endpoints; optometrist on admin endpoints; nobody can set their own role or status | 403 / 422 |
| PENDING optometrist | Sees no queue, no case, no chat, no catalogue, no PDF; cannot set "available" |
| Another optometrist or another patient on someone's case, chat, chat picture, advice, PDF (read and write) | 404; nothing changed |
| SQL-style and script payloads in names, chat, advice | Stored as plain text, returned as JSON, PDF still made, tables intact |
| Portal page | No `innerHTML` or similar, no inline handlers; text is written with text nodes; only http(s) text becomes a link, with `rel="noopener"`. Checked in the page source, not by running payloads in a browser |
| Passwords | bcrypt, salted, never in any response |
| Login brute force | **Was missing, added:** 10 wrong passwords a minute for one account from one address, then 429 with `Retry-After` |
| Account deletion | Report, chat, picture, advice and PDF are gone for the patient and the optometrist; nothing of the name or number left in the database |
| Git history | 913 file versions in 93 commits on 15 branches: no `.env` file, none of the three `.env` secrets, no key-shaped string |
| APK (`drishti-teleconsult.apk`, 667 files unzipped) | No Sarvam key, `ADMIN_PASSWORD` or `DEMO_PASSWORD`, neither the values nor the names |
| QR and optometrist report in shop mode | No name, photo, phone, e-mail or picture for any session; QR under 2 900 characters |

## 6. App on a phone

Not run: `adb devices` listed no phone. Still to do by hand: install the APK, `adb shell monkey -p <package> --throttle 200 -v 3000`, and the offline test (backend off, finish a check, backend on, report arrives). The queue logic itself is covered by a unit test.

## What was fixed

1. **Login had no limit on wrong passwords (HIGH).** `backend/app/teleconsult.py` now counts wrong passwords per account and address: after 10 in a minute it answers 429 until the minute has passed; a correct login clears the count.
2. **A faulty distance could become a power (HIGH if it ever happened).** `PowerRange.far` turned 0 mm into a huge number, a negative distance into a plus power, and crashed on NaN. The screens never sent such values in testing, but nothing stopped them. Now a distance that is not a real length of at least 1 cm is refused, and the tap is treated as "distance not caught".
3. **A design card could have no "why" line (MEDIUM, fixed because the matrix requires a why).** A low-budget customer whose lens type only offers SmartLife got the design with no reason. It now says "The design made for this lens type" (Hindi added).
4. **Test only:** the seed test read the demo phone numbers from a developer's own `.env`; it now ignores them.

The urgent wording of the teleconsult screens now comes from one function (`TeleText.urgentLines`), so it can be tested.

## Open risks (not fixed)

| Risk | Level |
|---|---|
| No test on a phone in this pass: monkey run, offline queue on the device, and the teleconsult patient screens | High for the demo |
| Plain http on the LAN; login is phone + password with no OTP and no password reset; a token is valid for 7 days and cannot be withdrawn except by deleting the account | Known demo scope (README) |
| `BRIDGE_TOKEN` is not set in the local `.env`, so the older bridge endpoints (report upload and review page) accept anyone on the same network | Medium |
| The login limit lives in memory: it resets when the server restarts, covers one account per address, and does not stop one address from trying many different accounts | Medium |
| The portal's protection against script injection was checked in its source and through the API, not by running payloads in a browser | Medium |
| A product chosen from a symptom alone has no "why" line when the customer skipped the hours or age its sentence quotes (in 7 026 random plans: BlueGuard 1 299 times, anti-fatigue 323 times). Never a wrong line | Low |
| A child without myopia who drives can still get a sun lens as a second pair (no adult lens type or design, as required) | Low |
| An answer stored as the text "NaN" for an hours question would crash the rings. The number pad and voice input cannot produce it | Low |
| Register and login need 1 to 1.5 s each when 50 people do it at the same moment; the optometrist queue reached 1.1 s at p95 | Low |
| One SQLite file and one server process: fine for the demo, not for production | Low |

## Safety guarantees (for the deck)

Each line is enforced by code and checked by an automated test on every build.

- **A red flag always wins.** Any of 10 warning signs marks the report URGENT. No lens, coating or frame is shown or sent, on screen, PDF, QR or report.
- **"See an eye doctor today" comes first.** In the app, on both PDFs, in the teleconsult screens and in the optometrist portal.
- **Emergencies take the short path.** Sudden vision loss, new flashes or a curtain: power of each eye and the grid only, then referral.
- **Children are never sold a lens for myopia.** Under 18 with any minus power: measured, referred for myopia control, no lens choice.
- **The power is a range, never an exact number, and never a guess.** A faulty reading gives no number; a lens that is too strong gives "use a weaker lens".
- **With a red flag or child myopia the power is "For the optometrist only".**
- **The optometrist always gets the report.** It is queued on the phone and sent when the connection returns.
- **A teleconsult never replaces an urgent visit.** Said in the app and the portal for every urgent case.
- **Nothing personal leaves the phone in shop mode.** No name, photo or phone number in the QR or the report; the server refuses them too.
- **Only verified optometrists see cases, and only their own.** Patients see only their own case; deleting the account deletes everything.
- **Lens rules cannot contradict each other.** Never BlueProtect with BlueGuard, never PhotoFusion X for night glare, at most two second pairs.
- **Checked at scale.** 70 000 randomized cases and 13 517 load requests without a wrong result, a crash or a lost message.
