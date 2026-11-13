import json

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


def frame(sku, shape, colour, material, price, a, quality, category="eyeglasses"):
    return {
        "id": f"k-{sku}", "name": f"{colour} {shape}", "sku": sku, "category": category, "shape": shape,
        "rim": "full", "colour": colour, "material": material, "materialEstimated": True,
        "sizes": {"a": a, "dbl": 18, "temple": 140, "b": 38, "frontWidth": 135, "label": f"{a}-18-140"},
        "dimensionsSource": "estimated", "priceInr": price, "priceSource": "demo",
        "glb": f"glb/{sku}.glb", "thumb": f"thumb/{sku}.jpg", "source": "Kaggle photo-extruded",
        "anchors": {"origin": "bridge centre"}, "quality": quality,
    }


@pytest.fixture
def client(tmp_path):
    cat = tmp_path / "frames3d"
    (cat / "glb").mkdir(parents=True)
    (cat / "thumb").mkdir()
    frames = [frame("a1", "round", "black", "acetate", 799, 48, 0.99),
              frame("a2", "square", "gold", "metal", 1499, 52, 0.95),
              frame("a3", "round", "gold", "metal", 2299, 55, 0.90),
              frame("s1", "aviator", "black", "metal", 1899, 58, 0.97, "sunglasses")]
    (cat / "frames_catalog.json").write_text(json.dumps({"version": 1, "frames": frames}), encoding="utf-8")
    for f in frames:
        (cat / f["glb"]).write_bytes(b"glTF" + b"\0" * 16)
        (cat / f["thumb"]).write_bytes(b"\xff\xd8\xff")
    return TestClient(create_app(str(tmp_path / "t.db"), token="", catalog_dir=str(cat)))


def ids(r):
    return [f["id"] for f in r.json()["frames"]]


def test_list_sorted_by_quality_with_urls_and_facets(client):
    r = client.get("/catalog")
    assert r.status_code == 200
    assert ids(r) == ["k-a1", "k-s1", "k-a2", "k-a3"]
    f = r.json()["frames"][0]
    assert f["glbUrl"] == "/catalog/k-a1.glb" and f["thumbUrl"] == "/catalog/k-a1/thumb"
    assert "glb" not in f and f["dimensionsSource"] == "estimated"
    assert r.json()["facets"]["shape"] == ["aviator", "round", "square"]
    assert "demo use only" in r.json()["licence"]


def test_filters(client):
    assert ids(client.get("/catalog?shape=round")) == ["k-a1", "k-a3"]
    assert ids(client.get("/catalog?colour=gold&material=metal")) == ["k-a2", "k-a3"]
    assert ids(client.get("/catalog?category=sunglasses")) == ["k-s1"]
    assert ids(client.get("/catalog?price=budget")) == ["k-a1"]
    assert ids(client.get("/catalog?price=premium")) == ["k-a3"]
    assert ids(client.get("/catalog?size=small")) == ["k-a1"]
    assert ids(client.get("/catalog?size=large")) == ["k-s1", "k-a3"]
    assert ids(client.get("/catalog?sort=price")) == ["k-a1", "k-a2", "k-s1", "k-a3"]
    assert client.get("/catalog?sort=bogus").status_code == 422


def test_paging(client):
    r = client.get("/catalog?page=2&page_size=3")
    assert r.json()["total"] == 4 and ids(r) == ["k-a3"]


def test_meta_glb_thumb(client):
    assert client.get("/catalog/k-a2").json()["sizes"]["label"] == "52-18-140"
    g = client.get("/catalog/k-a2.glb")
    assert g.status_code == 200 and g.content.startswith(b"glTF")
    assert g.headers["content-type"] == "model/gltf-binary"
    assert client.get("/catalog/k-a2/thumb").headers["content-type"] == "image/jpeg"
    assert client.get("/catalog/nope").status_code == 404
    assert client.get("/catalog/nope.glb").status_code == 404


def test_missing_folder_is_an_empty_catalogue(tmp_path):
    c = TestClient(create_app(str(tmp_path / "t.db"), token="", catalog_dir=str(tmp_path / "none")))
    assert c.get("/catalog").json()["total"] == 0


def test_token_required_when_set(tmp_path):
    c = TestClient(create_app(str(tmp_path / "t.db"), token="s3cret", catalog_dir=str(tmp_path / "none")))
    assert c.get("/catalog").status_code == 401
    assert c.get("/catalog", headers={"X-Bridge-Token": "s3cret"}).status_code == 200
