"""Teleconsult: patient and optometrist accounts, consultations, chat, video link and the advice / e-prescription PDF.

Roles: patient, optometrist, admin. Passwords are hashed with bcrypt; every endpoint needs a JWT (Authorization:
Bearer) and checks the role. An optometrist is PENDING until an admin approves; only APPROVED optometrists see cases.
A patient's name is shared only with their explicit consent (stored with its time) and the patient can delete the
account with every consultation, message and prescription.

Demo scope: payments are a placeholder ("not_required (demo)"), login is phone + password (no OTP), and the server
is expected to run on a LAN over http. Production needs HTTPS hosting, real OTP login and payment integration.
"""
from __future__ import annotations

import base64
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from datetime import date
from pathlib import Path
from typing import Any, Callable, Optional

import bcrypt
import jwt
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field

STATIC = Path(__file__).parent / "static"
ROLES = ("patient", "optometrist", "admin")
STATUSES = ("REQUESTED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CLOSED")
PRIORITIES = ("URGENT", "CHILD_MYOPIA", "ROUTINE")
PAYMENT_DEMO = "not_required (demo)"
CONSENT_TEXT = "I agree my report and name are shared with the optometrist I choose"
URGENT_NOTE = "A teleconsult never replaces an urgent visit. Please see an eye doctor today."
TOKEN_DAYS = 7
MAX_REPORT_BYTES = 256 * 1024
MAX_IMAGE_BYTES = 600 * 1024
IMAGE_MIMES = ("image/jpeg", "image/png", "image/webp")
DEFAULT_LENS_CATALOG = Path(__file__).resolve().parents[2] / "app" / "app" / "src" / "main" / "assets" / "lens_catalog.json"

SCHEMA = """
CREATE TABLE IF NOT EXISTS tc_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tc_users (
    id TEXT PRIMARY KEY,
    role TEXT NOT NULL,
    phone TEXT,
    email TEXT,
    password_hash TEXT NOT NULL,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    -- patient
    age INTEGER, gender TEXT, language TEXT, consent_at INTEGER, consent_text TEXT,
    -- optometrist
    qualification TEXT, registration_no TEXT, clinic_name TEXT, city TEXT, languages TEXT, fee INTEGER,
    available INTEGER NOT NULL DEFAULT 0, allow_whatsapp INTEGER NOT NULL DEFAULT 0, photo TEXT,
    status TEXT, demo INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS tc_users_role_phone ON tc_users(role, phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS tc_users_role_email ON tc_users(role, email) WHERE email IS NOT NULL;
CREATE TABLE IF NOT EXISTS tc_consultations (
    id TEXT PRIMARY KEY,
    patient_id TEXT NOT NULL REFERENCES tc_users(id) ON DELETE CASCADE,
    optometrist_id TEXT REFERENCES tc_users(id) ON DELETE SET NULL,
    requested_optometrist_id TEXT REFERENCES tc_users(id) ON DELETE SET NULL,
    report TEXT NOT NULL,
    priority TEXT NOT NULL,
    status TEXT NOT NULL,
    fee INTEGER,
    payment_status TEXT NOT NULL,
    video_url TEXT,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
    accepted_at INTEGER, started_at INTEGER, completed_at INTEGER, closed_at INTEGER
);
CREATE TABLE IF NOT EXISTS tc_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    consultation_id TEXT NOT NULL REFERENCES tc_consultations(id) ON DELETE CASCADE,
    sender_id TEXT, sender_role TEXT NOT NULL,
    kind TEXT NOT NULL,
    text TEXT, image BLOB, image_mime TEXT,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tc_prescriptions (
    consultation_id TEXT PRIMARY KEY REFERENCES tc_consultations(id) ON DELETE CASCADE,
    data TEXT NOT NULL,
    optometrist_name TEXT NOT NULL, registration_no TEXT NOT NULL, qualification TEXT, clinic TEXT,
    created_at INTEGER NOT NULL
);
"""


# ---- request bodies --------------------------------------------------------------------------------

class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class PatientRegister(_Strict):
    name: str = Field(min_length=1, max_length=80)
    phone: str = Field(min_length=10, max_length=16)
    password: str = Field(min_length=8, max_length=128)
    age: int = Field(ge=1, le=120)
    gender: Optional[str] = Field(default=None, max_length=20)
    language: str = Field(default="HI", max_length=10)
    consent: bool = Field(description=CONSENT_TEXT)


class OptometristRegister(_Strict):
    name: str = Field(min_length=1, max_length=80)
    phone: str = Field(min_length=10, max_length=16)
    email: str = Field(min_length=5, max_length=120)
    password: str = Field(min_length=8, max_length=128)
    qualification: str = Field(min_length=1, max_length=120)
    registrationNo: str = Field(min_length=1, max_length=60)
    clinicName: str = Field(min_length=1, max_length=120)
    city: str = Field(min_length=1, max_length=60)
    languages: list[str] = Field(min_length=1, max_length=10)
    fee: int = Field(ge=0, le=100000)
    allowWhatsapp: bool = False
    photo: Optional[str] = Field(default=None, max_length=300_000, description="data: URL of a small picture")


class Login(_Strict):
    role: str
    identifier: str = Field(min_length=3, max_length=120, description="phone (patient, optometrist) or email (optometrist, admin)")
    password: str = Field(min_length=1, max_length=128)


class ProfileUpdate(_Strict):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    age: Optional[int] = Field(default=None, ge=1, le=120)
    gender: Optional[str] = Field(default=None, max_length=20)
    language: Optional[str] = Field(default=None, max_length=10)
    qualification: Optional[str] = Field(default=None, min_length=1, max_length=120)
    clinicName: Optional[str] = Field(default=None, min_length=1, max_length=120)
    city: Optional[str] = Field(default=None, min_length=1, max_length=60)
    languages: Optional[list[str]] = Field(default=None, min_length=1, max_length=10)
    fee: Optional[int] = Field(default=None, ge=0, le=100000)
    available: Optional[bool] = None
    allowWhatsapp: Optional[bool] = None
    photo: Optional[str] = Field(default=None, max_length=300_000)


class ConsultationIn(_Strict):
    report: dict[str, Any]
    optometristId: Optional[str] = Field(default=None, max_length=64, description="None = any available optometrist")


class MessageIn(_Strict):
    text: Optional[str] = Field(default=None, max_length=2000)
    imageBase64: Optional[str] = Field(default=None, max_length=900_000)
    imageMime: Optional[str] = Field(default=None, max_length=20)


class EyePower(_Strict):
    sph: Optional[float] = Field(default=None, ge=-30, le=30)
    cyl: Optional[float] = Field(default=None, ge=-10, le=10)
    axis: Optional[int] = Field(default=None, ge=0, le=180)


class PrescriptionIn(_Strict):
    right: EyePower = Field(default_factory=EyePower)
    left: EyePower = Field(default_factory=EyePower)
    add: Optional[float] = Field(default=None, ge=0, le=4)
    pdMm: Optional[float] = Field(default=None, ge=40, le=80)
    lensAdvice: list[str] = Field(default_factory=list, max_length=12, description="Names from the lens catalogue or free text")
    referralAdvice: Optional[str] = Field(default=None, max_length=1000)
    needsInPersonExam: bool = False
    followUpDate: Optional[date] = None
    notes: Optional[str] = Field(default=None, max_length=2000)


# ---- helpers ---------------------------------------------------------------------------------------

def now_ms() -> int:
    return int(time.time() * 1000)


def norm_phone(phone: str) -> str:
    """Digits only; a 10-digit Indian number as typed (no country code is stored)."""
    digits = re.sub(r"\D", "", phone or "")
    if digits.startswith("91") and len(digits) == 12:
        digits = digits[2:]
    if digits.startswith("0") and len(digits) == 11:
        digits = digits[1:]
    if not 10 <= len(digits) <= 13:
        raise HTTPException(status_code=422, detail="phone must have 10 digits")
    return digits


def wa_number(phone: str) -> str:
    return "91" + phone if len(phone) == 10 else phone


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8")[:72], bcrypt.gensalt()).decode("ascii")


def check_password(password: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8")[:72], hashed.encode("ascii"))
    except ValueError:
        return False


def priority_of(report: dict[str, Any]) -> str:
    """URGENT / CHILD_MYOPIA / ROUTINE from the report's own triage tag; an urgent referral level is always URGENT."""
    tag = str((report.get("triage") or {}).get("tag") or "").upper()
    if (report.get("estimates") or {}).get("referralLevel") == "URGENT":
        return "URGENT"
    return tag if tag in PRIORITIES else "ROUTINE"


def case_id(cid: str) -> str:
    return cid[:6].upper()


def jitsi_room() -> str:
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return "https://meet.jit.si/drishti-" + "".join(secrets.choice(alphabet) for _ in range(12))


def greeting(to_name: str, cid: str, from_name: str, lang: str, from_optometrist: bool) -> str:
    """The prefilled WhatsApp text, in Hindi for a Hindi session and in English otherwise."""
    if lang.upper().startswith("HI"):
        who = f"मैं ऑप्टोमेट्रिस्ट {from_name} हूँ" if from_optometrist else f"मैं {from_name} हूँ"
        return f"नमस्ते {to_name}, {who}। दृष्टि साथी केस #{case_id(cid)} के बारे में बात करनी है।"
    who = f"this is optometrist {from_name}" if from_optometrist else f"this is {from_name}"
    return f"Hello {to_name}, {who}. This is about Drishti Saathi case #{case_id(cid)}."


def lens_options(path: Optional[Path]) -> list[dict[str, str]]:
    """Lens names the prescription form can pick from: the app's own lens_catalog.json when it is there."""
    f = Path(os.environ.get("LENS_CATALOG") or path or DEFAULT_LENS_CATALOG)
    if not f.exists():
        return []
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    out: list[dict[str, str]] = []
    for group in ("lens_types", "designs", "materials", "coatings", "second_pair_sun"):
        items = data.get(group) or []
        if isinstance(items, dict):
            items = [dict(v, id=k) if isinstance(v, dict) else {"id": k} for k, v in items.items()]
        for it in items:
            if isinstance(it, dict) and it.get("id"):
                out.append({"group": group, "id": str(it["id"]), "name": str(it.get("name") or it.get("product") or it["id"])})
    return out


def _fmt_d(v: Optional[float]) -> str:
    return "-" if v is None else f"{v:+.2f}"


def prescription_pdf(rx: dict[str, Any], meta: dict[str, Any]) -> bytes:
    """One A4 page: optometrist, patient, the power table, add, PD, lens advice, referral, follow-up, notes."""
    from fpdf import FPDF

    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(True, margin=18)
    pdf.add_page()
    font = "Helvetica"
    regular, bold = STATIC / "fonts" / "inter_deva_regular.ttf", STATIC / "fonts" / "inter_deva_bold.ttf"
    if regular.exists() and bold.exists():   # a Unicode font, so Hindi names and notes do not break the PDF
        pdf.add_font("DS", "", str(regular))
        pdf.add_font("DS", "B", str(bold))
        font = "DS"

    def clean(s: Any) -> str:
        s = "" if s is None else str(s)
        return s if font == "DS" else s.encode("latin-1", "replace").decode("latin-1")

    def line(text: str, size: int = 11, style: str = "", colour: tuple[int, int, int] = (11, 27, 43), gap: float = 6.5) -> None:
        pdf.set_font(font, style, size)
        pdf.set_text_color(*colour)
        pdf.multi_cell(0, gap, clean(text), new_x="LMARGIN", new_y="NEXT")

    muted = (91, 107, 124)
    line("Drishti Saathi", 20, "B", (26, 92, 255), 10)
    line("Teleconsultation advice / e-prescription", 13, "B")
    line(f"Case #{meta['caseId']}  -  Date: {meta['date']}", 10, "", muted)
    pdf.ln(3)
    line(f"Optometrist: {meta['optometristName']}" + (f", {meta['qualification']}" if meta.get("qualification") else ""), 11, "B")
    line(f"Registration no.: {meta['registrationNo']}" + (f"  -  {meta['clinic']}" if meta.get("clinic") else ""), 10, "", muted)
    line(f"Patient: {meta['patientName']}  -  Age: {meta['patientAge']}", 11, "B")
    pdf.ln(3)

    pdf.set_draw_color(220, 226, 232)
    widths = (30, 40, 40, 40)
    pdf.set_font(font, "B", 10)
    pdf.set_fill_color(240, 244, 255)
    for w, h in zip(widths, ("Eye", "Sphere (D)", "Cylinder (D)", "Axis")):
        pdf.cell(w, 9, h, border=1, fill=True, align="C")
    pdf.ln()
    pdf.set_font(font, "", 11)
    for label, key in (("Right (R)", "right"), ("Left (L)", "left")):
        e = rx.get(key) or {}
        cells = (label, _fmt_d(e.get("sph")), _fmt_d(e.get("cyl")), "-" if e.get("axis") is None else str(e["axis"]))
        for w, c in zip(widths, cells):
            pdf.cell(w, 9, c, border=1, align="C")
        pdf.ln()
    pdf.ln(3)
    if rx.get("add") is not None:
        line(f"Near addition (ADD): {_fmt_d(rx['add'])} D")
    if rx.get("pdMm") is not None:
        line(f"PD: {rx['pdMm']:.1f} mm")
    if rx.get("lensAdvice"):
        line("Lens advice", 11, "B")
        for item in rx["lensAdvice"]:
            line(f"  - {item}")
    if rx.get("needsInPersonExam"):
        line("An in-person eye examination is advised.", 11, "B", (201, 42, 42))
    if rx.get("referralAdvice"):
        line("Referral advice", 11, "B")
        line(rx["referralAdvice"])
    if rx.get("followUpDate"):
        line(f"Follow-up: {rx['followUpDate']}")
    if rx.get("notes"):
        line("Notes", 11, "B")
        line(rx["notes"])
    pdf.ln(4)
    line("Issued after a remote consultation based on a phone screening. The power must be confirmed with a trial lens "
         "or an in-person refraction before glasses are made. A teleconsult never replaces an urgent visit to an eye doctor.",
         9, "", muted, 5)
    return bytes(pdf.output())


# ---- router ----------------------------------------------------------------------------------------

def router(db: Callable[[], Any], secret: Optional[str] = None, lens_catalog: Optional[str] = None,
           admin_email: Optional[str] = None, admin_password: Optional[str] = None,
           report_problems: Callable[[Any], list[str]] = lambda report: []) -> APIRouter:
    """[db]: the app's connection context manager. [secret]: JWT key (else JWT_SECRET, else one kept in the database).
    [report_problems]: the bridge's privacy check; the report itself never carries a name, photo or contact detail
    (the patient's name comes from the account, with consent)."""
    r = APIRouter(prefix="/api", tags=["teleconsult"])

    with db() as con:
        con.executescript(SCHEMA)
        key = secret or os.environ.get("JWT_SECRET")
        if not key:
            row = con.execute("SELECT value FROM tc_settings WHERE key = 'jwt_secret'").fetchone()
            key = row["value"] if row else secrets.token_urlsafe(48)
            if not row:
                con.execute("INSERT INTO tc_settings (key, value) VALUES ('jwt_secret', ?)", (key,))
        # one admin, from the environment only (never from the repository)
        email = (admin_email if admin_email is not None else os.environ.get("ADMIN_EMAIL") or "").strip().lower()
        password = admin_password if admin_password is not None else os.environ.get("ADMIN_PASSWORD") or ""
        if email and password:
            row = con.execute("SELECT id, password_hash FROM tc_users WHERE role = 'admin' AND email = ?", (email,)).fetchone()
            if row is None:
                con.execute("INSERT INTO tc_users (id, role, email, password_hash, name, created_at) VALUES (?, 'admin', ?, ?, 'Admin', ?)",
                            (uuid.uuid4().hex, email, hash_password(password), now_ms()))
            elif not check_password(password, row["password_hash"]):
                con.execute("UPDATE tc_users SET password_hash = ? WHERE id = ?", (hash_password(password), row["id"]))
    jwt_key: str = key

    # ---- auth ----
    def make_token(user: sqlite3.Row) -> str:
        return jwt.encode({"sub": user["id"], "role": user["role"], "exp": int(time.time()) + TOKEN_DAYS * 86400}, jwt_key, algorithm="HS256")

    def current_user(authorization: Optional[str] = Header(default=None)) -> sqlite3.Row:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(status_code=401, detail="login required")
        try:
            claims = jwt.decode(authorization[7:].strip(), jwt_key, algorithms=["HS256"])
        except jwt.PyJWTError:
            raise HTTPException(status_code=401, detail="session expired or invalid, log in again")
        with db() as con:
            user = con.execute("SELECT * FROM tc_users WHERE id = ?", (claims.get("sub"),)).fetchone()
        if user is None or user["role"] != claims.get("role"):
            raise HTTPException(status_code=401, detail="account not found")
        return user

    def need(*roles: str, approved: bool = False) -> Callable[..., sqlite3.Row]:
        def dep(user: sqlite3.Row = Depends(current_user)) -> sqlite3.Row:
            if user["role"] not in roles:
                raise HTTPException(status_code=403, detail=f"only for: {', '.join(roles)}")
            if approved and user["role"] == "optometrist" and user["status"] != "APPROVED":
                raise HTTPException(status_code=403, detail="your account is waiting for admin approval")
            return user
        return dep

    # ---- views ----
    def optometrist_view(u: sqlite3.Row, private: bool = False) -> dict[str, Any]:
        out = {
            "id": u["id"], "role": "optometrist", "name": u["name"], "qualification": u["qualification"],
            "registrationNo": u["registration_no"], "clinicName": u["clinic_name"], "city": u["city"],
            "languages": json.loads(u["languages"] or "[]"), "fee": u["fee"], "available": bool(u["available"]),
            "allowWhatsapp": bool(u["allow_whatsapp"]), "photo": u["photo"], "demo": bool(u["demo"]),
        }
        if private:
            out.update({"phone": u["phone"], "email": u["email"], "status": u["status"], "createdAt": u["created_at"]})
        return out

    def patient_view(u: sqlite3.Row, with_phone: bool) -> dict[str, Any]:
        out = {"id": u["id"], "role": "patient", "name": u["name"], "age": u["age"], "gender": u["gender"],
               "language": u["language"], "consentAt": u["consent_at"], "demo": bool(u["demo"])}
        if with_phone:
            out["phone"] = u["phone"]
        return out

    def me_view(u: sqlite3.Row) -> dict[str, Any]:
        if u["role"] == "patient":
            return dict(patient_view(u, True), consentText=u["consent_text"])
        if u["role"] == "optometrist":
            return optometrist_view(u, private=True)
        return {"id": u["id"], "role": "admin", "name": u["name"], "email": u["email"]}

    def consultation_view(con: sqlite3.Connection, c: sqlite3.Row, viewer: sqlite3.Row, detail: bool = False) -> dict[str, Any]:
        patient = con.execute("SELECT * FROM tc_users WHERE id = ?", (c["patient_id"],)).fetchone()
        opt = con.execute("SELECT * FROM tc_users WHERE id = ?", (c["optometrist_id"],)).fetchone() if c["optometrist_id"] else None
        mine = viewer["role"] == "patient" or viewer["id"] == c["optometrist_id"]
        report = json.loads(c["report"])
        last = con.execute("SELECT MAX(id) AS id, COUNT(*) AS n FROM tc_messages WHERE consultation_id = ?", (c["id"],)).fetchone()
        has_rx = con.execute("SELECT 1 FROM tc_prescriptions WHERE consultation_id = ?", (c["id"],)).fetchone() is not None
        out: dict[str, Any] = {
            "id": c["id"], "caseId": case_id(c["id"]), "status": c["status"], "priority": c["priority"],
            "createdAt": c["created_at"], "updatedAt": c["updated_at"], "acceptedAt": c["accepted_at"],
            "startedAt": c["started_at"], "completedAt": c["completed_at"], "closedAt": c["closed_at"],
            "fee": c["fee"], "paymentStatus": c["payment_status"], "videoUrl": c["video_url"] if mine else None,
            "requestedOptometristId": c["requested_optometrist_id"],
            # the phone number is shown only to the optometrist who accepted the case
            "patient": patient_view(patient, with_phone=viewer["role"] == "optometrist" and viewer["id"] == c["optometrist_id"]) if patient else None,
            "optometrist": optometrist_view(opt) if opt else None,
            "hasPrescription": has_rx, "messageCount": last["n"], "lastMessageId": last["id"] or 0,
            "redFlagReasons": (report.get("triage") or {}).get("redFlagReasons") or [],
            "urgentNote": URGENT_NOTE if c["priority"] == "URGENT" else None,
        }
        if detail:
            out["report"] = report
            lang = (patient["language"] if patient else None) or "EN"
            if opt and patient and viewer["id"] == c["optometrist_id"]:
                out["whatsappUrl"] = whatsapp_url(patient["phone"], greeting(patient["name"], c["id"], opt["name"], lang, True))
            elif opt and patient and viewer["role"] == "patient" and opt["allow_whatsapp"]:
                out["whatsappUrl"] = whatsapp_url(opt["phone"], greeting(opt["name"], c["id"], patient["name"], lang, False))
            else:
                out["whatsappUrl"] = None
        return out

    def whatsapp_url(phone: str, text: str) -> str:
        from urllib.parse import quote
        return f"https://wa.me/{wa_number(phone)}?text={quote(text)}"

    def load_case(con: sqlite3.Connection, cid: str, user: sqlite3.Row, assigned_only: bool = False) -> sqlite3.Row:
        """The consultation if [user] may see it: its patient, the optometrist who has it, or (while it is still
        REQUESTED and not addressed to someone else) any approved optometrist. Everyone else gets 404."""
        c = con.execute("SELECT * FROM tc_consultations WHERE id = ?", (cid,)).fetchone()
        if c is None:
            raise HTTPException(status_code=404, detail="consultation not found")
        if user["role"] == "patient" and c["patient_id"] == user["id"]:
            return c
        if user["role"] == "optometrist" and user["status"] == "APPROVED":
            if c["optometrist_id"] == user["id"]:
                return c
            open_to_me = c["status"] == "REQUESTED" and c["requested_optometrist_id"] in (None, user["id"])
            if open_to_me and not assigned_only:
                return c
            if open_to_me:
                raise HTTPException(status_code=403, detail="accept the case first")
        raise HTTPException(status_code=404, detail="consultation not found")

    def touch(con: sqlite3.Connection, cid: str, **cols: Any) -> None:
        cols["updated_at"] = now_ms()
        con.execute(f"UPDATE tc_consultations SET {', '.join(k + ' = ?' for k in cols)} WHERE id = ?", (*cols.values(), cid))

    def add_message(con: sqlite3.Connection, cid: str, sender: Optional[sqlite3.Row], kind: str, text: Optional[str],
                    image: Optional[bytes] = None, mime: Optional[str] = None) -> int:
        cur = con.execute(
            "INSERT INTO tc_messages (consultation_id, sender_id, sender_role, kind, text, image, image_mime, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (cid, sender["id"] if sender else None, sender["role"] if sender else "system", kind, text, image, mime, now_ms()))
        return int(cur.lastrowid)

    def message_view(m: sqlite3.Row) -> dict[str, Any]:
        return {"id": m["id"], "senderRole": m["sender_role"], "senderId": m["sender_id"], "kind": m["kind"], "text": m["text"],
                "hasImage": m["image"] is not None, "createdAt": m["created_at"]}

    # ---- accounts ----
    @r.post("/auth/register/patient", status_code=201)
    def register_patient(body: PatientRegister) -> dict[str, Any]:
        if not body.consent:
            raise HTTPException(status_code=422, detail="consent is required: " + CONSENT_TEXT)
        phone = norm_phone(body.phone)
        uid = uuid.uuid4().hex
        t = now_ms()
        with db() as con:
            if con.execute("SELECT 1 FROM tc_users WHERE role = 'patient' AND phone = ?", (phone,)).fetchone():
                raise HTTPException(status_code=409, detail="this phone number is already registered")
            con.execute(
                "INSERT INTO tc_users (id, role, phone, password_hash, name, created_at, age, gender, language, consent_at, consent_text) "
                "VALUES (?, 'patient', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (uid, phone, hash_password(body.password), body.name, t, body.age, body.gender, body.language.upper(), t, CONSENT_TEXT))
            user = con.execute("SELECT * FROM tc_users WHERE id = ?", (uid,)).fetchone()
        return {"token": make_token(user), "user": me_view(user)}

    @r.post("/auth/register/optometrist", status_code=201)
    def register_optometrist(body: OptometristRegister) -> dict[str, Any]:
        phone = norm_phone(body.phone)
        email = body.email.lower()
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise HTTPException(status_code=422, detail="email is not valid")
        if body.photo and not body.photo.startswith("data:image/"):
            raise HTTPException(status_code=422, detail="photo must be an image")
        uid = uuid.uuid4().hex
        with db() as con:
            if con.execute("SELECT 1 FROM tc_users WHERE role = 'optometrist' AND (phone = ? OR email = ?)", (phone, email)).fetchone():
                raise HTTPException(status_code=409, detail="this phone number or email is already registered")
            con.execute(
                "INSERT INTO tc_users (id, role, phone, email, password_hash, name, created_at, qualification, registration_no, clinic_name, "
                "city, languages, fee, available, allow_whatsapp, photo, status) VALUES (?, 'optometrist', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'PENDING')",
                (uid, phone, email, hash_password(body.password), body.name, now_ms(), body.qualification, body.registrationNo,
                 body.clinicName, body.city, json.dumps(body.languages), body.fee, int(body.allowWhatsapp), body.photo))
            user = con.execute("SELECT * FROM tc_users WHERE id = ?", (uid,)).fetchone()
        return {"token": make_token(user), "user": me_view(user)}

    @r.post("/auth/login")
    def login(body: Login) -> dict[str, Any]:
        if body.role not in ROLES:
            raise HTTPException(status_code=422, detail="unknown role")
        ident = body.identifier.strip().lower()
        with db() as con:
            if "@" in ident:
                user = con.execute("SELECT * FROM tc_users WHERE role = ? AND email = ?", (body.role, ident)).fetchone()
            else:
                try:
                    phone = norm_phone(ident)
                except HTTPException:
                    phone = ""
                user = con.execute("SELECT * FROM tc_users WHERE role = ? AND phone = ?", (body.role, phone)).fetchone()
        # the same answer for an unknown account and a wrong password
        if user is None or not check_password(body.password, user["password_hash"]):
            raise HTTPException(status_code=401, detail="wrong phone / email or password")
        return {"token": make_token(user), "user": me_view(user)}

    @r.get("/me")
    def me(user: sqlite3.Row = Depends(current_user)) -> dict[str, Any]:
        return me_view(user)

    @r.patch("/me")
    def update_me(body: ProfileUpdate, user: sqlite3.Row = Depends(need("patient", "optometrist"))) -> dict[str, Any]:
        data = body.model_dump(exclude_unset=True)
        allowed = {"patient": {"name": "name", "age": "age", "gender": "gender", "language": "language"},
                   "optometrist": {"name": "name", "qualification": "qualification", "clinicName": "clinic_name", "city": "city",
                                   "languages": "languages", "fee": "fee", "available": "available",
                                   "allowWhatsapp": "allow_whatsapp", "photo": "photo"}}[user["role"]]
        cols: dict[str, Any] = {}
        for k, v in data.items():
            if k not in allowed:
                raise HTTPException(status_code=422, detail=f"{k} cannot be changed for a {user['role']}")
            if k == "photo" and v and not v.startswith("data:image/"):
                raise HTTPException(status_code=422, detail="photo must be an image")
            cols[allowed[k]] = json.dumps(v) if k == "languages" else int(v) if isinstance(v, bool) else v
        if cols.get("available") and user["status"] != "APPROVED":
            raise HTTPException(status_code=403, detail="your account is waiting for admin approval")
        with db() as con:
            if cols:
                con.execute(f"UPDATE tc_users SET {', '.join(k + ' = ?' for k in cols)} WHERE id = ?", (*cols.values(), user["id"]))
            return me_view(con.execute("SELECT * FROM tc_users WHERE id = ?", (user["id"],)).fetchone())

    @r.delete("/me", status_code=204)
    def delete_me(user: sqlite3.Row = Depends(need("patient"))) -> Response:
        """The patient's account and everything tied to it: consultations, their reports, messages and prescriptions."""
        with db() as con:
            con.execute("DELETE FROM tc_users WHERE id = ?", (user["id"],))   # consultations, messages and prescriptions cascade
        return Response(status_code=204)

    # ---- directory and admin ----
    @r.get("/optometrists")
    def optometrists(user: sqlite3.Row = Depends(need("patient", "admin"))) -> list[dict[str, Any]]:
        """Approved optometrists who are available now (what a patient can choose from)."""
        with db() as con:
            rows = con.execute("SELECT * FROM tc_users WHERE role = 'optometrist' AND status = 'APPROVED' AND available = 1 ORDER BY name").fetchall()
        return [optometrist_view(u) for u in rows]

    @r.get("/admin/optometrists")
    def admin_list(status: Optional[str] = None, user: sqlite3.Row = Depends(need("admin"))) -> list[dict[str, Any]]:
        with db() as con:
            rows = con.execute("SELECT * FROM tc_users WHERE role = 'optometrist' ORDER BY created_at DESC").fetchall()
        return [optometrist_view(u, private=True) for u in rows if not status or u["status"] == status.upper()]

    def set_status(oid: str, status: str) -> dict[str, Any]:
        with db() as con:
            u = con.execute("SELECT * FROM tc_users WHERE id = ? AND role = 'optometrist'", (oid,)).fetchone()
            if u is None:
                raise HTTPException(status_code=404, detail="optometrist not found")
            # a rejected optometrist is no longer listed as available
            con.execute("UPDATE tc_users SET status = ?, available = ? WHERE id = ?", (status, int(status == "APPROVED"), oid))
            return optometrist_view(con.execute("SELECT * FROM tc_users WHERE id = ?", (oid,)).fetchone(), private=True)

    @r.post("/admin/optometrists/{oid}/approve")
    def approve(oid: str, user: sqlite3.Row = Depends(need("admin"))) -> dict[str, Any]:
        return set_status(oid, "APPROVED")

    @r.post("/admin/optometrists/{oid}/reject")
    def reject(oid: str, user: sqlite3.Row = Depends(need("admin"))) -> dict[str, Any]:
        return set_status(oid, "REJECTED")

    @r.get("/admin/summary")
    def admin_summary(user: sqlite3.Row = Depends(need("admin"))) -> dict[str, Any]:
        with db() as con:
            by_status = {s: 0 for s in STATUSES}
            for row in con.execute("SELECT status, COUNT(*) AS n FROM tc_consultations GROUP BY status").fetchall():
                by_status[row["status"]] = row["n"]
            patients = con.execute("SELECT COUNT(*) AS n FROM tc_users WHERE role = 'patient'").fetchone()["n"]
        return {"consultations": by_status, "patients": patients}

    @r.get("/lens-catalog")
    def lens_catalog_list(user: sqlite3.Row = Depends(need("optometrist", "admin", approved=True))) -> list[dict[str, str]]:
        return lens_options(Path(lens_catalog) if lens_catalog else None)

    # ---- consultations ----
    @r.post("/consultations", status_code=201)
    async def create_consultation(request: Request, user: sqlite3.Row = Depends(need("patient"))) -> dict[str, Any]:
        raw = await request.body()
        if len(raw) > MAX_REPORT_BYTES:
            raise HTTPException(status_code=413, detail="report too large")
        try:
            body = ConsultationIn.model_validate_json(raw)
        except ValueError as e:
            raise HTTPException(status_code=422, detail=str(e)[:500])
        if not user["consent_at"]:
            raise HTTPException(status_code=403, detail="consent is required: " + CONSENT_TEXT)
        problems = report_problems(body.report)
        if problems:
            raise HTTPException(status_code=422, detail={"privacy": problems})
        cid = uuid.uuid4().hex
        t = now_ms()
        with db() as con:
            fee = None
            if body.optometristId:
                opt = con.execute("SELECT * FROM tc_users WHERE id = ? AND role = 'optometrist' AND status = 'APPROVED'", (body.optometristId,)).fetchone()
                if opt is None:
                    raise HTTPException(status_code=404, detail="optometrist not found")
                fee = opt["fee"]
            con.execute(
                "INSERT INTO tc_consultations (id, patient_id, requested_optometrist_id, report, priority, status, fee, payment_status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?)",
                (cid, user["id"], body.optometristId, json.dumps(body.report), priority_of(body.report), fee, PAYMENT_DEMO, t, t))
            return consultation_view(con, con.execute("SELECT * FROM tc_consultations WHERE id = ?", (cid,)).fetchone(), user, detail=True)

    @r.get("/consultations")
    def list_consultations(user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> list[dict[str, Any]]:
        """A patient's own consultations; for an approved optometrist the open queue and their own cases.
        URGENT first, then CHILD_MYOPIA, then ROUTINE; the newest first inside each group."""
        with db() as con:
            if user["role"] == "patient":
                rows = con.execute("SELECT * FROM tc_consultations WHERE patient_id = ?", (user["id"],)).fetchall()
            else:
                rows = con.execute(
                    "SELECT * FROM tc_consultations WHERE optometrist_id = ? OR (status = 'REQUESTED' AND (requested_optometrist_id IS NULL OR requested_optometrist_id = ?))",
                    (user["id"], user["id"])).fetchall()
            out = [consultation_view(con, c, user) for c in rows]
        if user["role"] == "patient":
            out.sort(key=lambda x: -x["createdAt"])
        else:
            out.sort(key=lambda x: (PRIORITIES.index(x["priority"]), -x["createdAt"]))
        return out

    @r.get("/consultations/{cid}")
    def get_consultation(cid: str, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> dict[str, Any]:
        with db() as con:
            return consultation_view(con, load_case(con, cid, user), user, detail=True)

    @r.post("/consultations/{cid}/accept")
    def accept(cid: str, user: sqlite3.Row = Depends(need("optometrist", approved=True))) -> dict[str, Any]:
        with db() as con:
            c = load_case(con, cid, user)
            if c["status"] != "REQUESTED":
                if c["optometrist_id"] == user["id"]:
                    return consultation_view(con, c, user, detail=True)
                raise HTTPException(status_code=409, detail="another optometrist already accepted this case")
            t = now_ms()
            done = con.execute("UPDATE tc_consultations SET optometrist_id = ?, status = 'ACCEPTED', accepted_at = ?, updated_at = ?, fee = ? "
                               "WHERE id = ? AND status = 'REQUESTED'", (user["id"], t, t, user["fee"], cid)).rowcount
            if not done:
                raise HTTPException(status_code=409, detail="another optometrist already accepted this case")
            add_message(con, cid, None, "SYSTEM", f"{user['name']} accepted the consultation.")
            return consultation_view(con, con.execute("SELECT * FROM tc_consultations WHERE id = ?", (cid,)).fetchone(), user, detail=True)

    @r.post("/consultations/{cid}/close")
    def close(cid: str, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> dict[str, Any]:
        with db() as con:
            c = load_case(con, cid, user, assigned_only=True)
            if c["status"] != "CLOSED":
                touch(con, cid, status="CLOSED", closed_at=now_ms())
                add_message(con, cid, None, "SYSTEM", "The consultation was closed.")
            return consultation_view(con, con.execute("SELECT * FROM tc_consultations WHERE id = ?", (cid,)).fetchone(), user, detail=True)

    def start_if_accepted(con: sqlite3.Connection, c: sqlite3.Row) -> None:
        if c["status"] == "ACCEPTED":
            touch(con, c["id"], status="IN_PROGRESS", started_at=now_ms())

    # ---- chat ----
    @r.get("/consultations/{cid}/messages")
    def messages(cid: str, after: int = 0, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> list[dict[str, Any]]:
        with db() as con:
            load_case(con, cid, user, assigned_only=True)
            rows = con.execute("SELECT * FROM tc_messages WHERE consultation_id = ? AND id > ? ORDER BY id", (cid, after)).fetchall()
        return [message_view(m) for m in rows]

    @r.post("/consultations/{cid}/messages", status_code=201)
    def post_message(cid: str, body: MessageIn, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> dict[str, Any]:
        text = (body.text or "").strip() or None
        image = None
        if body.imageBase64:
            if body.imageMime not in IMAGE_MIMES:
                raise HTTPException(status_code=422, detail="image must be JPEG, PNG or WebP")
            try:
                image = base64.b64decode(body.imageBase64, validate=True)
            except ValueError:
                raise HTTPException(status_code=422, detail="image is not valid base64")
            if len(image) > MAX_IMAGE_BYTES:
                raise HTTPException(status_code=413, detail="image too large")
        if not text and image is None:
            raise HTTPException(status_code=422, detail="empty message")
        with db() as con:
            c = load_case(con, cid, user, assigned_only=True)
            if c["status"] == "CLOSED":
                raise HTTPException(status_code=409, detail="this consultation is closed")
            if user["role"] == "optometrist":
                start_if_accepted(con, c)
            mid = add_message(con, cid, user, "IMAGE" if image is not None else "TEXT", text, image, body.imageMime if image is not None else None)
            touch(con, cid)
            return message_view(con.execute("SELECT * FROM tc_messages WHERE id = ?", (mid,)).fetchone())

    @r.get("/consultations/{cid}/messages/{mid}/image")
    def message_image(cid: str, mid: int, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> Response:
        with db() as con:
            load_case(con, cid, user, assigned_only=True)
            m = con.execute("SELECT image, image_mime FROM tc_messages WHERE id = ? AND consultation_id = ?", (mid, cid)).fetchone()
        if m is None or m["image"] is None:
            raise HTTPException(status_code=404, detail="image not found")
        return Response(content=bytes(m["image"]), media_type=m["image_mime"])

    @r.post("/consultations/{cid}/video")
    def video(cid: str, user: sqlite3.Row = Depends(need("optometrist", approved=True))) -> dict[str, Any]:
        """A Jitsi Meet room for this consultation; the link is posted into the chat (the same room is reused)."""
        with db() as con:
            c = load_case(con, cid, user, assigned_only=True)
            if c["status"] in ("CLOSED", "REQUESTED"):
                raise HTTPException(status_code=409, detail="accept the case first" if c["status"] == "REQUESTED" else "this consultation is closed")
            url = c["video_url"] or jitsi_room()
            start_if_accepted(con, c)
            if not c["video_url"]:
                touch(con, cid, video_url=url)
            add_message(con, cid, user, "VIDEO", url)
            return {"url": url}

    # ---- advice / e-prescription ----
    def rx_view(p: sqlite3.Row) -> dict[str, Any]:
        return dict(json.loads(p["data"]), optometristName=p["optometrist_name"], registrationNo=p["registration_no"],
                    qualification=p["qualification"], clinic=p["clinic"], createdAt=p["created_at"])

    @r.post("/consultations/{cid}/prescription")
    def write_prescription(cid: str, body: PrescriptionIn, user: sqlite3.Row = Depends(need("optometrist", approved=True))) -> dict[str, Any]:
        with db() as con:
            c = load_case(con, cid, user, assigned_only=True)
            if c["status"] in ("REQUESTED", "CLOSED"):
                raise HTTPException(status_code=409, detail="accept the case first" if c["status"] == "REQUESTED" else "this consultation is closed")
            for eye in (body.right, body.left):
                if eye.cyl not in (None, 0) and eye.axis is None:
                    raise HTTPException(status_code=422, detail="a cylinder needs an axis")
            data = json.loads(body.model_dump_json())
            t = now_ms()
            clinic = ", ".join(x for x in (user["clinic_name"], user["city"]) if x)
            con.execute(
                "INSERT INTO tc_prescriptions (consultation_id, data, optometrist_name, registration_no, qualification, clinic, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(consultation_id) DO UPDATE SET data = excluded.data, optometrist_name = excluded.optometrist_name, "
                "registration_no = excluded.registration_no, qualification = excluded.qualification, clinic = excluded.clinic, created_at = excluded.created_at",
                (cid, json.dumps(data), user["name"], user["registration_no"], user["qualification"], clinic, t))
            if c["status"] != "COMPLETED":
                touch(con, cid, status="COMPLETED", completed_at=t, started_at=c["started_at"] or t)
            else:
                touch(con, cid)
            add_message(con, cid, None, "PRESCRIPTION", "The optometrist sent the advice / e-prescription.")
            return rx_view(con.execute("SELECT * FROM tc_prescriptions WHERE consultation_id = ?", (cid,)).fetchone())

    def load_rx(con: sqlite3.Connection, cid: str, user: sqlite3.Row) -> tuple[sqlite3.Row, sqlite3.Row]:
        c = load_case(con, cid, user, assigned_only=True)
        p = con.execute("SELECT * FROM tc_prescriptions WHERE consultation_id = ?", (cid,)).fetchone()
        if p is None:
            raise HTTPException(status_code=404, detail="no prescription yet")
        return c, p

    @r.get("/consultations/{cid}/prescription")
    def read_prescription(cid: str, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> dict[str, Any]:
        with db() as con:
            return rx_view(load_rx(con, cid, user)[1])

    @r.get("/consultations/{cid}/prescription.pdf")
    def prescription_file(cid: str, user: sqlite3.Row = Depends(need("patient", "optometrist", approved=True))) -> Response:
        with db() as con:
            c, p = load_rx(con, cid, user)
            patient = con.execute("SELECT name, age FROM tc_users WHERE id = ?", (c["patient_id"],)).fetchone()
        meta = {"caseId": case_id(cid), "date": time.strftime("%d %b %Y", time.localtime(p["created_at"] / 1000)),
                "optometristName": p["optometrist_name"], "registrationNo": p["registration_no"], "qualification": p["qualification"],
                "clinic": p["clinic"], "patientName": patient["name"], "patientAge": patient["age"]}
        return Response(content=prescription_pdf(json.loads(p["data"]), meta), media_type="application/pdf",
                        headers={"Content-Disposition": f'inline; filename="drishti-advice-{case_id(cid)}.pdf"'})

    return r
