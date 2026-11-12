# Remote Optometrist Bridge

A small FastAPI + SQLite service. A shop using the Drishti Saathi app can send a customer's pre-consultation profile to a remote optometrist, who replies with advice and an "needs in-person exam" flag. The app shows the reply on the summary.

> यह चिकित्सीय निदान या पर्चा नहीं है / Not a medical diagnosis or prescription.
> The optometrist sees phone estimates that still have to be confirmed with the shop's trial lens.

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
4. The card shows **Waiting** until the optometrist submits advice. Tap **Check for reply** to see it.

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

## Status

This is a prototype for demos:
- one shared token, no per-user accounts
- no audit log
- SQLite on one machine

Real customer use would need proper authentication, hosting with encryption at rest, a data-retention rule, and a check against India's DPDP Act. Use demo sessions for recordings.
