# Remote Optometrist Bridge

A small FastAPI + SQLite service. A shop using the Drishti Saathi app can send a customer's pre-consultation profile to a remote optometrist, who replies with advice and an "needs in-person exam" flag. The app shows the reply on the summary.

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.
> The optometrist sees phone estimates that still have to be confirmed with the shop's trial lens.

## How the optometrist bridge works

```
 shop phone (app)                      laptop / server (this backend)                optometrist (browser)
 ───────────────                       ─────────────────────────────                 ─────────────────────
 Summary → Send to optometrist ──POST /profiles──▶  stored, case #3F9A0C  ◀──every 10 s── opens  <server>/
 card: "Case #3F9A0C, Waiting"                                                        sees NEW case, writes advice,
 checks every 15 s ──GET /profiles/{id}/review──▶  reply stored  ◀──POST /profiles/{id}/review── Submit advice
 "Reply received" + notification + sound ◀────────  status REVIEWED                    "Reply sent to the shop"
```

- **Who opens what.** The shop's phone uses the server address set in staff mode → **Optometrist bridge settings**. The optometrist opens the same address in a browser, with a `/` at the end: for example `https://abc.trycloudflare.com/` or `http://192.168.1.5:8000/`. Both use the same token if one is set.
- **The case number** is the first 6 characters of the server's profile id, in capitals, for example `#3F9A0C`. The phone shows it in large text after sending, and the optometrist's list shows the same number. Use it when you talk about a case on the phone.
- **Optometrist page.**
  - The list refreshes every 10 seconds.
  - A case not yet opened in this browser has a **NEW** badge, and a short sound plays when one arrives. Click **Sound: on** once, because browsers only play sound after a click.
  - Opening a case shows at the top: the case number, age, problem, the functional vision profile, the power source and values, and any referral or red flags. The full data is underneath.
  - After **Submit advice**, the page shows "Reply sent to the shop".
- **How the reply reaches the app.** Nothing is pushed to the phone; the phone asks the server.
  - It asks every 15 seconds while that session's summary is open.
  - It asks when **Past sessions** opens, and every 15 seconds while that list is open.
  - When a reply arrives, the card changes to **Reply received** and Android shows a notification with sound. The notification gives only the case number; tapping it opens the session. Android 13+ asks once for permission to show notifications.
  - Past sessions shows "Waiting" or "Reply received" for every session that was sent.
  - If the app is closed, nothing is checked until it is opened again.

## What is sent, and what never is

A profile contains:
- interview answers
- the functional vision profile
- the power source and values (estimated band, starting trial lens, reading add, the customer's known power or the phone estimate)
- measurements (PD, face width, screen-test numbers)
- the app's estimates (lens type, material, referral level and reasons, notes)

**No name, no photo, no phone number.** The app only sends a profile after the consent text has been read aloud and the customer said yes (staff tick) *and* the customer tapped "I agree, send".

The server also rejects:
- any profile without both consents
- any field called name, photo, image, phone, email, address and similar, at any depth
- any string over 2,000 characters (so no picture can be pasted in as text)
- any body over 256 KB

Deleting the session in the app also deletes the profile and its review here.

## API

| Method | Path | |
|---|---|---|
| `POST` | `/profiles` | Consented profile JSON → `201 {id, status: "WAITING", receivedAt}` |
| `GET` | `/profiles` | List: id, received time, status, age, vision problem, power source, referral level |
| `GET` | `/profiles/{id}` | The full profile plus its review status |
| `POST` | `/profiles/{id}/review` | `{advice, needsInPersonExam}`. Posting again replaces the earlier review. |
| `GET` | `/profiles/{id}/review` | `{status: "WAITING"}` or `{status: "REVIEWED", advice, needsInPersonExam, reviewedAt}` |
| `DELETE` | `/profiles/{id}` | Removes the profile and its review |
| `GET` | `/health` | `{ok, tokenRequired}` |
| `GET` | `/` | The optometrist page |

Interactive docs: `http://localhost:8000/docs`.

**Token.** If the environment variable `BRIDGE_TOKEN` is set, every API call needs the header `X-Bridge-Token: <token>`. The optometrist page has a token field, and the app has one in staff settings. Always set a token before exposing the server beyond your own Wi-Fi.

## Run locally

Needs Python 3.10+.

```sh
cd backend
python -m venv .venv
.venv\Scripts\activate            # Windows   (macOS/Linux: source .venv/bin/activate)
pip install -r requirements-dev.txt
python -m pytest                  # API tests
set BRIDGE_TOKEN=pick-a-long-random-string     # Windows   (macOS/Linux: export BRIDGE_TOKEN=...)
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

- The optometrist page is at `http://localhost:8000/`.
- The data lives in `backend/bridge.db`. Set `BRIDGE_DB=<path>` to use another file. Delete the file to start fresh.

## Connect the app

1. On the home screen, turn on **staff mode** and tap **Optometrist bridge settings**.
2. Enter the server URL and the token, then tap **Test connection**.
3. After a session: on the summary, tap **Send to optometrist**. Go through the consent screen.
4. The card shows the case number and **Waiting**. It checks every 15 s and changes to **Reply received**, with a notification, when the optometrist submits advice. **Check for reply** checks at once.

**Same Wi-Fi (quickest for a demo).**
- URL: `http://<laptop IP>:8000`. Find the IP with `ipconfig` (Windows) or `ip addr`.
- Allow port 8000 through the laptop firewall.
- Plain `http://` works only in the debug APK. Release builds need `https://`.

## Expose it for the demo

Use an HTTPS tunnel so the phone (on mobile data) and the optometrist (anywhere) can reach your laptop. Either of these works:

```sh
# Cloudflare quick tunnel (no account needed)
cloudflared tunnel --url http://localhost:8000

# or ngrok
ngrok http 8000
```

Each prints an `https://…` address. Then:
1. Put that address in the app's bridge settings.
2. Send the same address to the optometrist.
3. Share the token separately (not in the same message).

Stop the tunnel after the demo.

## AI voice interview (Sarvam)

The same server also runs the optional **Voice interview (AI)**. The flow is: phone → this backend → Sarvam → this backend → phone. The Sarvam key lives only in `backend/.env` and is never in the APK.

**Setup.**

```sh
cd backend
copy .env.example .env        # Windows   (macOS/Linux: cp .env.example .env)
# edit .env: SARVAM_API_KEY=<your key from dashboard.sarvam.ai>; optionally BRIDGE_TOKEN=<long random string>
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

`/health` shows `"voiceAi": true` when the key is loaded. `.env` is git-ignored; real environment variables override it.

| Variable | Default | |
|---|---|---|
| `SARVAM_API_KEY` | (none) | Required for the AI interview. |
| `BRIDGE_TOKEN` | (none) | Shared token, header `X-Bridge-Token`; protects these endpoints too. |
| `SARVAM_STT_MODEL` | `saaras:v4` | Speech-to-text, `POST /speech-to-text`. |
| `SARVAM_CHAT_MODEL` | `sarvam-105b` | Chat, `POST /v1/chat/completions`, JSON mode, reasoning off. |
| `SARVAM_TTS_MODEL` / `SARVAM_TTS_SPEAKER` | `bulbul:v3` / `priya` | Text-to-speech, `POST /text-to-speech`, 16 kHz WAV. |
| `SARVAM_TTS_PACE` | `0.9` | Slightly slower speech. |
| `SARVAM_TIMEOUT_S` | `25` | Per Sarvam call. |

Endpoints and model names follow docs.sarvam.ai as of 12 Nov 2026. If Sarvam renames a model, change the variable; no code change is needed.

**Endpoints.**

| Endpoint | Body | Returns |
|---|---|---|
| `POST /anamnesis/start` | `{"language": "hi-IN"}` or `"en-IN"` | `session_id`, `question`, `audio` (base64 WAV or null), `tts_error`, `progress` |
| `POST /anamnesis/turn` | `{"session_id", "audio"}`: base64 WAV, 16 kHz mono 16-bit, at most 15 s | `transcript`, `extracted`, `next_question`, `audio`, `should_continue`, `urgent`, `source` (`ai` or `scripted`), `progress` |
| `POST /anamnesis/finish` | `{"session_id"}` | `answers`, `red_flags_asked`, `red_flag_unclear`, `urgent`, `coverage`, `transcript` |

Each turn makes three calls: speech-to-text, then chat, then text-to-speech. Sessions are kept in memory for 30 minutes; a restart drops open interviews, and the app then switches to the standard interview. Audio is not stored.

**How the interview is decided (in code, not by the model).**
- There are 11 required topics:
  - age
  - main difficulty (near / far / both)
  - screen use
  - reading
  - distance tasks
  - headache
  - eye strain
  - outdoor vs indoor
  - night vision
  - existing glasses (and whether they remember the number)
  - red flags
- The red-flag question is always asked, as question 3, with fixed wording. A "yes" ends the interview as urgent.
- The interview stops at 8 questions, or when every topic is covered or has been asked twice.
- The chat model only extracts answers and phrases the next question about the topic the tracker chose. The backend falls back to the scripted question for that topic when:
  - the model returns invalid JSON
  - the call fails
  - the question contains a diagnosis, condition, power or lens word
- If the customer says a red-flag word (flash, curtain, sudden, pain, चमक, पर्दा, अचानक, दर्द, …) and the model did not mark a red flag, the red flag is marked *unclear*. The phone then asks the standard red-flag questions itself.

**Errors.** The app only ever sees a short code in `{"detail": {"code": ...}}`. Raw Sarvam errors and the key are never returned or logged.

| Code | Meaning |
|---|---|
| `NOT_CONFIGURED` | No key. |
| `SARVAM_AUTH` | Key rejected. |
| `RATE_LIMITED`, `SARVAM_UNAVAILABLE`, `NETWORK`, `BAD_RESPONSE`, `SARVAM_REJECTED` | Sarvam busy, unreachable, or gave an unusable answer. |
| `EMPTY_TRANSCRIPT` | Nothing heard; the same question is asked again and not counted. |
| `BAD_AUDIO`, `AUDIO_TOO_LONG` | The recording is not 16 kHz mono WAV, or is over 15 s. |
| `SESSION_EXPIRED` | Unknown or expired `session_id`. |

A TTS failure is not an error: the question text comes back with `audio: null` and `tts_error: "TTS_FAILED"`, and the phone reads it with its own voice.

**On the phone.**
1. Staff mode → **Optometrist bridge settings**: the same server address and token as above (laptop IP on the same Wi-Fi, or the cloudflared / ngrok `https://` address).
2. Start a check. Under the first question, tap **Voice interview (AI)**.
3. Choose Hindi or English and read the consent. Then tap **Continue**, or **Use buttons instead**.
4. For each question: tap the mic, speak, and tap again (it stops by itself at 15 s).
5. When it ends, the standard interview continues at the first question still unanswered. The power question always comes back, so the power path runs as usual.
6. Then the confirmation screen, where staff can correct anything, and the usual flow.

If the backend fails twice in a row, the phone shows "Voice AI unavailable, continuing with the standard interview" and keeps the answers so far. It does the same at once for a missing key, a wrong token or no server.

## Status

This is a prototype for demos:
- one shared token, no per-user accounts
- no audit log
- SQLite on one machine

Real customer use would need proper authentication, hosting with encryption at rest, a data-retention rule, and a check against India's DPDP Act. Use demo sessions for recordings.
