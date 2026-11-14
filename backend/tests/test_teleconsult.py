"""Teleconsult: accounts, roles, the approval gate, the consultation flow, chat, prescription PDF, data deletion."""
import base64
import sqlite3

import pytest
from fastapi.testclient import TestClient

from app import teleconsult
from app.main import create_app

ADMIN = {"role": "admin", "identifier": "admin@test.example", "password": "admin-pass-123"}
PATIENT = {"name": "Asha Devi", "phone": "98765 43210", "password": "patient-pass", "age": 34, "gender": "F",
           "language": "HI", "consent": True}
OPTOM = {"name": "Dr Test Optom", "phone": "9123456780", "email": "Optom@Test.Example", "password": "optom-pass-1",
         "qualification": "B.Optom", "registrationNo": "UP-OPT-1234", "clinicName": "Test Eye Clinic", "city": "Kanpur",
         "languages": ["Hindi", "English"], "fee": 200, "allowWhatsapp": True}
REPORT = {
    "triage": {"tag": "ROUTINE", "redFlags": [], "redFlagReasons": []},
    "answers": {"age": 34, "visionProblem": "DISTANCE"},
    "power": {"phoneRange": {"right": "-2.00 to -2.50 D", "left": "not measured - skipped"}},
    "visionProfile": {"work": {"score": 88, "label": "Near-focused", "answered": True}},
    "screening": {"lines": [{"text": "Grid R: lines straight", "status": "OK"}]},
    "lens": {"ids": ["single_vision"], "items": [{"product": "ZEISS Single Vision", "why": ["Fits your budget"]}]},
    "estimates": {"referralLevel": "NONE"},
}


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "t.db")


@pytest.fixture
def client(db_path):
    return TestClient(create_app(db_path, token="", jwt_secret="test-secret-key-that-is-long-enough-for-hs256",
                                 admin_email="Admin@Test.Example", admin_password="admin-pass-123"))


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def login(client, body):
    r = client.post("/api/auth/login", json=body)
    assert r.status_code == 200, r.text
    return auth(r.json()["token"])


def new_patient(client, **over):
    r = client.post("/api/auth/register/patient", json={**PATIENT, **over})
    assert r.status_code == 201, r.text
    return auth(r.json()["token"]), r.json()["user"]


def new_optometrist(client, approve=True, **over):
    r = client.post("/api/auth/register/optometrist", json={**OPTOM, **over})
    assert r.status_code == 201, r.text
    user = r.json()["user"]
    if approve:
        assert client.post(f"/api/admin/optometrists/{user['id']}/approve", headers=login(client, ADMIN)).status_code == 200
    return auth(r.json()["token"]), user


def request_case(client, patient, report=None, optometrist_id=None):
    r = client.post("/api/consultations", json={"report": report or REPORT, "optometristId": optometrist_id}, headers=patient)
    assert r.status_code == 201, r.text
    return r.json()


# ---- auth ----

def test_passwords_are_hashed_and_login_works(client, db_path):
    _, user = new_patient(client)
    assert user["phone"] == "9876543210" and user["consentAt"] and user["consentText"] == teleconsult.CONSENT_TEXT
    con = sqlite3.connect(db_path)
    hashes = [r[0] for r in con.execute("SELECT password_hash FROM tc_users")]
    con.close()
    assert len(hashes) == 2 and all(h.startswith("$2") for h in hashes)       # bcrypt, admin + patient
    assert all("patient-pass" not in h and "admin-pass-123" not in h for h in hashes)

    assert client.post("/api/auth/login", json={"role": "patient", "identifier": "+91 98765-43210", "password": "patient-pass"}).status_code == 200
    wrong = client.post("/api/auth/login", json={"role": "patient", "identifier": "9876543210", "password": "nope-nope"})
    unknown = client.post("/api/auth/login", json={"role": "patient", "identifier": "9000000000", "password": "patient-pass"})
    assert wrong.status_code == unknown.status_code == 401 and wrong.json() == unknown.json()
    # the same phone as another role is another account, and a patient cannot log in as an optometrist
    assert client.post("/api/auth/login", json={"role": "optometrist", "identifier": "9876543210", "password": "patient-pass"}).status_code == 401


def test_registration_rules(client):
    assert client.post("/api/auth/register/patient", json={**PATIENT, "consent": False}).status_code == 422
    assert client.post("/api/auth/register/patient", json={**PATIENT, "password": "short"}).status_code == 422
    assert client.post("/api/auth/register/patient", json={**PATIENT, "phone": "12345"}).status_code == 422
    assert client.post("/api/auth/register/patient", json={**PATIENT, "role": "admin"}).status_code == 422   # no extra fields
    new_patient(client)
    assert client.post("/api/auth/register/patient", json=PATIENT).status_code == 409
    new_optometrist(client, approve=False)
    assert client.post("/api/auth/register/optometrist", json={**OPTOM, "phone": "9000000001"}).status_code == 409   # same email
    assert client.post("/api/auth/register/optometrist", json={**OPTOM, "phone": "9000000002", "email": "not-an-email"}).status_code == 422


def test_every_endpoint_needs_a_valid_token(client, db_path):
    for method, path in [("get", "/api/me"), ("get", "/api/optometrists"), ("get", "/api/consultations"),
                         ("get", "/api/admin/optometrists"), ("post", "/api/consultations"), ("delete", "/api/me"),
                         ("get", "/api/consultations/x"), ("get", "/api/consultations/x/messages"),
                         ("get", "/api/consultations/x/prescription.pdf"), ("get", "/api/lens-catalog")]:
        assert getattr(client, method)(path).status_code == 401, path
        assert getattr(client, method)(path, headers=auth("not.a.token")).status_code == 401, path
    # a token signed with another key is refused
    other = TestClient(create_app(db_path + "-other", token="", jwt_secret="another-secret-key-another-secret-key-1234"))
    forged = other.post("/api/auth/register/patient", json=PATIENT).json()["token"]
    assert client.get("/api/me", headers=auth(forged)).status_code == 401


def test_admin_comes_from_the_environment_only(client, db_path):
    assert client.get("/api/me", headers=login(client, ADMIN)).json()["role"] == "admin"
    # no admin without the two settings, and there is no way to register one
    bare = TestClient(create_app(db_path + "2", token="", jwt_secret="x" * 40, admin_email="", admin_password=""))
    assert bare.post("/api/auth/login", json=ADMIN).status_code == 401
    assert bare.post("/api/auth/register/admin", json=ADMIN).status_code in (404, 405)


# ---- roles ----

def test_role_checks(client):
    patient, _ = new_patient(client)
    optom, o = new_optometrist(client)
    admin = login(client, ADMIN)
    # admin pages
    for h in (patient, optom):
        assert client.get("/api/admin/optometrists", headers=h).status_code == 403
        assert client.post(f"/api/admin/optometrists/{o['id']}/reject", headers=h).status_code == 403
        assert client.get("/api/admin/summary", headers=h).status_code == 403
    # only a patient asks for a consultation or deletes the account
    assert client.post("/api/consultations", json={"report": REPORT}, headers=optom).status_code == 403
    assert client.post("/api/consultations", json={"report": REPORT}, headers=admin).status_code == 403
    assert client.delete("/api/me", headers=optom).status_code == 403
    # only an optometrist accepts, starts a video call or writes a prescription
    case = request_case(client, patient)
    for path in ("accept", "video", "prescription"):
        assert client.post(f"/api/consultations/{case['id']}/{path}", json={}, headers=patient).status_code == 403, path
        assert client.post(f"/api/consultations/{case['id']}/{path}", json={}, headers=admin).status_code == 403, path
    # a patient cannot change optometrist fields
    assert client.patch("/api/me", json={"fee": 1}, headers=patient).status_code == 422
    assert client.patch("/api/me", json={"status": "APPROVED"}, headers=optom).status_code == 422


def test_a_patient_sees_only_their_own_cases(client):
    asha, _ = new_patient(client)
    ravi, _ = new_patient(client, name="Ravi", phone="9811111111")
    case = request_case(client, asha)
    assert [c["id"] for c in client.get("/api/consultations", headers=asha).json()] == [case["id"]]
    assert client.get("/api/consultations", headers=ravi).json() == []
    for path in ("", "/messages", "/prescription", "/prescription.pdf"):
        assert client.get(f"/api/consultations/{case['id']}{path}", headers=ravi).status_code == 404, path
    assert client.post(f"/api/consultations/{case['id']}/messages", json={"text": "hi"}, headers=ravi).status_code == 404
    assert client.post(f"/api/consultations/{case['id']}/close", headers=ravi).status_code == 404


# ---- approval gate ----

def test_a_pending_optometrist_sees_no_cases_until_an_admin_approves(client):
    patient, _ = new_patient(client)
    case = request_case(client, patient)
    optom, o = new_optometrist(client, approve=False)
    assert o["status"] == "PENDING" and o["available"] is False
    for method, path in [("get", "/api/consultations"), ("get", f"/api/consultations/{case['id']}"),
                         ("post", f"/api/consultations/{case['id']}/accept"), ("get", f"/api/consultations/{case['id']}/messages"),
                         ("get", "/api/lens-catalog")]:
        assert getattr(client, method)(path, headers=optom).status_code == 403, path
    assert client.patch("/api/me", json={"available": True}, headers=optom).status_code == 403
    assert client.get("/api/optometrists", headers=patient).json() == []          # not offered to patients
    assert client.post("/api/consultations", json={"report": REPORT, "optometristId": o["id"]}, headers=patient).status_code == 404

    admin = login(client, ADMIN)
    assert [x["status"] for x in client.get("/api/admin/optometrists?status=pending", headers=admin).json()] == ["PENDING"]
    assert client.post(f"/api/admin/optometrists/{o['id']}/approve", headers=admin).json()["status"] == "APPROVED"
    assert [c["id"] for c in client.get("/api/consultations", headers=optom).json()] == [case["id"]]
    listed = client.get("/api/optometrists", headers=patient).json()
    assert [x["name"] for x in listed] == ["Dr Test Optom"]
    assert "phone" not in listed[0] and "email" not in listed[0] and listed[0]["registrationNo"] == "UP-OPT-1234"

    # rejected again: locked out and no longer listed
    client.post(f"/api/admin/optometrists/{o['id']}/reject", headers=admin)
    assert client.get("/api/consultations", headers=optom).status_code == 403
    assert client.get("/api/optometrists", headers=patient).json() == []
    assert client.post("/api/admin/optometrists/nope/approve", headers=admin).status_code == 404


# ---- consultation flow ----

def test_consultation_flow_request_accept_chat_video_prescription(client):
    patient, p = new_patient(client)
    optom, o = new_optometrist(client)
    case = request_case(client, patient, optometrist_id=o["id"])
    cid = case["id"]
    assert case["status"] == "REQUESTED" and case["priority"] == "ROUTINE" and case["fee"] == 200
    assert case["paymentStatus"] == "not_required (demo)" and case["report"]["power"]["phoneRange"]["right"].startswith("-2.00")
    assert case["optometrist"] is None and case["whatsappUrl"] is None

    # before accepting: the optometrist sees the report, the name, age and language, but not the phone, and cannot chat
    seen = client.get(f"/api/consultations/{cid}", headers=optom).json()
    assert seen["patient"]["name"] == "Asha Devi" and seen["patient"]["age"] == 34 and seen["patient"]["language"] == "HI"
    assert "phone" not in seen["patient"] and seen["whatsappUrl"] is None and seen["report"] == REPORT
    assert client.get(f"/api/consultations/{cid}/messages", headers=optom).status_code == 403
    assert client.post(f"/api/consultations/{cid}/video", headers=optom).status_code == 403
    assert client.post(f"/api/consultations/{cid}/prescription", json={}, headers=optom).status_code == 403

    acc = client.post(f"/api/consultations/{cid}/accept", headers=optom).json()
    assert acc["status"] == "ACCEPTED" and acc["acceptedAt"] and acc["optometrist"]["id"] == o["id"]
    assert acc["patient"]["phone"] == "9876543210"
    assert acc["whatsappUrl"].startswith("https://wa.me/919876543210?text=") and acc["caseId"] in acc["whatsappUrl"]
    assert "%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87" in acc["whatsappUrl"]      # "नमस्ते": a Hindi session
    # the patient gets the optometrist's WhatsApp only because the optometrist allowed it
    mine = client.get(f"/api/consultations/{cid}", headers=patient).json()
    assert mine["whatsappUrl"].startswith("https://wa.me/919123456780?text=") and "phone" not in mine["optometrist"]

    # chat: the first reply of the optometrist starts the consultation
    assert client.post(f"/api/consultations/{cid}/messages", json={"text": "Namaste, can you see far signboards?"}, headers=optom).status_code == 201
    assert client.get(f"/api/consultations/{cid}", headers=patient).json()["status"] == "IN_PROGRESS"
    assert client.post(f"/api/consultations/{cid}/messages", json={"text": "Not clearly"}, headers=patient).status_code == 201
    assert client.post(f"/api/consultations/{cid}/messages", json={"text": "  "}, headers=patient).status_code == 422
    msgs = client.get(f"/api/consultations/{cid}/messages", headers=patient).json()
    assert [(m["senderRole"], m["kind"]) for m in msgs] == [("system", "SYSTEM"), ("optometrist", "TEXT"), ("patient", "TEXT")]
    assert client.get(f"/api/consultations/{cid}/messages?after={msgs[1]['id']}", headers=optom).json() == [msgs[2]]

    # video: a Jitsi room, posted into the chat, the same room the second time
    v = client.post(f"/api/consultations/{cid}/video", headers=optom).json()["url"]
    assert v.startswith("https://meet.jit.si/drishti-") and len(v.rsplit("-", 1)[1]) == 12
    assert client.post(f"/api/consultations/{cid}/video", headers=optom).json()["url"] == v
    assert client.get(f"/api/consultations/{cid}", headers=patient).json()["videoUrl"] == v
    assert client.get(f"/api/consultations/{cid}/messages", headers=patient).json()[-1]["text"] == v

    # prescription: stored with the optometrist's name and registration number, the case is COMPLETED
    rx = {"right": {"sph": -2.25, "cyl": -0.5, "axis": 180}, "left": {"sph": -2.0}, "add": None, "pdMm": 62.5,
          "lensAdvice": ["ZEISS Single Vision", "DuraVision coating"], "referralAdvice": "Full eye exam within a month",
          "needsInPersonExam": True, "followUpDate": "2026-12-15", "notes": "दूर का चश्मा पहनें"}
    assert client.post(f"/api/consultations/{cid}/prescription", json={**rx, "right": {"cyl": -0.5}}, headers=optom).status_code == 422
    assert client.post(f"/api/consultations/{cid}/prescription", json={**rx, "right": {"sph": -99}}, headers=optom).status_code == 422
    saved = client.post(f"/api/consultations/{cid}/prescription", json=rx, headers=optom)
    assert saved.status_code == 200, saved.text
    got = client.get(f"/api/consultations/{cid}/prescription", headers=patient).json()
    assert got["right"] == {"sph": -2.25, "cyl": -0.5, "axis": 180} and got["followUpDate"] == "2026-12-15"
    assert got["optometristName"] == "Dr Test Optom" and got["registrationNo"] == "UP-OPT-1234"
    done = client.get(f"/api/consultations/{cid}", headers=patient).json()
    assert done["status"] == "COMPLETED" and done["completedAt"] and done["hasPrescription"] is True
    for h in (patient, optom):
        pdf = client.get(f"/api/consultations/{cid}/prescription.pdf", headers=h)
        assert pdf.status_code == 200 and pdf.headers["content-type"] == "application/pdf" and pdf.content[:5] == b"%PDF-"
        assert len(pdf.content) > 1500

    # closed: no more messages
    assert client.post(f"/api/consultations/{cid}/close", headers=patient).json()["status"] == "CLOSED"
    assert client.post(f"/api/consultations/{cid}/messages", json={"text": "hello?"}, headers=optom).status_code == 409
    assert client.post(f"/api/consultations/{cid}/prescription", json=rx, headers=optom).status_code == 409


def test_queue_order_any_available_and_one_optometrist_per_case(client):
    patient, _ = new_patient(client)
    first, a = new_optometrist(client)
    second, b = new_optometrist(client, name="Dr Second", phone="9000000009", email="second@test.example")
    routine = request_case(client, patient)
    child = request_case(client, patient, {**REPORT, "triage": {"tag": "CHILD_MYOPIA"}})
    urgent = request_case(client, patient, {**REPORT, "triage": {"tag": "ROUTINE", "redFlagReasons": ["Sudden loss of vision"]},
                                             "estimates": {"referralLevel": "URGENT"}})
    only_b = request_case(client, patient, optometrist_id=b["id"])
    assert urgent["priority"] == "URGENT" and "never replaces an urgent visit" in urgent["urgentNote"]
    assert routine["urgentNote"] is None

    queue = client.get("/api/consultations", headers=first).json()
    assert [c["id"] for c in queue] == [urgent["id"], child["id"], routine["id"]]          # urgent first; not B's own request
    assert queue[0]["redFlagReasons"] == ["Sudden loss of vision"] and "report" not in queue[0]
    assert client.get(f"/api/consultations/{only_b['id']}", headers=first).status_code == 404
    assert client.post(f"/api/consultations/{only_b['id']}/accept", headers=first).status_code == 404

    assert client.post(f"/api/consultations/{urgent['id']}/accept", headers=first).status_code == 200
    assert client.post(f"/api/consultations/{urgent['id']}/accept", headers=first).status_code == 200      # again: no change
    assert client.post(f"/api/consultations/{urgent['id']}/accept", headers=second).status_code == 404     # gone from B's view
    assert urgent["id"] not in [c["id"] for c in client.get("/api/consultations", headers=second).json()]
    assert client.get(f"/api/consultations/{urgent['id']}/messages", headers=second).status_code == 404
    assert client.post(f"/api/consultations/{urgent['id']}/prescription", json={}, headers=second).status_code == 404


def test_report_rules_and_chat_pictures(client):
    patient, _ = new_patient(client)
    optom, _ = new_optometrist(client)
    # the report itself never carries a name, a photo or a phone number
    bad = client.post("/api/consultations", json={"report": {**REPORT, "answers": {"name": "Asha"}}}, headers=patient)
    assert bad.status_code == 422 and "answers.name" in str(bad.json())
    assert client.post("/api/consultations", content=b'{"report": {"x": "' + b"a" * 300_000 + b'"}}', headers=patient).status_code == 413
    assert client.post("/api/consultations", json={"report": REPORT, "priority": "URGENT"}, headers=patient).status_code == 422

    cid = request_case(client, patient)["id"]
    client.post(f"/api/consultations/{cid}/accept", headers=optom)
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    sent = client.post(f"/api/consultations/{cid}/messages", json={"imageBase64": base64.b64encode(png).decode(), "imageMime": "image/png"}, headers=patient)
    assert sent.status_code == 201 and sent.json()["kind"] == "IMAGE" and sent.json()["hasImage"] is True
    img = client.get(f"/api/consultations/{cid}/messages/{sent.json()['id']}/image", headers=optom)
    assert img.status_code == 200 and img.content == png and img.headers["content-type"] == "image/png"
    assert client.post(f"/api/consultations/{cid}/messages", json={"imageBase64": "AAAA", "imageMime": "text/html"}, headers=patient).status_code == 422
    assert client.post(f"/api/consultations/{cid}/messages", json={"imageBase64": "not base64!", "imageMime": "image/png"}, headers=patient).status_code == 422
    big = base64.b64encode(b"x" * 650_000).decode()
    assert client.post(f"/api/consultations/{cid}/messages", json={"imageBase64": big, "imageMime": "image/png"}, headers=patient).status_code == 413


def test_profile_update_and_availability(client):
    patient, _ = new_patient(client)
    optom, o = new_optometrist(client)
    assert client.get("/api/me", headers=optom).json()["available"] is True          # approval switches it on
    upd = client.patch("/api/me", json={"fee": 350, "clinicName": "New Clinic", "languages": ["Hindi"], "available": False, "allowWhatsapp": False}, headers=optom).json()
    assert (upd["fee"], upd["clinicName"], upd["languages"], upd["available"], upd["allowWhatsapp"]) == (350, "New Clinic", ["Hindi"], False, False)
    assert client.get("/api/optometrists", headers=patient).json() == []              # not available: not offered
    assert client.patch("/api/me", json={"age": 35, "language": "EN"}, headers=patient).json()["age"] == 35
    # WhatsApp to the optometrist only when they allow it
    cid = request_case(client, patient)["id"]
    client.post(f"/api/consultations/{cid}/accept", headers=optom)
    assert client.get(f"/api/consultations/{cid}", headers=patient).json()["whatsappUrl"] is None
    assert "Hello" in client.get(f"/api/consultations/{cid}", headers=optom).json()["whatsappUrl"]   # an English session now
    assert len(client.get("/api/lens-catalog", headers=optom).json()) > 5


# ---- data deletion ----

def test_a_patient_can_delete_the_account_and_all_its_data(client, db_path):
    asha, a = new_patient(client)
    ravi, _ = new_patient(client, name="Ravi", phone="9811111111")
    optom, _ = new_optometrist(client)
    cid = request_case(client, asha)["id"]
    kept = request_case(client, ravi)["id"]
    client.post(f"/api/consultations/{cid}/accept", headers=optom)
    client.post(f"/api/consultations/{cid}/messages", json={"text": "my phone is 9876543210"}, headers=asha)
    client.post(f"/api/consultations/{cid}/prescription", json={"right": {"sph": -1.0}}, headers=optom)

    assert client.delete("/api/me", headers=asha).status_code == 204
    assert client.get("/api/me", headers=asha).status_code == 401                      # the old token is dead
    assert client.post("/api/auth/login", json={"role": "patient", "identifier": PATIENT["phone"], "password": PATIENT["password"]}).status_code == 401
    assert client.get(f"/api/consultations/{cid}", headers=optom).status_code == 404
    assert [c["id"] for c in client.get("/api/consultations", headers=optom).json()] == [kept]

    con = sqlite3.connect(db_path)
    try:
        assert con.execute("SELECT COUNT(*) FROM tc_users WHERE id = ?", (a["id"],)).fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM tc_consultations WHERE id = ?", (cid,)).fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM tc_messages WHERE consultation_id = ?", (cid,)).fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM tc_prescriptions WHERE consultation_id = ?", (cid,)).fetchone()[0] == 0
        dump = " ".join(str(v) for t in ("tc_users", "tc_consultations", "tc_messages", "tc_prescriptions") for row in con.execute(f"SELECT * FROM {t}") for v in row)
        assert "Asha" not in dump and "9876543210" not in dump
        assert con.execute("SELECT COUNT(*) FROM tc_consultations").fetchone()[0] == 1   # the other patient is untouched
    finally:
        con.close()
    # the number can register again
    new_patient(client)


def test_portal_pages_are_served(client):
    for path in ("/portal", "/portal/admin"):
        r = client.get(path)
        assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    assert client.get("/portal/assets/inter_deva_regular.ttf").status_code == 200
    assert client.get("/portal/assets/..%2F..%2Fmain.py").status_code == 404
    # the older bridge page and API still work
    assert client.get("/").status_code == 200 and client.get("/health").json()["ok"] is True


# ---- demo data ----

def test_seed_script_makes_marked_demo_accounts_once(client, db_path):
    from app import seed_demo
    made = seed_demo.seed(db_path, "demo-pass-123")
    assert made == {"optometrists": 3, "patients": 3, "consultations": 3}
    assert seed_demo.seed(db_path, "demo-pass-123") == {"optometrists": 0, "patients": 0, "consultations": 0}    # nothing twice

    admin = login(client, ADMIN)
    everyone = client.get("/api/admin/optometrists", headers=admin).json()
    assert sorted(o["status"] for o in everyone) == ["APPROVED", "APPROVED", "PENDING"]
    assert all(o["name"].startswith("DEMO ") and o["demo"] for o in everyone)

    optom = login(client, {"role": "optometrist", "identifier": "9000000101", "password": "demo-pass-123"})
    queue = client.get("/api/consultations", headers=optom).json()
    assert [c["priority"] for c in queue] == ["URGENT", "CHILD_MYOPIA", "ROUTINE"]
    assert all(c["patient"]["name"].startswith("DEMO ") for c in queue)
    assert client.get(f"/api/consultations/{queue[0]['id']}", headers=optom).json()["report"]["triage"]["emergency"] is True
    pending = login(client, {"role": "optometrist", "identifier": "9000000103", "password": "demo-pass-123"})
    assert client.get("/api/consultations", headers=pending).status_code == 403
    patient = login(client, {"role": "patient", "identifier": "9000000203", "password": "demo-pass-123"})
    assert len(client.get("/api/consultations", headers=patient).json()) == 1
    assert len(client.get("/api/optometrists", headers=patient).json()) == 2
    # --reset removes only the demo rows (and what hangs on them) and makes them again
    real, _ = new_patient(client)
    cid = request_case(client, real)["id"]
    client.post(f"/api/consultations/{queue[2]['id']}/accept", headers=optom)
    assert seed_demo.seed(db_path, "demo-pass-123", reset=True) == {"optometrists": 3, "patients": 3, "consultations": 3}
    optom = login(client, {"role": "optometrist", "identifier": "9000000101", "password": "demo-pass-123"})
    after = client.get("/api/consultations", headers=optom).json()
    assert sorted(c["status"] for c in after) == ["REQUESTED"] * 4 and cid in [c["id"] for c in after]
    assert client.get("/api/me", headers=real).json()["name"] == "Asha Devi"
    # the sample reports pass the same privacy check as real ones
    from app.main import privacy_problems
    assert all(privacy_problems(seed_demo.report(k, 30)) == [] for k in ("URGENT", "CHILD_MYOPIA", "ROUTINE"))
