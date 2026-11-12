"""Remote Optometrist Bridge: receives consented, anonymous pre-consultation profiles from the Drishti Saathi app
and lets an optometrist reply with advice and an "needs in-person exam" flag.

Storage is one SQLite file. Profiles carry numbers and answers only: the API rejects anything that looks like a name,
photo or contact detail, and any profile without the customer's spoken and tapped consent.
"""
from __future__ import annotations

import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from . import anamnesis
from .sarvam import SarvamClient

try:  # backend/.env holds SARVAM_API_KEY (never committed); real environment variables win
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parent.parent / ".env", override=False)
except ImportError:  # pragma: no cover
    pass

STATIC = Path(__file__).parent / "static"
MAX_BODY_BYTES = 256 * 1024

# Keys that must never appear anywhere in a profile (matched case-insensitively, ignoring _ and -).
FORBIDDEN_KEYS = {
    "name", "firstname", "lastname", "fullname", "customername", "photo", "photos", "image", "images", "picture",
    "selfie", "face_image", "faceimage", "frame_image", "jpeg", "png", "base64", "phone", "phonenumber", "mobile",
    "email", "address", "aadhaar", "aadhar",
}
# Long strings are rejected so no image or document can be smuggled in as text.
MAX_STRING = 2000


class Consent(BaseModel):
    model_config = ConfigDict(extra="forbid")
    consentAt: int = Field(gt=0, description="Epoch ms when the customer agreed")
    spoken: bool = Field(description="Consent text was read aloud and the customer said yes")
    tapped: bool = Field(description="The customer tapped 'I agree, send'")
    textVersion: str = Field(min_length=1, max_length=40)


class ProfileIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    consent: Consent
    app: dict[str, Any] = Field(default_factory=dict)
    demo: bool = False
    answers: dict[str, Any]
    functionalProfile: Optional[dict[str, Any]] = None
    powerSource: Optional[str] = Field(default=None, max_length=40)
    power: dict[str, Any] = Field(default_factory=dict)
    measurements: dict[str, Any] = Field(default_factory=dict)
    estimates: dict[str, Any] = Field(default_factory=dict)


class ReviewIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    advice: str = Field(min_length=1, max_length=4000)
    needsInPersonExam: bool


def _norm(k: str) -> str:
    return k.lower().replace("-", "").replace(" ", "")


def privacy_problems(value: Any, path: str = "") -> list[str]:
    """Paths of forbidden keys or over-long strings anywhere in [value]."""
    out: list[str] = []
    if isinstance(value, dict):
        for k, v in value.items():
            p = f"{path}.{k}" if path else str(k)
            if _norm(str(k)) in FORBIDDEN_KEYS or _norm(str(k)).replace("_", "") in FORBIDDEN_KEYS:
                out.append(p)
            out += privacy_problems(v, p)
    elif isinstance(value, list):
        for i, v in enumerate(value):
            out += privacy_problems(v, f"{path}[{i}]")
    elif isinstance(value, str) and len(value) > MAX_STRING:
        out.append(f"{path} (string longer than {MAX_STRING})")
    return out


SCHEMA = """
CREATE TABLE IF NOT EXISTS profiles (
    id TEXT PRIMARY KEY,
    received_at INTEGER NOT NULL,
    payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS reviews (
    profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    advice TEXT NOT NULL,
    needs_in_person INTEGER NOT NULL,
    reviewed_at INTEGER NOT NULL
);
"""


def create_app(db_path: Optional[str] = None, token: Optional[str] = None,
               sarvam: Optional[SarvamClient] = None) -> FastAPI:
    db_file = db_path or os.environ.get("BRIDGE_DB", str(Path(__file__).resolve().parent.parent / "bridge.db"))
    api_token = token if token is not None else os.environ.get("BRIDGE_TOKEN") or None

    @contextmanager
    def db() -> Iterator[sqlite3.Connection]:
        con = sqlite3.connect(db_file)
        con.row_factory = sqlite3.Row
        con.execute("PRAGMA foreign_keys = ON")
        try:
            yield con
            con.commit()
        finally:
            con.close()

    with db() as con:
        con.executescript(SCHEMA)

    app = FastAPI(title="Drishti Saathi Remote Optometrist Bridge", version="1.1")
    voice = anamnesis.AnamnesisService(sarvam or SarvamClient())

    def check_token(x_bridge_token: Optional[str] = Header(default=None)) -> None:
        if api_token and not (x_bridge_token and secrets.compare_digest(x_bridge_token, api_token)):
            raise HTTPException(status_code=401, detail="missing or wrong X-Bridge-Token")

    def now_ms() -> int:
        return int(time.time() * 1000)

    def review_of(con: sqlite3.Connection, pid: str) -> dict[str, Any]:
        r = con.execute("SELECT * FROM reviews WHERE profile_id = ?", (pid,)).fetchone()
        if r is None:
            return {"status": "WAITING"}
        return {"status": "REVIEWED", "advice": r["advice"], "needsInPersonExam": bool(r["needs_in_person"]),
                "reviewedAt": r["reviewed_at"]}

    def get_row(con: sqlite3.Connection, pid: str) -> sqlite3.Row:
        row = con.execute("SELECT * FROM profiles WHERE id = ?", (pid,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="profile not found")
        return row

    @app.get("/", include_in_schema=False)
    def page() -> FileResponse:
        return FileResponse(STATIC / "index.html")

    @app.get("/health")
    def health(x_bridge_token: Optional[str] = Header(default=None)) -> dict[str, Any]:
        """Lets the app test its settings: tokenOk is true when no token is needed or the given one is right."""
        ok = not api_token or bool(x_bridge_token and secrets.compare_digest(x_bridge_token, api_token))
        return {"ok": True, "tokenRequired": bool(api_token), "tokenOk": ok, "voiceAi": voice.sarvam.configured}

    @app.post("/profiles", status_code=201, dependencies=[Depends(check_token)])
    async def create_profile(request: Request) -> dict[str, Any]:
        body = await request.body()
        if len(body) > MAX_BODY_BYTES:
            raise HTTPException(status_code=413, detail="profile too large")
        try:
            raw = json.loads(body)
        except json.JSONDecodeError:
            raise HTTPException(status_code=422, detail="body is not JSON")
        problems = privacy_problems(raw)
        if problems:
            raise HTTPException(status_code=422, detail={"privacy": problems})
        try:
            profile = ProfileIn.model_validate(raw)
        except ValidationError as e:
            raise HTTPException(status_code=422, detail=e.errors(include_url=False, include_input=False))
        if not (profile.consent.spoken and profile.consent.tapped):
            raise HTTPException(status_code=422, detail="spoken and tapped consent are both required")
        pid = uuid.uuid4().hex
        received = now_ms()
        with db() as con:
            con.execute("INSERT INTO profiles (id, received_at, payload) VALUES (?, ?, ?)",
                        (pid, received, profile.model_dump_json()))
        return {"id": pid, "status": "WAITING", "receivedAt": received}

    @app.get("/profiles", dependencies=[Depends(check_token)])
    def list_profiles() -> list[dict[str, Any]]:
        with db() as con:
            rows = con.execute("SELECT * FROM profiles ORDER BY received_at DESC").fetchall()
            out = []
            for r in rows:
                p = json.loads(r["payload"])
                a = p.get("answers", {})
                rv = review_of(con, r["id"])
                out.append({
                    "id": r["id"], "receivedAt": r["received_at"], "status": rv["status"], "demo": p.get("demo", False),
                    "age": a.get("age"), "visionProblem": a.get("visionProblem"), "powerSource": p.get("powerSource"),
                    "referralLevel": p.get("estimates", {}).get("referralLevel"),
                    "needsInPersonExam": rv.get("needsInPersonExam"),
                })
            return out

    @app.get("/profiles/{pid}", dependencies=[Depends(check_token)])
    def get_profile(pid: str) -> dict[str, Any]:
        with db() as con:
            r = get_row(con, pid)
            return {"id": pid, "receivedAt": r["received_at"], "profile": json.loads(r["payload"]),
                    "review": review_of(con, pid)}

    @app.delete("/profiles/{pid}", status_code=204, dependencies=[Depends(check_token)])
    def delete_profile(pid: str) -> Response:
        """The shop deleted the session: the profile and its review go too."""
        with db() as con:
            get_row(con, pid)
            con.execute("DELETE FROM profiles WHERE id = ?", (pid,))
        return Response(status_code=204)

    @app.post("/profiles/{pid}/review", dependencies=[Depends(check_token)])
    def post_review(pid: str, review: ReviewIn) -> dict[str, Any]:
        with db() as con:
            get_row(con, pid)
            con.execute(
                "INSERT INTO reviews (profile_id, advice, needs_in_person, reviewed_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(profile_id) DO UPDATE SET advice = excluded.advice, "
                "needs_in_person = excluded.needs_in_person, reviewed_at = excluded.reviewed_at",
                (pid, review.advice.strip(), int(review.needsInPersonExam), now_ms()),
            )
            return review_of(con, pid)

    @app.get("/profiles/{pid}/review", dependencies=[Depends(check_token)])
    def get_review(pid: str) -> dict[str, Any]:
        with db() as con:
            get_row(con, pid)
            return review_of(con, pid)

    app.include_router(anamnesis.router(voice, check_token))
    return app


app = create_app()
