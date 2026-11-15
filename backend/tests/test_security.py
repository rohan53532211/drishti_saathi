"""Security pass before submission: tokens, roles, reading other people's cases, hostile text, brute force, deletion,
and the urgent note. The helpers come from the teleconsult tests."""
import base64
import json
import re
import sqlite3
import time
from pathlib import Path

import jwt
import pytest

from app import teleconsult
from tests.test_teleconsult import (ADMIN, OPTOM, PATIENT, REPORT, auth, client, db_path, login, new_optometrist,  # noqa: F401
                                    new_patient, request_case)

SECRET = "test-secret-key-that-is-long-enough-for-hs256"
PORTAL = Path(teleconsult.__file__).parent / "static" / "portal.html"

# every endpoint that needs a login (method, path); {cid} = a real consultation
PROTECTED = [
    ("GET", "/api/me"), ("PATCH", "/api/me"), ("DELETE", "/api/me"), ("GET", "/api/optometrists"),
    ("GET", "/api/admin/optometrists"), ("GET", "/api/admin/summary"), ("POST", "/api/admin/optometrists/x/approve"),
    ("POST", "/api/admin/optometrists/x/reject"), ("GET", "/api/lens-catalog"), ("POST", "/api/consultations"),
    ("GET", "/api/consultations"), ("GET", "/api/consultations/{cid}"), ("POST", "/api/consultations/{cid}/accept"),
    ("POST", "/api/consultations/{cid}/close"), ("GET", "/api/consultations/{cid}/messages"),
    ("POST", "/api/consultations/{cid}/messages"), ("GET", "/api/consultations/{cid}/messages/1/image"),
    ("POST", "/api/consultations/{cid}/video"), ("POST", "/api/consultations/{cid}/prescription"),
    ("GET", "/api/consultations/{cid}/prescription"), ("GET", "/api/consultations/{cid}/prescription.pdf"),
]


def b64(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def full_case(client):
    """A patient, an approved optometrist, an accepted case with a chat message, a picture and a prescription."""
    patient, p = new_patient(client)
    optom, o = new_optometrist(client)
    cid = request_case(client, patient)["id"]
    assert client.post(f"/api/consultations/{cid}/accept", headers=optom).status_code == 200
    assert client.post(f"/api/consultations/{cid}/messages", json={"text": "hello"}, headers=optom).status_code == 201
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 64).decode()
    pic = client.post(f"/api/consultations/{cid}/messages", json={"imageBase64": png, "imageMime": "image/png"}, headers=optom)
    assert pic.status_code == 201, pic.text
    assert client.post(f"/api/consultations/{cid}/prescription", json={"right": {"sph": -1.0}}, headers=optom).status_code == 200
    return patient, p, optom, o, cid, pic.json()["id"]


# ---- auth ----

def test_no_token_bad_token_expired_tampered_and_alg_none_are_all_401(client):
    patient, p, _, _, cid, _ = full_case(client)
    good = patient["Authorization"][7:]
    head, body, sig = good.split(".")
    claims = {"sub": p["id"], "role": "patient", "exp": int(time.time()) + 3600}
    admin_claims = dict(claims, role="admin")
    bad_tokens = {
        "none": None,
        "empty": "",
        "garbage": "not-a-token",
        "wrong scheme": "Basic " + good,
        "expired": jwt.encode(dict(claims, exp=int(time.time()) - 10), SECRET, algorithm="HS256"),
        "other key": jwt.encode(claims, "another-secret-key-that-is-long-enough-too", algorithm="HS256"),
        "tampered payload": f"{head}.{b64(admin_claims)}.{sig}",
        "tampered signature": f"{head}.{body}.{sig[:-2]}{'AA' if not sig.endswith('AA') else 'BB'}",
        "alg none": f"{b64({'alg': 'none', 'typ': 'JWT'})}.{b64(claims)}.",
        "alg None, signature kept": f"{b64({'alg': 'None', 'typ': 'JWT'})}.{b64(claims)}.{sig}",
        "role raised with the right key but no such user": jwt.encode(admin_claims, SECRET, algorithm="HS256"),
        "no expiry, unknown user": jwt.encode({"sub": "nobody", "role": "patient"}, SECRET, algorithm="HS256"),
    }
    for name, token in bad_tokens.items():
        headers = {} if token is None else {"Authorization": token if token.startswith("Basic ") else f"Bearer {token}"}
        for method, path in PROTECTED:
            r = client.request(method, path.replace("{cid}", cid), headers=headers, json={} if method in ("POST", "PATCH") else None)
            assert r.status_code == 401, f"{name}: {method} {path} -> {r.status_code}"
    # the real token still works
    assert client.get("/api/me", headers=patient).status_code == 200


# ---- roles ----

def test_role_matrix_patient_pending_optometrist_and_optometrist_on_admin_endpoints(client):
    patient, _, optom, o, cid, _ = full_case(client)
    pending, _ = new_optometrist(client, approve=False, phone="9000011111", email="pending@test.example")
    admin_only = [("GET", "/api/admin/optometrists"), ("GET", "/api/admin/summary"),
                  ("POST", f"/api/admin/optometrists/{o['id']}/approve"), ("POST", f"/api/admin/optometrists/{o['id']}/reject")]
    optometrist_only = [("GET", "/api/lens-catalog"), ("POST", f"/api/consultations/{cid}/accept"),
                        ("POST", f"/api/consultations/{cid}/video"), ("POST", f"/api/consultations/{cid}/prescription")]
    for method, path in admin_only + optometrist_only:
        assert client.request(method, path, headers=patient, json={} if method == "POST" else None).status_code == 403, f"patient {method} {path}"
    for method, path in admin_only:
        assert client.request(method, path, headers=optom).status_code == 403, f"optometrist {method} {path}"
        assert client.request(method, path, headers=pending).status_code == 403, f"pending {method} {path}"
    # a PENDING optometrist sees no case, no queue, no catalogue, and cannot make themselves available
    for method, path in [("GET", "/api/consultations"), ("GET", f"/api/consultations/{cid}"), ("GET", f"/api/consultations/{cid}/messages"),
                         ("POST", f"/api/consultations/{cid}/accept"), ("GET", "/api/lens-catalog"),
                         ("GET", f"/api/consultations/{cid}/prescription.pdf")]:
        assert client.request(method, path, headers=pending).status_code == 403, f"pending {method} {path}"
    assert client.patch("/api/me", json={"available": True}, headers=pending).status_code == 403
    # nobody can give themselves a role or a status
    assert client.patch("/api/me", json={"role": "admin"}, headers=patient).status_code == 422
    assert client.patch("/api/me", json={"status": "APPROVED"}, headers=pending).status_code == 422
    assert client.post("/api/auth/register/patient", json={**PATIENT, "phone": "9000022222", "role": "admin"}).status_code == 422


def test_idor_other_optometrists_and_other_patients_cannot_read_a_case_its_chat_its_picture_or_its_pdf(client):
    patient, _, optom, _, cid, mid = full_case(client)
    other_optom, _ = new_optometrist(client, phone="9000033333", email="other@test.example")
    other_patient, _ = new_patient(client, name="Ravi", phone="9811111111")
    reads = [f"/api/consultations/{cid}", f"/api/consultations/{cid}/messages", f"/api/consultations/{cid}/messages/{mid}/image",
             f"/api/consultations/{cid}/prescription", f"/api/consultations/{cid}/prescription.pdf"]
    for who, headers in (("other optometrist", other_optom), ("other patient", other_patient)):
        for path in reads:
            r = client.get(path, headers=headers)
            assert r.status_code == 404, f"{who} GET {path} -> {r.status_code}"
        for path, body in [(f"/api/consultations/{cid}/messages", {"text": "let me in"}), (f"/api/consultations/{cid}/close", None),
                           (f"/api/consultations/{cid}/video", None), (f"/api/consultations/{cid}/prescription", {"right": {"sph": 9}}),
                           (f"/api/consultations/{cid}/accept", None)]:
            r = client.post(path, json=body, headers=headers)
            assert r.status_code in (403, 404, 409), f"{who} POST {path} -> {r.status_code}"
        assert cid not in [c["id"] for c in client.get("/api/consultations", headers=headers).json()]
    # the people on the case still read everything, and nothing was changed by the attempts
    for headers in (patient, optom):
        for path in reads:
            assert client.get(path, headers=headers).status_code == 200, path
    case = client.get(f"/api/consultations/{cid}", headers=patient).json()
    assert case["status"] == "COMPLETED" and case["videoUrl"] is None
    assert [m["text"] for m in client.get(f"/api/consultations/{cid}/messages", headers=patient).json() if m["kind"] == "TEXT"] == ["hello"]
    assert client.get(f"/api/consultations/{cid}/prescription", headers=patient).json()["right"]["sph"] == -1.0


# ---- injection ----

HOSTILE = [
    "Robert'); DROP TABLE tc_users;--",
    "' OR '1'='1",
    "<script>alert(1)</script>",
    "<img src=x onerror=alert(1)>",
    "\"><svg onload=alert(1)>",
    "javascript:alert(1)",
    "{{7*7}} ${7*7} %s %n",
]


def test_sql_and_script_payloads_are_stored_as_plain_text_and_break_nothing(client, db_path):
    optom, o = new_optometrist(client)
    for n, text in enumerate(HOSTILE):
        patient, p = new_patient(client, name=text, phone=f"98{n:08d}")
        assert p["name"] == text
        cid = request_case(client, patient)["id"]
        assert client.post(f"/api/consultations/{cid}/accept", headers=optom).status_code == 200
        assert client.post(f"/api/consultations/{cid}/messages", json={"text": text}, headers=patient).status_code == 201
        rx = client.post(f"/api/consultations/{cid}/prescription", headers=optom,
                         json={"right": {"sph": -1.0}, "lensAdvice": [text], "referralAdvice": text, "notes": text})
        assert rx.status_code == 200, rx.text
        seen = client.get(f"/api/consultations/{cid}", headers=optom)
        assert seen.headers["content-type"].startswith("application/json")       # never served as a page
        assert seen.json()["patient"]["name"] == text
        assert text in [m["text"] for m in client.get(f"/api/consultations/{cid}/messages", headers=optom).json() if m["kind"] == "TEXT"]
        got = client.get(f"/api/consultations/{cid}/prescription", headers=patient).json()
        assert got["notes"] == text and got["referralAdvice"] == text and got["lensAdvice"] == [text]
        pdf = client.get(f"/api/consultations/{cid}/prescription.pdf", headers=patient)
        assert pdf.status_code == 200 and pdf.content.startswith(b"%PDF")
        # a hostile login identifier is just a wrong login
        assert client.post("/api/auth/login", json={"role": "patient", "identifier": text, "password": text}).status_code in (401, 422)
        assert client.get(f"/api/consultations/{text}", headers=optom).status_code == 404
    con = sqlite3.connect(db_path)
    try:
        assert con.execute("SELECT COUNT(*) FROM tc_users WHERE role = 'patient'").fetchone()[0] == len(HOSTILE)
        assert con.execute("SELECT COUNT(*) FROM tc_consultations").fetchone()[0] == len(HOSTILE)
    finally:
        con.close()
    # a hostile video or WhatsApp link can never come from a user: the server builds both itself
    case = client.get(f"/api/consultations/{cid}", headers=optom).json()
    assert case["whatsappUrl"].startswith("https://wa.me/91") and "<" not in case["whatsappUrl"] and "'" not in case["whatsappUrl"]


def test_the_portal_page_writes_every_text_as_text_never_as_markup(client):
    page = PORTAL.read_text(encoding="utf-8")
    for sink in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "srcdoc"):
        assert sink not in page, f"portal.html uses {sink}"
    assert not re.search(r"\son\w+\s*=\s*[\"']", page), "inline event handler in portal.html"
    # elements are built with createElement / createTextNode; only http(s) text in a chat message becomes a link
    assert "document.createTextNode(String(kid))" in page
    assert r"/^https?:\/\//.test(part) ? h(\"a\"" .replace('\\"', '"') in page
    assert page.count('target: "_blank"') == page.count('target: "_blank", rel: "noopener"')
    served = client.get("/portal")
    assert served.status_code == 200 and served.headers["content-type"].startswith("text/html")


# ---- passwords and brute force ----

def test_passwords_are_bcrypt_hashes_and_never_leave_the_server(client, db_path):
    patient, p = new_patient(client)
    _, o = new_optometrist(client)
    con = sqlite3.connect(db_path)
    try:
        hashes = [r[0] for r in con.execute("SELECT password_hash FROM tc_users")]
    finally:
        con.close()
    assert len(hashes) == 3 and all(re.fullmatch(r"\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}", h) for h in hashes)
    assert len(set(hashes)) == 3                                                    # salted
    for secret in (PATIENT["password"], OPTOM["password"], ADMIN["password"]):
        assert all(secret not in h for h in hashes)
    for path, headers in (("/api/me", patient), ("/api/optometrists", patient), ("/api/admin/optometrists", login(client, ADMIN))):
        body = client.get(path, headers=headers).text
        assert "password" not in body.lower() and "$2b$" not in body and "$2a$" not in body


def test_ten_wrong_passwords_a_minute_lock_that_account_from_that_address_then_it_opens_again(client, monkeypatch):
    new_patient(client)
    new_patient(client, name="Ravi", phone="9811111111")
    wrong = {"role": "patient", "identifier": PATIENT["phone"], "password": "wrong-password"}
    right = {"role": "patient", "identifier": PATIENT["phone"], "password": PATIENT["password"]}
    for n in range(teleconsult.LOGIN_MAX_FAILURES):
        assert client.post("/api/auth/login", json=wrong).status_code == 401, n
    blocked = client.post("/api/auth/login", json=wrong)
    assert blocked.status_code == 429 and 0 < int(blocked.headers["retry-after"]) <= teleconsult.LOGIN_WINDOW_S + 1
    # the right password does not get through either while it is locked, however the number is typed
    assert client.post("/api/auth/login", json=right).status_code == 429
    assert client.post("/api/auth/login", json=dict(right, identifier="+91 98765-43210")).status_code == 429
    # other accounts are not affected
    assert client.post("/api/auth/login", json={"role": "patient", "identifier": "9811111111", "password": PATIENT["password"]}).status_code == 200
    assert client.post("/api/auth/login", json=ADMIN).status_code == 200
    # a minute later it opens again, and a good login clears the count
    start = time.monotonic()
    monkeypatch.setattr(teleconsult.time, "monotonic", lambda: start + teleconsult.LOGIN_WINDOW_S + 1)
    assert client.post("/api/auth/login", json=right).status_code == 200
    for n in range(teleconsult.LOGIN_MAX_FAILURES - 1):
        assert client.post("/api/auth/login", json=wrong).status_code == 401
    assert client.post("/api/auth/login", json=right).status_code == 200
    assert client.post("/api/auth/login", json=wrong).status_code == 401            # counted from zero again


# ---- deletion ----

def test_deleting_the_account_removes_the_report_the_chat_the_picture_and_the_pdf_for_everyone(client, db_path):
    patient, p, optom, _, cid, mid = full_case(client)
    assert client.get(f"/api/consultations/{cid}/prescription.pdf", headers=optom).status_code == 200
    assert client.delete("/api/me", headers=patient).status_code == 204
    for path in (f"/api/consultations/{cid}", f"/api/consultations/{cid}/messages", f"/api/consultations/{cid}/messages/{mid}/image",
                 f"/api/consultations/{cid}/prescription", f"/api/consultations/{cid}/prescription.pdf"):
        assert client.get(path, headers=optom).status_code == 404, path
        assert client.get(path, headers=patient).status_code == 401, path
    assert client.get("/api/consultations", headers=optom).json() == []
    con = sqlite3.connect(db_path)
    try:
        for table in ("tc_consultations", "tc_messages", "tc_prescriptions"):
            assert con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0, table
        assert con.execute("SELECT COUNT(*) FROM tc_users WHERE role = 'patient'").fetchone()[0] == 0
        dump = " ".join(str(v) for t in ("tc_users", "tc_consultations", "tc_messages", "tc_prescriptions") for row in con.execute(f"SELECT * FROM {t}") for v in row)
        assert PATIENT["name"] not in dump and "9876543210" not in dump
    finally:
        con.close()
    # only patients can delete themselves this way; the optometrist's account is untouched
    assert client.delete("/api/me", headers=optom).status_code == 403


# ---- urgent first ----

def test_an_urgent_case_carries_the_doctor_today_note_is_first_in_the_queue_and_the_portal_shows_it_first(client):
    optom, _ = new_optometrist(client)
    routine, _ = new_patient(client)
    urgent, _ = new_patient(client, name="Ravi", phone="9811111111")
    request_case(client, routine)
    report = dict(REPORT, triage={"tag": "URGENT", "emergency": True, "redFlags": ["NEW_FLASHES"], "redFlagReasons": ["New flashes of light"]})
    case = request_case(client, urgent, report=report)
    assert case["priority"] == "URGENT"
    assert case["urgentNote"] == "A teleconsult never replaces an urgent visit. Please see an eye doctor today."
    assert case["redFlagReasons"] == ["New flashes of light"]
    queue = client.get("/api/consultations", headers=optom).json()
    assert [c["priority"] for c in queue] == ["URGENT", "ROUTINE"] and queue[0]["urgentNote"] and queue[1]["urgentNote"] is None
    # a priority typed by the client is ignored: it is read from the report's own triage tag
    assert client.post("/api/consultations", json={"report": REPORT, "priority": "URGENT"}, headers=routine).status_code == 422
    # the portal's case view writes the urgent note before the report
    page = PORTAL.read_text(encoding="utf-8")
    view = page[page.index("function reportView(c)"):]
    banner = view.index('if (c.priority === "URGENT") out.push(')
    assert banner < view.index("out.push(", banner + 40) and view.index("const out = [];") < banner
    assert view[view.index("const out = [];"):banner].count("out.push(") == 0          # nothing is added before the urgent banner
    assert "see an eye doctor today. A teleconsult never replaces an urgent visit." in view
