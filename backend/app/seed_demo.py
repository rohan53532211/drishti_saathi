"""Demo data for the teleconsult portal. Run from backend/:  python -m app.seed_demo   (add --reset to start again)

Creates (only what is missing; running it twice changes nothing):
- 2 approved optometrists and 1 pending one, 3 patients: every name is marked DEMO and the rows carry demo = 1;
- 3 consultations waiting in the queue: one URGENT, one CHILD_MYOPIA, one ROUTINE.

All demo accounts get the password in DEMO_PASSWORD (backend/.env; never committed). The admin account is not made
here: it comes from ADMIN_EMAIL / ADMIN_PASSWORD when the server starts. --reset first deletes every demo account
(rows with demo = 1) with its consultations, chats and prescriptions; real accounts are never touched. The phone numbers are placeholders; set
DEMO_PATIENT_PHONE / DEMO_OPTOMETRIST_PHONE to your own numbers if you want to try the WhatsApp buttons.
"""
from __future__ import annotations

import json
import os
import sqlite3
import sys
import uuid
from pathlib import Path

from . import teleconsult as tc

try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parent.parent / ".env", override=False)
except ImportError:  # pragma: no cover
    pass

OPTOMETRISTS = [
    {"name": "DEMO Dr. Meera Sharma", "phone": "9000000101", "email": "demo.meera@example.com", "qualification": "M.Optom",
     "registration_no": "DEMO-OPT-0001", "clinic_name": "DEMO Netra Eye Care", "city": "Kanpur", "languages": ["Hindi", "English"],
     "fee": 200, "status": "APPROVED", "allow_whatsapp": 1},
    {"name": "DEMO Dr. Arjun Verma", "phone": "9000000102", "email": "demo.arjun@example.com", "qualification": "B.Optom",
     "registration_no": "DEMO-OPT-0002", "clinic_name": "DEMO Drishti Clinic", "city": "Lucknow", "languages": ["Hindi", "English", "Bhojpuri"],
     "fee": 150, "status": "APPROVED", "allow_whatsapp": 0},
    {"name": "DEMO Dr. Kavita Singh", "phone": "9000000103", "email": "demo.kavita@example.com", "qualification": "D.Optom",
     "registration_no": "DEMO-OPT-0003", "clinic_name": "DEMO Jyoti Opticals", "city": "Unnao", "languages": ["Hindi"],
     "fee": 100, "status": "PENDING", "allow_whatsapp": 0},
]
PATIENTS = [
    {"name": "DEMO Ramesh Kumar", "phone": "9000000201", "age": 58, "gender": "M", "language": "HI", "case": "URGENT"},
    {"name": "DEMO Anaya Gupta", "phone": "9000000202", "age": 11, "gender": "F", "language": "HI", "case": "CHILD_MYOPIA"},
    {"name": "DEMO Sunita Yadav", "phone": "9000000203", "age": 34, "gender": "F", "language": "EN", "case": "ROUTINE"},
]


def ring(score: int, label: str, answered: bool = True) -> dict:
    return {"score": score, "label": label, "answered": answered}


def grid(eye: str, cells: list[tuple[int, int]] = ()) -> dict:
    return {"eye": eye, "distortion": bool(cells), "cells": [{"col": c, "row": r} for c, r in cells], "columns": 12, "rows": 20, "distanceMm": 300.0}


def report(kind: str, age: int) -> dict:
    """A sample report in the shape the app sends (numbers and answers only: no name, photo or phone)."""
    consent = {"consentAt": tc.now_ms(), "spoken": True, "tapped": True, "textVersion": "teleconsult-demo"}
    pd = {"value": 62.5, "confidence": "MEDIUM", "source": "MEASURED"}
    if kind == "URGENT":
        return {
            "consent": consent, "demo": True, "app": {"version": "demo", "lang": "HI"},
            "triage": {"tag": "URGENT", "redFlags": ["NEW_FLASHES", "AMSLER_DISTORTION"], "emergency": True, "path": "Short path (emergency)",
                       "redFlagReasons": ["New flashes of light", "Wavy, bent or missing lines on the grid test – left eye"],
                       "childMyopia": False, "lensSuggested": False, "powerLabel": "For the optometrist only", "ageBand": "45-59"},
            "answers": {"age": age, "visionProblem": "BOTH", "redFlagFlashes": True, "redFlagOnset": "THIS_WEEK", "redFlagEye": "LEFT", "diabetesOrBp": True},
            "power": {"phoneRange": {"right": "−1.00 to −1.50 D (read up to 80 cm; phone estimate, to be confirmed with a trial lens)",
                                     "left": "−1.25 to −1.75 D (read up to 67 cm; phone estimate, to be confirmed with a trial lens)"}},
            "measurements": {"pdMm": pd},
            "visionProfile": {"work": ring(50, "Balanced", False), "digital": ring(0, "Light", False), "mobility": ring(5, "Rarely", False),
                              "leisure": ring(0, "Low Sun & UV Exposure", False), "overall": 14, "nightGlareSensitive": False},
            "screening": {"label": "Screening, not a diagnosis",
                          "lines": [{"text": "Grid R: lines straight", "status": "OK"},
                                    {"text": "Grid L: wavy or missing lines reported (3 square(s) marked)", "status": "URGENT"}],
                          "results": {"amsler": [grid("RIGHT"), grid("LEFT", [(5, 9), (6, 9), (6, 10)])]}},
            "lens": {"label": "Suggested ZEISS lens family – optician confirms", "suggested": False, "ids": [], "items": []},
            "estimates": {"referralLevel": "URGENT", "referralReasons": ["RED_FLAG_FLASHES_FLOATERS_CURTAIN"],
                          "suggestionsNote": "No lens is suggested today. The eye doctor visit comes first."},
        }
    if kind == "CHILD_MYOPIA":
        return {
            "consent": consent, "demo": True, "app": {"version": "demo", "lang": "HI"},
            "triage": {"tag": "CHILD_MYOPIA", "redFlags": [], "redFlagReasons": [], "emergency": False, "childMyopia": True,
                       "lensSuggested": False, "powerLabel": "For the optometrist only", "ageBand": "<18"},
            "answers": {"age": age, "visionProblem": "DISTANCE", "distanceSituation": "CLASSROOM_BOARD", "distanceSince": "UNDER_6_MONTHS",
                        "screenHours": "H4_PLUS", "wearsGlasses": False, "redFlagFlashes": False, "redFlagOther": False},
            "power": {"phoneRange": {"right": "−1.75 to −2.25 D (read up to 50 cm; phone estimate, to be confirmed with a trial lens)",
                                     "left": "−1.50 to −2.00 D (read up to 57 cm; phone estimate, to be confirmed with a trial lens)"}},
            "measurements": {"pdMm": {"value": 56.0, "confidence": "MEDIUM", "source": "MEASURED"}},
            "visionProfile": {"work": ring(100, "Near-focused"), "digital": ring(50, "Connected"), "mobility": ring(5, "Rarely"),
                              "leisure": ring(17, "Low Sun & UV Exposure"), "overall": 43, "nightGlareSensitive": False},
            "screening": {"label": "Screening, not a diagnosis",
                          "lines": [{"text": "Grid R: lines straight", "status": "OK"}, {"text": "Grid L: lines straight", "status": "OK"},
                                    {"text": "Contrast R: lowest step seen 3% – Normal", "status": "OK"}, {"text": "Contrast L: lowest step seen 3% – Normal", "status": "OK"},
                                    {"text": "Colour: Normal", "status": "OK"}],
                          "results": {"amsler": [grid("RIGHT"), grid("LEFT")]}},
            "lens": {"label": "Suggested ZEISS lens family – optician confirms", "suggested": False, "ids": [], "items": [], "referralInfo": "CHILD_MYOPIA"},
            "estimates": {"referralLevel": "ROUTINE", "referralReasons": [], "suggestionsNote": "No lens is suggested today. The eye doctor visit comes first."},
        }
    return {
        "consent": consent, "demo": True, "app": {"version": "demo", "lang": "EN"},
        "triage": {"tag": "ROUTINE", "redFlags": [], "redFlagReasons": [], "emergency": False, "childMyopia": False, "lensSuggested": True, "ageBand": "30-44"},
        "answers": {"age": age, "visionProblem": "DISTANCE", "occupation": "OFFICE_COMPUTER", "screenHours": "H4_PLUS", "headacheStrain": True,
                    "nightDriving": True, "budget": "MID", "redFlagFlashes": False, "redFlagOther": False},
        "power": {"phoneRange": {"right": "Measured through −2.50 D lens, residual −2.25 D, total −4.75 D (range −4.50 to −5.00)",
                                 "left": "−2.25 to −2.75 D (read up to 40 cm; phone estimate, to be confirmed with a trial lens)"}},
        "measurements": {"pdMm": pd},
        "visionProfile": {"work": ring(75, "Near-focused"), "digital": ring(80, "Digital Native"), "mobility": ring(65, "Weekly"),
                          "leisure": ring(17, "Low Sun & UV Exposure"), "overall": 59, "nightGlareSensitive": True},
        "screening": {"label": "Screening, not a diagnosis",
                      "lines": [{"text": "Grid R: lines straight", "status": "OK"}, {"text": "Grid L: lines straight", "status": "OK"},
                                {"text": "Contrast R: Test not valid – repeat with glasses/trial lens on", "status": "WATCH"},
                                {"text": "Contrast L: lowest step seen 3% – Normal", "status": "OK"},
                                {"text": "Colour: Normal", "status": "OK"},
                                {"text": "Clock dial R: some lines darker: possible astigmatism, axis about 90°", "status": "WATCH"}],
                      "results": {"amsler": [grid("RIGHT"), grid("LEFT")]}},
        "lens": {"label": "Suggested ZEISS lens family – optician confirms", "suggested": True, "optometristConfirms": False,
                 "ids": ["single_vision", "smartlife_digital", "blueguard"],
                 "items": [{"kind": "LENS_TYPE", "id": "single_vision", "product": "ZEISS Single Vision", "why": ["One power for far; you are under 40"]},
                           {"kind": "DESIGN", "id": "smartlife_digital", "product": "ZEISS SmartLife Digital", "why": ["You use screens 8 h a day", "You told us about eye strain"]},
                           {"kind": "MATERIAL", "id": "blueguard", "product": "ZEISS BlueGuard", "why": ["You use screens 8 h a day"]}]},
        "estimates": {"referralLevel": "ROUTINE", "referralReasons": [], "notes": ["PHONE_ESTIMATE_APPROXIMATE"]},
    }


def seed(db_file: str, password: str, reset: bool = False) -> dict[str, int]:
    con = sqlite3.connect(db_file)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    made = {"optometrists": 0, "patients": 0, "consultations": 0}
    try:
        con.executescript(tc.SCHEMA)
        if reset:
            # demo patients take their consultations, chats and prescriptions with them (cascade)
            con.execute("DELETE FROM tc_users WHERE demo = 1")
        hashed = tc.hash_password(password)
        t = tc.now_ms()
        opt_phone, pat_phone = os.environ.get("DEMO_OPTOMETRIST_PHONE"), os.environ.get("DEMO_PATIENT_PHONE")
        for i, o in enumerate(OPTOMETRISTS):
            phone = tc.norm_phone(opt_phone) if (opt_phone and i == 0) else o["phone"]
            if con.execute("SELECT 1 FROM tc_users WHERE role = 'optometrist' AND (phone = ? OR email = ?)", (phone, o["email"])).fetchone():
                continue
            con.execute(
                "INSERT INTO tc_users (id, role, phone, email, password_hash, name, created_at, qualification, registration_no, clinic_name, city, "
                "languages, fee, available, allow_whatsapp, status, demo) VALUES (?, 'optometrist', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                (uuid.uuid4().hex, phone, o["email"], hashed, o["name"], t, o["qualification"], o["registration_no"], o["clinic_name"], o["city"],
                 json.dumps(o["languages"]), o["fee"], int(o["status"] == "APPROVED"), o["allow_whatsapp"], o["status"]))
            made["optometrists"] += 1
        for i, p in enumerate(PATIENTS):
            phone = tc.norm_phone(pat_phone) if (pat_phone and i == 2) else p["phone"]
            if con.execute("SELECT 1 FROM tc_users WHERE role = 'patient' AND phone = ?", (phone,)).fetchone():
                continue
            pid = uuid.uuid4().hex
            con.execute(
                "INSERT INTO tc_users (id, role, phone, password_hash, name, created_at, age, gender, language, consent_at, consent_text, demo) "
                "VALUES (?, 'patient', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                (pid, phone, hashed, p["name"], t, p["age"], p["gender"], p["language"], t, tc.CONSENT_TEXT))
            made["patients"] += 1
            rep = report(p["case"], p["age"])
            con.execute(
                "INSERT INTO tc_consultations (id, patient_id, report, priority, status, payment_status, created_at, updated_at) VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, ?)",
                (uuid.uuid4().hex, pid, json.dumps(rep), tc.priority_of(rep), tc.PAYMENT_DEMO, t - i * 60_000, t))
            made["consultations"] += 1
        con.commit()
    finally:
        con.close()
    return made


def main() -> int:
    password = os.environ.get("DEMO_PASSWORD") or ""
    if len(password) < 8:
        print("Set DEMO_PASSWORD (8 or more characters) in backend/.env first: every demo account gets this password.")
        return 1
    db_file = os.environ.get("BRIDGE_DB", str(Path(__file__).resolve().parent.parent / "bridge.db"))
    made = seed(db_file, password, reset="--reset" in sys.argv[1:])
    print(f"Demo data in {db_file}: {made['optometrists']} optometrists, {made['patients']} patients, {made['consultations']} consultations added.")
    print("Optometrist logins (password = DEMO_PASSWORD): " + ", ".join(o["phone"] + (" (pending)" if o["status"] == "PENDING" else "") for o in OPTOMETRISTS))
    print("Patient logins (password = DEMO_PASSWORD): " + ", ".join(p["phone"] for p in PATIENTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
