import copy

import pytest
from fastapi.testclient import TestClient

from app.main import create_app, privacy_problems

PROFILE = {
    "consent": {"consentAt": 1762934400000, "spoken": True, "tapped": True, "textVersion": "bridge-1"},
    "app": {"version": "0.1", "lang": "HI"},
    "demo": False,
    "answers": {"age": 48, "visionProblem": "NEAR", "nearTask": "ACCOUNTS", "powerKnown": "NOT_SURE"},
    "functionalProfile": {"nearHours": 4.5, "intermediateHours": 1.2, "distanceHours": 1.0, "dominant": "NEAR",
                          "impliedLens": "READING"},
    "powerSource": "PHONE_ESTIMATE",
    "power": {"right": {"bandD": [-0.5, 1.0], "startingTrialLens": "-0.25"}, "addD": 1.5},
    "measurements": {"pdMm": 63.0, "faceWidthMm": 140.0},
    "estimates": {"lensType": "READING", "referralLevel": "NONE", "notes": ["PHONE_ESTIMATE_APPROXIMATE"]},
}


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(str(tmp_path / "t.db"), token=""))


def send(client, body=None, **headers):
    return client.post("/profiles", json=body if body is not None else PROFILE, headers=headers)


def test_profile_round_trip_and_list(client):
    r = send(client)
    assert r.status_code == 201
    pid = r.json()["id"]
    assert r.json()["status"] == "WAITING"

    rows = client.get("/profiles").json()
    assert len(rows) == 1
    assert rows[0] == {**rows[0], "id": pid, "status": "WAITING", "age": 48, "visionProblem": "NEAR",
                       "powerSource": "PHONE_ESTIMATE", "referralLevel": "NONE"}

    full = client.get(f"/profiles/{pid}").json()
    assert full["profile"]["power"]["addD"] == 1.5
    assert full["review"] == {"status": "WAITING"}


def test_review_flow(client):
    pid = send(client).json()["id"]
    assert client.get(f"/profiles/{pid}/review").json() == {"status": "WAITING"}

    r = client.post(f"/profiles/{pid}/review", json={"advice": "Try +1.50 readers.", "needsInPersonExam": False})
    assert r.status_code == 200
    rv = client.get(f"/profiles/{pid}/review").json()
    assert rv["status"] == "REVIEWED"
    assert rv["advice"] == "Try +1.50 readers."
    assert rv["needsInPersonExam"] is False
    assert rv["reviewedAt"] > 0
    assert client.get("/profiles").json()[0]["status"] == "REVIEWED"

    # a second review replaces the first
    client.post(f"/profiles/{pid}/review", json={"advice": "Please come in.", "needsInPersonExam": True})
    rv = client.get(f"/profiles/{pid}/review").json()
    assert (rv["advice"], rv["needsInPersonExam"]) == ("Please come in.", True)


def test_review_validation_and_unknown_profile(client):
    pid = send(client).json()["id"]
    assert client.post(f"/profiles/{pid}/review", json={"advice": "", "needsInPersonExam": False}).status_code == 422
    assert client.post(f"/profiles/{pid}/review", json={"advice": "x"}).status_code == 422
    assert client.post("/profiles/nope/review", json={"advice": "x", "needsInPersonExam": False}).status_code == 404
    assert client.get("/profiles/nope/review").status_code == 404
    assert client.get("/profiles/nope").status_code == 404


def test_consent_is_required(client):
    for field in ("spoken", "tapped"):
        body = copy.deepcopy(PROFILE)
        body["consent"][field] = False
        assert send(client, body).status_code == 422
    body = copy.deepcopy(PROFILE)
    del body["consent"]
    assert send(client, body).status_code == 422
    assert client.get("/profiles").json() == []


@pytest.mark.parametrize("path,key", [
    ("answers", "name"), ("answers", "customer_name"), ("measurements", "photo"), ("app", "faceImage"),
    ("estimates", "phoneNumber"), ("answers", "Email"),
])
def test_names_photos_and_contacts_are_rejected(client, path, key):
    body = copy.deepcopy(PROFILE)
    body[path][key] = "x"
    r = send(client, body)
    assert r.status_code == 422
    assert client.get("/profiles").json() == []


def test_nested_forbidden_key_and_long_strings_are_rejected(client):
    body = copy.deepcopy(PROFILE)
    body["power"]["right"]["image"] = "data"
    assert send(client, body).status_code == 422
    body = copy.deepcopy(PROFILE)
    body["measurements"]["blob"] = "A" * 5000  # e.g. a base64 picture
    assert send(client, body).status_code == 422
    assert privacy_problems({"a": [{"Photo": 1}]}) == ["a[0].Photo"]
    assert privacy_problems(PROFILE) == []


def test_unknown_top_level_fields_and_oversize_bodies_are_rejected(client):
    body = copy.deepcopy(PROFILE)
    body["extra"] = 1
    assert send(client, body).status_code == 422
    body = copy.deepcopy(PROFILE)
    body["answers"] = {f"k{i}": "x" * 1000 for i in range(300)}
    assert send(client, body).status_code == 413


def test_delete_removes_profile_and_review(client):
    pid = send(client).json()["id"]
    client.post(f"/profiles/{pid}/review", json={"advice": "ok", "needsInPersonExam": False})
    assert client.delete(f"/profiles/{pid}").status_code == 204
    assert client.get(f"/profiles/{pid}").status_code == 404
    assert client.delete(f"/profiles/{pid}").status_code == 404


def test_token_is_enforced_when_set(tmp_path):
    c = TestClient(create_app(str(tmp_path / "t.db"), token="s3cret"))
    h = c.get("/health").json()
    assert (h["ok"], h["tokenRequired"], h["tokenOk"]) == (True, True, False)
    assert c.get("/health", headers={"X-Bridge-Token": "s3cret"}).json()["tokenOk"] is True
    assert send(c).status_code == 401
    assert c.get("/profiles", headers={"X-Bridge-Token": "wrong"}).status_code == 401
    assert send(c, **{"X-Bridge-Token": "s3cret"}).status_code == 201
    assert len(c.get("/profiles", headers={"X-Bridge-Token": "s3cret"}).json()) == 1


def test_data_survives_a_restart(tmp_path):
    db = str(tmp_path / "t.db")
    pid = send(TestClient(create_app(db, token=""))).json()["id"]
    assert TestClient(create_app(db, token="")).get(f"/profiles/{pid}").status_code == 200


def test_optometrist_page_is_served(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "Optometrist Bridge" in r.text
    assert "textContent" in r.text  # values are inserted as text, never as HTML


def test_profile_built_by_the_app_is_accepted(client):
    """Contract: tests/fixtures/app_profile.json is written by the app's BridgeProfileTest (copy it after changes)."""
    import json
    from pathlib import Path
    body = json.loads((Path(__file__).parent / "fixtures" / "app_profile.json").read_text(encoding="utf-8"))
    r = send(client, body)
    assert r.status_code == 201, r.text
    row = client.get("/profiles").json()[0]
    assert row["powerSource"] == "PHONE_ESTIMATE"
    assert row["demo"] is True
