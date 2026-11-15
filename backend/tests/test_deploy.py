"""Hosting (Render): CORS, https proxy headers, the demo seed for an empty database, files kept inside backend/."""
import json
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import anamnesis, main, seed_demo, teleconsult
from app.main import create_app

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app" / "app" / "src" / "main" / "assets"


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "t.db")


@pytest.fixture(autouse=True)
def no_local_env(monkeypatch):
    for k in ("DEMO_OPTOMETRIST_PHONE", "DEMO_PATIENT_PHONE", "CORS_ORIGINS", "AUTO_SEED_DEMO"):
        monkeypatch.delenv(k, raising=False)


def users(db_path, where="1 = 1"):
    con = sqlite3.connect(db_path)
    try:
        return con.execute(f"SELECT COUNT(*) FROM tc_users WHERE {where}").fetchone()[0]
    finally:
        con.close()


def test_health_and_portal_answer_behind_an_https_proxy(db_path):
    c = TestClient(create_app(db_path, token="", admin_email="", admin_password=""))
    proxy = {"X-Forwarded-Proto": "https", "X-Forwarded-For": "203.0.113.7", "Host": "drishti.onrender.com"}
    assert c.get("/health", headers=proxy).json()["ok"] is True
    for path in ("/portal", "/portal/", "/portal/admin", "/"):
        r = c.get(path, headers=proxy, follow_redirects=False)
        assert r.status_code == 200 and "text/html" in r.headers["content-type"], path


def test_cors_allows_any_origin_by_default_and_never_credentials(db_path):
    c = TestClient(create_app(db_path, token="", admin_email="", admin_password=""))
    pre = c.options("/api/auth/login", headers={"Origin": "https://shop.example", "Access-Control-Request-Method": "POST",
                                                "Access-Control-Request-Headers": "authorization,content-type,x-bridge-token"})
    assert pre.status_code == 200
    assert pre.headers["access-control-allow-origin"] == "*"
    assert "access-control-allow-credentials" not in pre.headers
    assert c.get("/health", headers={"Origin": "https://shop.example"}).headers["access-control-allow-origin"] == "*"


def test_cors_origins_can_be_limited(db_path, monkeypatch):
    monkeypatch.setenv("CORS_ORIGINS", "https://a.example/, https://b.example")
    c = TestClient(create_app(db_path, token="", admin_email="", admin_password=""))
    assert c.get("/health", headers={"Origin": "https://a.example"}).headers["access-control-allow-origin"] == "https://a.example"
    assert "access-control-allow-origin" not in c.get("/health", headers={"Origin": "https://evil.example"}).headers


def test_empty_database_gets_tables_admin_and_demo_data_and_the_demo_logins_work(db_path, monkeypatch):
    monkeypatch.setenv("DEMO_PASSWORD", "demo-pass-123")
    app = create_app(db_path, token="", jwt_secret="k" * 40, admin_email="admin@test.example", admin_password="admin-pass-123")
    assert users(db_path, "role != 'admin'") == 0                      # only the admin: still "empty"
    assert main.auto_seed_demo(db_path) == {"optometrists": 3, "patients": 3, "consultations": 3}
    assert main.auto_seed_demo(db_path) is None                        # a second start changes nothing
    assert users(db_path, "demo = 1") == 6 and users(db_path, "role = 'admin'") == 1
    c = TestClient(app)
    for body in ({"role": "optometrist", "identifier": "9000000101", "password": "demo-pass-123"},
                 {"role": "patient", "identifier": "9000000201", "password": "demo-pass-123"},
                 {"role": "admin", "identifier": "admin@test.example", "password": "admin-pass-123"}):
        assert c.post("/api/auth/login", json=body).status_code == 200, body["role"]


def test_no_seed_into_a_database_that_has_accounts(db_path, monkeypatch):
    monkeypatch.setenv("DEMO_PASSWORD", "demo-pass-123")
    c = TestClient(create_app(db_path, token="", admin_email="", admin_password=""))
    reg = c.post("/api/auth/register/patient", json={"name": "Real Person", "phone": "9811111111", "password": "patient-pass",
                                                     "age": 40, "consent": True})
    assert reg.status_code == 201
    assert main.auto_seed_demo(db_path) is None
    assert users(db_path) == 1


@pytest.mark.parametrize("env", [{"DEMO_PASSWORD": ""}, {"DEMO_PASSWORD": "short"},
                                 {"DEMO_PASSWORD": "demo-pass-123", "AUTO_SEED_DEMO": "0"}])
def test_no_seed_without_a_demo_password_or_when_switched_off(db_path, monkeypatch, env):
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    create_app(db_path, token="", admin_email="", admin_password="")
    assert main.auto_seed_demo(db_path) is None
    assert users(db_path) == 0


def test_backend_keeps_its_own_copy_of_the_app_files_it_reads(monkeypatch, tmp_path):
    """Render deploys backend/ only: with the app's assets folder away, the copies in app/data are used."""
    monkeypatch.delenv("LENS_CATALOG", raising=False)
    monkeypatch.delenv("ANAMNESIS_QUESTIONS", raising=False)
    monkeypatch.setattr(teleconsult, "DEFAULT_LENS_CATALOG", tmp_path / "none.json")
    monkeypatch.setattr(anamnesis, "DEFAULT_QUESTIONS_PATH", str(tmp_path / "none.json"))
    assert len(teleconsult.lens_options(None)) > 5
    bundled = json.loads(Path(anamnesis.BUNDLED_QUESTIONS_PATH).read_text(encoding="utf-8"))["aiTopics"]
    assert [t.id for t in anamnesis.load_topics()] == [t["id"] for t in bundled]


@pytest.mark.parametrize("name", ["lens_catalog.json", "anamnesis_questions.json"])
def test_bundled_copies_match_the_app_assets(name):
    if not (ASSETS / name).exists():
        pytest.skip("app assets are not here (backend-only checkout)")
    ours = json.loads((Path(teleconsult.__file__).parent / "data" / name).read_text(encoding="utf-8"))
    assert ours == json.loads((ASSETS / name).read_text(encoding="utf-8")), f"copy backend/app/data/{name} again from the app assets"


def test_render_blueprint_has_the_settings_and_no_secret_values():
    text = (REPO / "render.yaml").read_text(encoding="utf-8")
    for needed in ("rootDir: backend", "buildCommand: pip install -r requirements.txt",
                   "uvicorn app.main:app --host 0.0.0.0 --port $PORT", "--proxy-headers", "healthCheckPath: /health", "PYTHON_VERSION"):
        assert needed in text, needed
    lines = [ln.strip() for ln in text.splitlines()]
    for key in ("SARVAM_API_KEY", "ADMIN_EMAIL", "ADMIN_PASSWORD", "DEMO_PASSWORD", "DEMO_OPTOMETRIST_PHONE", "DEMO_PATIENT_PHONE", "BRIDGE_TOKEN"):
        i = lines.index(f"- key: {key}")
        assert lines[i + 1] == "sync: false", key                      # typed into the dashboard, never in git
    assert lines[lines.index("- key: JWT_SECRET") + 1] == "generateValue: true"
