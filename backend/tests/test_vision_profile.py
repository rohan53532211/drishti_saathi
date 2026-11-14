"""Vision-profile app: report tags, URGENT first, the new profile sections, and the optometrist's confirmed power."""
import copy
import sqlite3

import pytest
from fastapi.testclient import TestClient

from app.main import create_app, tag_of

from test_api import PROFILE

ROUTINE = {
    **PROFILE,
    "triage": {"tag": "ROUTINE", "redFlags": [], "redFlagReasons": [], "emergency": False, "childMyopia": False, "lensSuggested": True},
    "visionProfile": {"work": {"score": 88, "label": "Near-focused", "answered": True}, "digital": {"score": 100, "label": "Digital Native", "answered": True},
                      "mobility": {"score": 65, "label": "Weekly", "answered": True}, "leisure": {"score": 17, "label": "Low Sun & UV Exposure", "answered": True},
                      "overall": 68, "nightGlareSensitive": False},
    "screening": {"label": "Screening, not a diagnosis",
                  "lines": [{"text": "Grid R: lines straight", "status": "OK"}, {"text": "Colour: Normal", "status": "OK"}],
                  "results": {"amsler": [{"eye": "RIGHT", "distortion": False, "cells": [], "columns": 12, "rows": 20, "distanceMm": 300.0}]}},
    "lens": {"label": "Suggested ZEISS lens family – optician confirms", "suggested": True, "optometristConfirms": False,
             "ids": ["anti_fatigue", "smartlife", "uvprotect", "blueguard", "dv_platinum", "office"],
             "items": [{"kind": "LENS_TYPE", "id": "anti_fatigue", "product": "ZEISS Anti-fatigue (Digital) lenses",
                        "why": ["You are 35 and your eyes get tired after 10 h of screens."], "url": "https://www.zeiss.co.in/"}]},
    "survey": {"values": {"screenHoursDay": ["10"], "driveFrequency": ["WEEKLY"]}},
}


def urgent():
    p = copy.deepcopy(ROUTINE)
    p["triage"] = {"tag": "URGENT", "redFlags": ["NEW_FLASHES", "AMSLER_DISTORTION"],
                   "redFlagReasons": ["New flashes of light", "Wavy, bent or missing lines (grid)"], "emergency": True,
                   "childMyopia": False, "lensSuggested": False, "powerLabel": "For the optometrist only"}
    p["estimates"] = {**p["estimates"], "referralLevel": "URGENT"}
    p["screening"]["results"]["amsler"] = [{"eye": "LEFT", "distortion": True, "cells": [{"col": 3, "row": 7}, {"col": 4, "row": 7}],
                                            "columns": 12, "rows": 20, "distanceMm": 305.0}]
    p["lens"] = {"label": p["lens"]["label"], "suggested": False, "optometristConfirms": False, "ids": [], "items": []}
    return p


def child():
    p = copy.deepcopy(ROUTINE)
    p["answers"] = {**p["answers"], "age": 10}
    p["triage"] = {**p["triage"], "tag": "CHILD_MYOPIA", "childMyopia": True, "lensSuggested": False}
    p["lens"] = {"label": p["lens"]["label"], "suggested": False, "ids": [], "items": [], "referralInfo": "ZEISS Myopia management lenses"}
    return p


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(str(tmp_path / "t.db"), token=""))


def test_new_sections_are_accepted_and_returned(client):
    r = client.post("/profiles", json=ROUTINE)
    assert r.status_code == 201
    full = client.get(f"/profiles/{r.json()['id']}").json()
    assert full["tag"] == "ROUTINE"
    p = full["profile"]
    assert p["visionProfile"]["digital"]["label"] == "Digital Native"
    assert p["screening"]["label"] == "Screening, not a diagnosis"
    assert p["lens"]["ids"][0] == "anti_fatigue"
    assert p["survey"]["values"]["screenHoursDay"] == ["10"]


def test_older_apps_without_the_new_sections_still_work(client):
    r = client.post("/profiles", json=PROFILE)
    assert r.status_code == 201
    row = client.get("/profiles").json()[0]
    assert row["tag"] == "ROUTINE" and row["redFlagReasons"] == [] and row["lensIds"] == []


def test_urgent_comes_first_then_child_myopia_then_routine(client):
    ids = {}
    for name, body in [("routine1", ROUTINE), ("child", child()), ("urgent", urgent()), ("routine2", ROUTINE)]:
        ids[name] = client.post("/profiles", json=body).json()["id"]
    rows = client.get("/profiles").json()
    assert [r["tag"] for r in rows] == ["URGENT", "CHILD_MYOPIA", "ROUTINE", "ROUTINE"]
    assert rows[0]["id"] == ids["urgent"]
    assert rows[0]["redFlagReasons"] == ["New flashes of light", "Wavy, bent or missing lines (grid)"]
    assert rows[1]["id"] == ids["child"]
    # inside a group the newest is first
    assert {rows[2]["id"], rows[3]["id"]} == {ids["routine1"], ids["routine2"]}
    assert rows[2]["receivedAt"] >= rows[3]["receivedAt"]


def test_urgent_detail_has_the_reasons_the_tapped_grid_and_no_lens(client):
    pid = client.post("/profiles", json=urgent()).json()["id"]
    full = client.get(f"/profiles/{pid}").json()
    assert full["tag"] == "URGENT"
    p = full["profile"]
    assert p["triage"]["redFlagReasons"][0] == "New flashes of light"
    assert p["triage"]["powerLabel"] == "For the optometrist only"
    cells = p["screening"]["results"]["amsler"][0]["cells"]
    assert cells == [{"col": 3, "row": 7}, {"col": 4, "row": 7}]
    assert p["lens"]["suggested"] is False and p["lens"]["ids"] == []


def test_tag_rules():
    assert tag_of({}) == "ROUTINE"
    assert tag_of({"triage": {"tag": "CHILD_MYOPIA"}}) == "CHILD_MYOPIA"
    assert tag_of({"triage": {"tag": "nonsense"}}) == "ROUTINE"
    # an urgent referral level is always URGENT, whatever the app's tag says
    assert tag_of({"triage": {"tag": "ROUTINE"}, "estimates": {"referralLevel": "URGENT"}}) == "URGENT"
    assert tag_of({"triage": {"tag": "urgent"}}) == "URGENT"


def test_privacy_rules_cover_the_new_sections(client):
    bad = copy.deepcopy(ROUTINE)
    bad["survey"]["values"]["customer_name"] = ["x"]
    assert client.post("/profiles", json=bad).status_code == 422
    bad = copy.deepcopy(ROUTINE)
    bad["lens"]["items"][0]["photo"] = "x"
    assert client.post("/profiles", json=bad).status_code == 422
    bad = copy.deepcopy(ROUTINE)
    bad["unknownSection"] = {}
    assert client.post("/profiles", json=bad).status_code == 422


def test_optometrist_confirms_or_edits_the_power_and_replies(client):
    pid = client.post("/profiles", json=ROUTINE).json()["id"]
    body = {"advice": "Confirmed with trial lenses.", "needsInPersonExam": False,
            "confirmedPower": {"right": " -4.25 / -0.50 x 180 ", "left": "-4.00", "add": ""}}
    assert client.post(f"/profiles/{pid}/review", json=body).status_code == 200
    rv = client.get(f"/profiles/{pid}/review").json()
    assert rv["status"] == "REVIEWED"
    assert rv["confirmedPower"] == {"right": "-4.25 / -0.50 x 180", "left": "-4.00"}     # trimmed, empty left out
    assert client.get(f"/profiles/{pid}").json()["review"]["confirmedPower"]["left"] == "-4.00"

    # a second reply without a power replaces it
    client.post(f"/profiles/{pid}/review", json={"advice": "See in person.", "needsInPersonExam": True})
    rv = client.get(f"/profiles/{pid}/review").json()
    assert "confirmedPower" not in rv and rv["needsInPersonExam"] is True

    # over-long or unknown fields are rejected
    assert client.post(f"/profiles/{pid}/review", json={"advice": "x", "needsInPersonExam": False,
                                                        "confirmedPower": {"right": "9" * 41}}).status_code == 422
    assert client.post(f"/profiles/{pid}/review", json={"advice": "x", "needsInPersonExam": False,
                                                        "confirmedPower": {"axis": "90"}}).status_code == 422


def test_an_older_database_gets_the_confirmed_column(tmp_path):
    db = str(tmp_path / "old.db")
    con = sqlite3.connect(db)
    con.executescript("""
        CREATE TABLE profiles (id TEXT PRIMARY KEY, received_at INTEGER NOT NULL, payload TEXT NOT NULL);
        CREATE TABLE reviews (profile_id TEXT PRIMARY KEY, advice TEXT NOT NULL, needs_in_person INTEGER NOT NULL, reviewed_at INTEGER NOT NULL);
    """)
    con.commit(); con.close()
    client = TestClient(create_app(db, token=""))
    pid = client.post("/profiles", json=ROUTINE).json()["id"]
    r = client.post(f"/profiles/{pid}/review", json={"advice": "ok", "needsInPersonExam": False, "confirmedPower": {"right": "-1.00"}})
    assert r.status_code == 200 and r.json()["confirmedPower"] == {"right": "-1.00"}


def test_the_page_shows_the_new_sections(client):
    html = client.get("/").text
    for needle in ["URGENT", "Vision profile", "Screening tests", "amslerSvg", "Confirm or correct the power", "confirmedPower", "Child myopia"]:
        assert needle in html
