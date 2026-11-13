"""Frame catalogue for the 3D try-on: metadata, GLB models and thumbnails.

The files are produced offline by tools/frames_to_glb.py into a folder holding frames_catalog.json,
glb/<sku>.glb and thumb/<sku>.jpg (git-ignored: the Kaggle source licence is unknown, demo use only).
The folder is CATALOG_DIR, or <repo>/generated/frames3d by default. A missing folder gives an empty catalogue.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Callable, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse

DEFAULT_DIR = Path(__file__).resolve().parents[2] / "generated" / "frames3d"
PRICE_BANDS = {"budget": (0, 999), "mid": (1000, 1999), "premium": (2000, 10 ** 9)}
SIZE_BANDS = {"small": (0, 49), "medium": (50, 53), "large": (54, 10 ** 3)}   # lens width A (mm)
FILTERS = ("category", "shape", "rim", "colour", "material")


def size_band(a_mm: float) -> str:
    for name, (lo, hi) in SIZE_BANDS.items():
        if lo <= round(a_mm) <= hi:
            return name
    return "large"


def price_band(price: int) -> str:
    for name, (lo, hi) in PRICE_BANDS.items():
        if lo <= price <= hi:
            return name
    return "premium"


class Catalog:
    def __init__(self, folder: Optional[str] = None) -> None:
        self.dir = Path(folder or os.environ.get("CATALOG_DIR") or DEFAULT_DIR)
        self._mtime = None
        self._frames: list[dict[str, Any]] = []

    def frames(self) -> list[dict[str, Any]]:
        f = self.dir / "frames_catalog.json"
        if not f.exists():
            return []
        m = f.stat().st_mtime
        if m != self._mtime:          # reload when the generator rewrites the file
            data = json.loads(f.read_text(encoding="utf-8"))
            frames = []
            for e in data.get("frames", []):
                e = dict(e)
                e["sizeBand"] = size_band(e["sizes"]["a"])
                e["priceBand"] = price_band(e["priceInr"])
                frames.append(e)
            self._frames, self._mtime = frames, m
        return self._frames

    def get(self, fid: str) -> dict[str, Any]:
        for e in self.frames():
            if e["id"] == fid:
                return e
        raise HTTPException(status_code=404, detail="no such frame")

    def file(self, rel: str) -> Path:
        p = (self.dir / rel).resolve()
        if self.dir.resolve() not in p.parents or not p.is_file():
            raise HTTPException(status_code=404, detail="file missing")
        return p


def public(e: dict[str, Any]) -> dict[str, Any]:
    out = {k: v for k, v in e.items() if k not in ("glb", "thumb")}
    out["glbUrl"] = f"/catalog/{e['id']}.glb"
    out["thumbUrl"] = f"/catalog/{e['id']}/thumb"
    return out


def router(catalog: Catalog, check_token: Callable[..., None]) -> APIRouter:
    r = APIRouter(prefix="/catalog", dependencies=[Depends(check_token)])

    @r.get("")
    def list_frames(
        category: Optional[str] = None, shape: Optional[str] = None, rim: Optional[str] = None,
        colour: Optional[str] = None, material: Optional[str] = None,
        price: Optional[str] = Query(None, description="budget | mid | premium"),
        size: Optional[str] = Query(None, description="small | medium | large (lens width)"),
        sort: str = Query("quality", pattern="^(quality|price|-price)$"),
        page: int = Query(1, ge=1), page_size: int = Query(30, ge=1, le=100),
    ) -> dict[str, Any]:
        want = {"category": category, "shape": shape, "rim": rim, "colour": colour, "material": material,
                "priceBand": price, "sizeBand": size}
        rows = [e for e in catalog.frames() if all(v is None or e.get(k) == v for k, v in want.items())]
        if sort == "price":
            rows.sort(key=lambda e: e["priceInr"])
        elif sort == "-price":
            rows.sort(key=lambda e: -e["priceInr"])
        else:
            rows.sort(key=lambda e: -e.get("quality", 0))
        start = (page - 1) * page_size
        facets = {k: sorted({e[k] for e in catalog.frames()}) for k in FILTERS}
        return {"total": len(rows), "page": page, "pageSize": page_size, "facets": facets,
                "licence": "Kaggle Glasses dataset: licence unknown, demo use only",
                "frames": [public(e) for e in rows[start:start + page_size]]}

    @r.get("/{fid}.glb")
    def glb(fid: str) -> FileResponse:
        return FileResponse(catalog.file(catalog.get(fid)["glb"]), media_type="model/gltf-binary",
                            headers={"Cache-Control": "public, max-age=86400"})

    @r.get("/{fid}/thumb")
    def thumb(fid: str) -> FileResponse:
        return FileResponse(catalog.file(catalog.get(fid)["thumb"]), media_type="image/jpeg",
                            headers={"Cache-Control": "public, max-age=86400"})

    @r.get("/{fid}")
    def meta(fid: str) -> dict[str, Any]:
        return public(catalog.get(fid))

    return r
