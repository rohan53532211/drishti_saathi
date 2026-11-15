"""Load test for the teleconsult backend. Never run it against the demo server: it starts its own server on a
temporary database (default port 8010), drives it, checks the database afterwards and removes it.

    cd backend
    .venv\\Scripts\\python.exe tests\\load\\loadtest.py --patients 50 --seconds 120

Each simulated patient registers, logs in, sends one report (a consultation request), and then chats until the time
is up; three optometrists accept the cases and answer. Prints p50 / p95 / p99 latency per kind of call, the error
rate, and whether any message was lost or any case duplicated. Exit code 1 when a check fails.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
from collections import defaultdict
from pathlib import Path

import httpx

BACKEND = Path(__file__).resolve().parents[2]
PASSWORD = "load-test-pass-1"
ADMIN = {"email": "load-admin@test.example", "password": "load-admin-pass-1"}
REPORT = {
    "triage": {"tag": "ROUTINE", "redFlags": [], "redFlagReasons": []},
    "answers": {"age": 34, "visionProblem": "DISTANCE"},
    "power": {"phoneRange": {"right": "-2.00 to -2.50 D", "left": "-1.75 to -2.25 D"}},
    "visionProfile": {"work": {"score": 88, "label": "Near-focused", "answered": True}},
    "lens": {"ids": ["single_vision"]},
    "estimates": {"referralLevel": "NONE"},
}


class Stats:
    def __init__(self) -> None:
        self.ms: dict[str, list[float]] = defaultdict(list)
        self.status: dict[int, int] = defaultdict(int)
        self.failures: list[str] = []

    async def call(self, client: httpx.AsyncClient, kind: str, method: str, path: str, ok: tuple[int, ...] = (200, 201), **kw):
        t = time.perf_counter()
        try:
            r = await client.request(method, path, **kw)
        except httpx.HTTPError as e:
            self.ms[kind].append((time.perf_counter() - t) * 1000)
            self.status[0] += 1
            self.failures.append(f"{kind}: {type(e).__name__}")
            return None
        self.ms[kind].append((time.perf_counter() - t) * 1000)
        self.status[r.status_code] += 1
        if r.status_code not in ok:
            self.failures.append(f"{kind}: {r.status_code} {r.text[:120]}")
            return None
        return r


def pct(values: list[float], p: float) -> float:
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


async def patient(n: int, base: str, stats: Stats, stop_at: float, urgent: bool, sent: dict[str, list[str]], cases: dict[int, str]):
    async with httpx.AsyncClient(base_url=base, timeout=30) as c:
        phone = f"97{n:08d}"
        body = {"name": f"Load Patient {n}", "phone": phone, "password": PASSWORD, "age": 20 + n % 50, "language": "HI", "consent": True}
        if await stats.call(c, "register", "POST", "/api/auth/register/patient", json=body) is None:
            return
        r = await stats.call(c, "login", "POST", "/api/auth/login", json={"role": "patient", "identifier": phone, "password": PASSWORD})
        if r is None:
            return
        h = {"Authorization": "Bearer " + r.json()["token"]}
        report = dict(REPORT, triage={"tag": "URGENT", "redFlags": ["NEW_FLASHES"], "redFlagReasons": ["New flashes of light"]}) if urgent else REPORT
        r = await stats.call(c, "send report", "POST", "/api/consultations", json={"report": report}, headers=h)
        if r is None:
            return
        cid = r.json()["id"]
        cases[n] = cid
        sent[cid] = []
        k = 0
        while time.monotonic() < stop_at:
            r = await stats.call(c, "poll case", "GET", f"/api/consultations/{cid}", headers=h)
            if r is not None and r.json()["status"] in ("ACCEPTED", "IN_PROGRESS"):
                text = f"p{n}-m{k}"
                if await stats.call(c, "chat send", "POST", f"/api/consultations/{cid}/messages", json={"text": text}, headers=h) is not None:
                    sent[cid].append(text)
                    k += 1
                await stats.call(c, "chat read", "GET", f"/api/consultations/{cid}/messages", headers=h)
            await asyncio.sleep(1.0 + (n % 7) / 10)


async def optometrist(n: int, base: str, stats: Stats, stop_at: float, token: str, sent: dict[str, list[str]], accepted: dict[str, int]):
    h = {"Authorization": "Bearer " + token}
    mine: set[str] = set()
    k = 0
    async with httpx.AsyncClient(base_url=base, timeout=30) as c:
        while time.monotonic() < stop_at:
            r = await stats.call(c, "queue", "GET", "/api/consultations", headers=h)
            for case in (r.json() if r is not None else []):
                if case["status"] == "REQUESTED":
                    # two optometrists may go for the same case: exactly one wins, the other gets 409
                    a = await stats.call(c, "accept", "POST", f"/api/consultations/{case['id']}/accept", headers=h, ok=(200, 409, 404))
                    if a is not None and a.status_code == 200:
                        mine.add(case["id"])
                        accepted[case["id"]] = accepted.get(case["id"], 0) + 1
            for cid in list(mine)[k % 5::5]:
                text = f"o{n}-m{k}"
                if await stats.call(c, "chat send", "POST", f"/api/consultations/{cid}/messages", json={"text": text}, headers=h) is not None:
                    sent.setdefault(cid, []).append(text)
            k += 1
            await asyncio.sleep(1.5)


async def run(base: str, patients: int, seconds: int, stats: Stats):
    sent: dict[str, list[str]] = {}
    cases: dict[int, str] = {}
    accepted: dict[str, int] = {}
    tokens = []
    async with httpx.AsyncClient(base_url=base, timeout=30) as c:
        admin = (await c.post("/api/auth/login", json={"role": "admin", "identifier": ADMIN["email"], "password": ADMIN["password"]})).json()["token"]
        for n in range(3):
            body = {"name": f"Load Optom {n}", "phone": f"96{n:08d}", "email": f"optom{n}@load.example", "password": PASSWORD, "qualification": "B.Optom",
                    "registrationNo": f"LOAD-{n}", "clinicName": "Load Clinic", "city": "Kanpur", "languages": ["Hindi"], "fee": 100}
            r = await c.post("/api/auth/register/optometrist", json=body)
            r.raise_for_status()
            (await c.post(f"/api/admin/optometrists/{r.json()['user']['id']}/approve", headers={"Authorization": "Bearer " + admin})).raise_for_status()
            (await c.patch("/api/me", json={"available": True}, headers={"Authorization": "Bearer " + r.json()["token"]})).raise_for_status()
            tokens.append(r.json()["token"])
    stop_at = time.monotonic() + seconds
    await asyncio.gather(
        *(patient(n, base, stats, stop_at, n % 10 == 0, sent, cases) for n in range(patients)),
        *(optometrist(n, base, stats, stop_at, t, sent, accepted) for n, t in enumerate(tokens)),
    )
    return sent, cases, accepted


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--patients", type=int, default=50)
    ap.add_argument("--seconds", type=int, default=120)
    ap.add_argument("--port", type=int, default=8010)
    ap.add_argument("--json", default=None, help="write the numbers to this file")
    args = ap.parse_args()
    if args.port == 8000:
        print("port 8000 is the demo server; use another port")
        return 2

    tmp = tempfile.mkdtemp(prefix="drishti-load-")
    db = os.path.join(tmp, "load.db")
    env = dict(os.environ, BRIDGE_DB=db, ADMIN_EMAIL=ADMIN["email"], ADMIN_PASSWORD=ADMIN["password"], JWT_SECRET="load-test-secret-key-that-is-long-enough")
    server = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(args.port), "--log-level", "warning"],
                              cwd=BACKEND, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    base = f"http://127.0.0.1:{args.port}"
    try:
        for _ in range(60):
            try:
                if httpx.get(base + "/health", timeout=2).status_code == 200:
                    break
            except httpx.HTTPError:
                time.sleep(0.5)
        else:
            print("the test server did not start")
            return 2
        stats = Stats()
        started = time.monotonic()
        sent, cases, accepted = asyncio.run(run(base, args.patients, args.seconds, stats))
        took = time.monotonic() - started
    finally:
        server.terminate()
        try:
            err = server.communicate(timeout=10)[1].decode(errors="replace")
        except subprocess.TimeoutExpired:
            server.kill()
            err = ""

    # ---- what the database holds afterwards ----
    con = sqlite3.connect(db)
    try:
        n_patients = con.execute("SELECT COUNT(*) FROM tc_users WHERE role = 'patient'").fetchone()[0]
        n_cases = con.execute("SELECT COUNT(*) FROM tc_consultations").fetchone()[0]
        per_patient = [r[0] for r in con.execute("SELECT COUNT(*) FROM tc_consultations GROUP BY patient_id")]
        stored: dict[str, list[str]] = defaultdict(list)
        for cid, text in con.execute("SELECT consultation_id, text FROM tc_messages WHERE kind = 'TEXT' ORDER BY id"):
            stored[cid].append(text)
        urgent = con.execute("SELECT COUNT(*) FROM tc_consultations WHERE priority = 'URGENT'").fetchone()[0]
    finally:
        con.close()
    lost = sum(1 for cid, texts in sent.items() for t in texts if t not in stored[cid])
    doubled = sum(len(v) - len(set(v)) for v in stored.values())
    n_sent = sum(len(v) for v in sent.values())
    total = sum(stats.status.values())
    errors_5xx = sum(n for code, n in stats.status.items() if code >= 500 or code == 0)
    unexpected = len(stats.failures)

    print(f"\n{args.patients} patients + 3 optometrists for {took:.0f} s on {base} (temporary database)")
    print(f"{'call':<12}{'count':>8}{'p50 ms':>10}{'p95 ms':>10}{'p99 ms':>10}{'max ms':>10}")
    rows = {}
    for kind in sorted(stats.ms):
        v = stats.ms[kind]
        rows[kind] = {"count": len(v), "p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1), "p99": round(pct(v, 99), 1), "max": round(max(v), 1)}
        print(f"{kind:<12}{len(v):>8}{pct(v, 50):>10.0f}{pct(v, 95):>10.0f}{pct(v, 99):>10.0f}{max(v):>10.0f}")
    everything = [x for v in stats.ms.values() for x in v]
    print(f"{'ALL':<12}{len(everything):>8}{pct(everything, 50):>10.0f}{pct(everything, 95):>10.0f}{pct(everything, 99):>10.0f}{max(everything):>10.0f}")
    print(f"requests: {total} ({total / took:.1f}/s), mean {statistics.mean(everything):.0f} ms")
    print(f"status codes: {dict(sorted(stats.status.items()))}")
    print(f"5xx or connection errors: {errors_5xx}; unexpected answers: {unexpected} ({100 * unexpected / max(total, 1):.2f} %)")
    print(f"patients registered: {n_patients}/{args.patients}; cases: {n_cases} (one per patient: {all(c == 1 for c in per_patient)}); urgent: {urgent}")
    print(f"cases accepted: {len(accepted)}; accepted twice: {sum(1 for v in accepted.values() if v > 1)}")
    print(f"chat messages sent: {n_sent}; lost: {lost}; stored twice: {doubled}")
    for f in stats.failures[:10]:
        print("  failure:", f)
    if "Traceback" in err:
        print("server traceback (last lines):", *err.strip().splitlines()[-6:], sep="\n  ")

    ok = (errors_5xx == 0 and unexpected == 0 and n_patients == args.patients and n_cases == args.patients and all(c == 1 for c in per_patient)
          and lost == 0 and doubled == 0 and all(v == 1 for v in accepted.values()) and "Traceback" not in err)
    print("RESULT:", "PASS" if ok else "FAIL")
    if args.json:
        Path(args.json).write_text(json.dumps({
            "patients": args.patients, "seconds": round(took), "requests": total, "perSecond": round(total / took, 1), "calls": rows,
            "all": {"p50": round(pct(everything, 50), 1), "p95": round(pct(everything, 95), 1), "p99": round(pct(everything, 99), 1), "max": round(max(everything), 1)},
            "status": dict(sorted(stats.status.items())), "errors5xx": errors_5xx, "unexpected": unexpected, "cases": n_cases, "accepted": len(accepted),
            "messagesSent": n_sent, "lost": lost, "storedTwice": doubled, "pass": ok}, indent=1), encoding="utf-8")
    try:
        for name in os.listdir(tmp):
            os.remove(os.path.join(tmp, name))
        os.rmdir(tmp)
    except OSError:
        pass
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
