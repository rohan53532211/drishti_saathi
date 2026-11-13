"""3D frames from the Kaggle product photos ("photo-extruded" hybrid), offline on the laptop.

Usage (run with `python -I` so nothing is imported from the image folder):
    python -I tools/frames_to_glb.py <glasses_root> <out_dir> [--eyeglasses N] [--sunglasses M] [--only sku1,sku2]
                                     [--bundle K --bundle-dir <app assets dir>]

<glasses_root> holds eyeglasses/<folder>/product/<sku>_0.jpg (front) and <sku>_2.jpg (side) and the same
under sunglasses/. For every product:
  1. Cut out the front view from its white background (same colour-distance idea as frames_cutout.py),
     find the outer contour and the lens openings (enclosed background; per side the convex hull of the
     large holes, which also removes temples seen through clear lenses). Implausible shapes are rejected
     and the reason is logged.
  2. The front (outer contour minus the lens openings) is extruded to 4 mm. Units are mm, origin at the
     bridge centre, +X = wearer's right, +Y up, +Z towards the face (so the viewer looks along +Z).
     The cut-out photo is UV-mapped onto it (planar projection) as the texture.
  3. Lenses: separate thin transparent parts filling the openings (clear; tinted for sunglasses).
  4. Temples: separate nodes hinged at the outer edges (node origin = hinge), 140 mm, colour sampled from
     the side view, with a fixed downward ear bend. The app swings them about Y to the head width.
  5. Scale: no measured sizes for Kaggle products, so the total front width is set to 135 mm and the
     entry says dimensionsSource "estimated". Sizes A-DBL-temple are derived from that.
  6. Auto-tags: shape, rim, colour, material (estimated from the rim thickness).
  7. One GLB per frame (JPEG texture, typically 40-120 KB) + thumbnail + frames_catalog.json entry.
  8. parametric_glb() builds a plain frame from A-DBL-temple alone, for frames without a usable photo.

The Kaggle licence is unknown: everything generated stays git-ignored (demo use only).
Needs opencv-python-headless, shapely, trimesh, pillow, numpy.
"""
import argparse
import glob
import io
import json
import math
import os
import sys

import cv2
import numpy as np
import trimesh
from PIL import Image
from shapely import affinity
from shapely.geometry import Polygon, MultiPolygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

WORK_W = 1200
ALPHA_T0, ALPHA_T1 = 7.0, 34.0
MASK_T = 14.0
MIN_HOLE_FRAC = 0.004
TOTAL_WIDTH_MM = 135.0
FRONT_DEPTH_MM = 4.0
LENS_DEPTH_MM = 1.2
TEMPLE_MM = 140.0
EAR_BEND_MM, EAR_BEND_DEG = 35.0, 28.0
TEX_MAX_W = 768
THUMB_W = 320
SHADOW_MAX_CHROMA, SHADOW_MAX_DARKNESS = 9.0, 110

PRICE_BANDS = {  # placeholder demo prices (INR) by material/category, marked "demo"
    ("eyeglasses", "metal"): (899, 2499), ("eyeglasses", "acetate"): (699, 1999),
    ("sunglasses", "metal"): (1299, 2999), ("sunglasses", "acetate"): (999, 2499),
}


# ---------------------------------------------------------------- cut-out

def colour_distance(a):
    b = np.concatenate([a[:6].reshape(-1, 3), a[-6:].reshape(-1, 3), a[:, :6].reshape(-1, 3), a[:, -6:].reshape(-1, 3)])
    bg = np.median(b, axis=0)
    darker = np.maximum(bg - a, 0)
    chroma = (a - a.mean(2, keepdims=True)) - (bg - bg.mean())
    return bg, np.sqrt((darker ** 2).sum(2) + (chroma ** 2).sum(2)), np.sqrt((chroma ** 2).sum(2))


def load_work(path):
    src = Image.open(path).convert('RGB')
    s = WORK_W / src.width
    return np.asarray(src.resize((WORK_W, round(src.height * s)), Image.LANCZOS)).astype(np.float32)


def clean_mask(m, close_r, min_px=200):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * close_r + 1, 2 * close_r + 1))
    c = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_CLOSE, k)
    n, lab, st, _ = cv2.connectedComponentsWithStats(c, 4)
    keep = np.zeros(n, bool)
    keep[1:] = st[1:, cv2.CC_STAT_AREA] > min_px
    return keep[lab]


class Reject(Exception):
    pass


def find_parts(closed):
    """Largest outer contour + lens hulls (per side) + midline bridge holes, in work px."""
    h, w = closed.shape
    cs, hier = cv2.findContours(closed.astype(np.uint8), cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    if not cs:
        raise Reject('empty cut-out')
    hier = hier[0]
    outer = [i for i in range(len(cs)) if hier[i][3] == -1]
    oi = max(outer, key=lambda i: cv2.contourArea(cs[i]))
    ox, oy, ow, oh = cv2.boundingRect(cs[oi])
    mid = ox + ow / 2
    holes = {'L': [], 'R': []}
    mids = []
    for i in range(len(cs)):
        if hier[i][3] != oi:
            continue
        area = cv2.contourArea(cs[i])
        if area < MIN_HOLE_FRAC * h * w:
            continue
        m = cv2.moments(cs[i])
        cx = m['m10'] / max(m['m00'], 1e-6)
        if abs(cx - mid) < 0.06 * ow:
            mids.append(cs[i][:, 0, :])
            continue
        holes['L' if cx < mid else 'R'].append((area, cs[i][:, 0, :]))
    lens = {}
    for side in 'LR':
        hs = sorted(holes[side], key=lambda t: -t[0])
        if not hs:
            raise Reject(f'no lens opening on the {side} side')
        pts = np.concatenate([c for a, c in hs if a >= 0.03 * hs[0][0]])
        lens[side] = cv2.convexHull(pts)[:, 0, :].astype(np.float64)
    return cs[oi][:, 0, :].astype(np.float64), lens, mids, (ox, oy, ow, oh)


def find_parts_opaque(closed):
    """Sunglasses: tinted lenses are not see-through, so there are no holes. The lens is the filled
    silhouette shrunk by a rim width, split at the bridge."""
    cs, _ = cv2.findContours(closed.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cs:
        raise Reject('empty cut-out')
    c = max(cs, key=cv2.contourArea)
    ox, oy, ow, oh = cv2.boundingRect(c)
    filled = np.zeros(closed.shape, np.uint8)
    cv2.fillPoly(filled, [c], 1)
    r = max(4, int(round(0.022 * ow)))
    inner = cv2.erode(filled, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1)))
    mid = ox + ow / 2
    inner[:, int(mid - 0.05 * ow):int(mid + 0.05 * ow) + 1] = 0
    n, lab, st, cen = cv2.connectedComponentsWithStats(inner, 4)
    lens = {}
    for side in 'LR':
        idx = [i for i in range(1, n) if (cen[i][0] < mid) == (side == 'L')]
        if not idx:
            raise Reject(f'no opaque lens on the {side} side')
        i = max(idx, key=lambda i: st[i, cv2.CC_STAT_AREA])
        ys, xs = np.nonzero(lab == i)
        lens[side] = cv2.convexHull(np.stack([xs, ys], 1).astype(np.int32))[:, 0, :].astype(np.float64)
    return c[:, 0, :].astype(np.float64), lens, [], (ox, oy, ow, oh)


def poly_of(pts):
    p = Polygon(pts)
    return p if p.is_valid else p.buffer(0)


def mirror_iou(lp, rp, mid):
    lm = affinity.scale(lp, xfact=-1, yfact=1, origin=(mid, 0))
    inter = lm.intersection(rp).area
    return inter / max(lm.union(rp).area, 1e-6)


def run_len(closed, y, x, dy, dx, skip_max=5):
    h, w = closed.shape
    skip = 0
    while 0 <= y < h and 0 <= x < w and not closed[y, x] and skip <= skip_max:
        y, x, skip = y + dy, x + dx, skip + 1
    n = 0
    while 0 <= y < h and 0 <= x < w and closed[y, x]:
        y, x, n = y + dy, x + dx, n + 1
    return n


def analyse_front(path, opaque=False):
    a = load_work(path)
    h, w, _ = a.shape
    bg, dist, chroma = colour_distance(a)
    fg = dist > MASK_T
    close_used = None
    parts_of = find_parts_opaque if opaque else find_parts
    for r in (3, 6, 10):              # a bigger closing seals half rims (nylon cord) and thin metal
        closed = clean_mask(fg, r)
        try:
            outer, lens, mids, box = parts_of(closed)
            close_used = r
            break
        except Reject as e:
            err = e
    if close_used is None:
        raise err
    t = []
    for side, hull in lens.items():
        cx = int(hull[:, 0].mean())
        t.append(np.median([run_len(closed, int(hull[:, 1].min()) - 1, cx + k, -1, 0) for k in (-10, -5, 0, 5, 10)]))
    rim = max(1.0, float(min(t)))
    # contact shadow on the floor under the rims: per column, the lowest frame pixels that are neutral grey,
    # not too dark and clearly unlike the rim colour are stripped (only below the lens centres).
    lens_fill = np.zeros((h, w), np.uint8)
    for hull in lens.values():
        cv2.fillPoly(lens_fill, [hull.astype(np.int32)], 1)
    rim_rgb = np.median(a[closed & ~lens_fill.astype(bool)], 0)
    cand = ((chroma < SHADOW_MAX_CHROMA) & ((bg.mean() - a.mean(2)) < SHADOW_MAX_DARKNESS)
            & (np.linalg.norm(a - rim_rgb, axis=2) > 40))
    cand[:int(np.mean([hh[:, 1].mean() for hh in lens.values()]))] = False
    ok = (cand & closed) | ~closed
    strip = np.flipud(np.cumprod(np.flipud(ok), axis=0)).astype(bool) & closed
    if strip.any():
        closed = clean_mask(fg & ~strip, close_used)
        outer, lens, mids, box = parts_of(closed)
    ox, oy, ow, oh = box
    if not opaque:
        # Clean the bottom edge: grey floor shadow glued under a silver or clear rim is a flat band that sticks
        # out below the curved lens bottom. Below each lens, neutral light pixels further than the side-rim
        # thickness from the lens edge are removed (black and coloured rims are never touched).
        trim = np.zeros((h, w), bool)
        tips = np.zeros((h, w), bool)
        neutral_light = (chroma < SHADOW_MAX_CHROMA) & ((bg.mean() - a.mean(2)) < SHADOW_MAX_DARKNESS)
        for side, hull in lens.items():
            fill = np.zeros((h, w), np.uint8)
            cv2.fillPoly(fill, [hull.astype(np.int32)], 1)
            cy = int(hull[:, 1].mean())
            side_rim = (run_len(closed, cy, int(hull[:, 0].min()) - 1, 0, -1) if side == 'L'
                        else run_len(closed, cy, int(hull[:, 0].max()) + 1, 0, 1))
            allow = 1.3 * max(side_rim, 2) + 1
            x0, x1 = int(hull[:, 0].min()), int(hull[:, 0].max())
            for x in range(x0, x1 + 1):
                lb = np.flatnonzero(fill[:, x])
                if len(lb):
                    trim[int(lb[-1] + 1 + allow):, x] = True
            # the band also sticks out sideways past the lens: below the lens centre, outside the lens columns
            ext = np.zeros((h, w), bool)
            ext[int(hull[:, 1].max() - allow):, max(0, x0 - 60):x0] = True
            ext[int(hull[:, 1].max() - allow):, x1 + 1:min(w, x1 + 61)] = True
            trim |= ext & ~(cv2.dilate(fill, np.ones((3, 3), np.uint8), iterations=int(allow) + 1).astype(bool))
            # thick rims: thin shadow tips poking out at the bottom corners are removed by an opening
            r = int(0.35 * side_rim)
            if r >= 2:
                k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
                opened = cv2.morphologyEx(closed.astype(np.uint8), cv2.MORPH_OPEN, k).astype(bool)
                zone = np.zeros((h, w), bool)
                zone[int(hull[:, 1].mean()):, max(0, x0 - 80):min(w, x1 + 80)] = True
                tips |= zone & closed & ~opened
        for t in (trim, tips):
            t &= neutral_light & closed
            if t.any():
                try:     # if a trim opens a rim, it is skipped (the shadow check below decides)
                    c2 = clean_mask(closed & ~t, 1)
                    outer, lens, mids, box = parts_of(c2)
                    closed = c2
                except Reject:
                    outer, lens, mids, box = parts_of(closed)
        ox, oy, ow, oh = box
        # a floor shadow the colour test could not strip (grey shadow under a silver or clear rim) lies flat,
        # while the lens bottom curves up: the material under the lens gets much thicker than the rim.
        for side, hull in lens.items():
            fill = np.zeros((h, w), np.uint8)
            cv2.fillPoly(fill, [hull.astype(np.int32)], 1)
            x0, x1 = int(hull[:, 0].min()), int(hull[:, 0].max())
            thick = []
            for x in range(int(x0 + 0.15 * (x1 - x0)), int(x1 - 0.15 * (x1 - x0)), 3):
                lb = np.flatnonzero(fill[:, x])
                if len(lb):
                    thick.append(run_len(closed, lb[-1] + 1, x, 1, 0, skip_max=3))
            # a flat shadow tab left under the rim shows as a sudden step in the bottom edge
            bottoms = []
            for x in range(int(x0 + 0.05 * (x1 - x0)), int(x1 - 0.05 * (x1 - x0))):
                col = np.flatnonzero(closed[int(hull[:, 1].mean()):, x])
                bottoms.append(col[-1] if len(col) else np.nan)
            b = np.asarray(bottoms, float)
            d = np.abs(np.diff(b))
            lim = max(4.0, 0.3 * rim)
            for i in range(6, len(d) - 6):      # an isolated jump with a flat edge on both sides
                if d[i] > lim and np.nanmedian(d[i - 6:i]) <= 1 and np.nanmedian(d[i + 1:i + 7]) <= 1:
                    raise Reject(f'floor shadow tab under the bottom rim (step {d[i]:.0f} px)')
            if thick and max(thick) > 2.0 * rim + 4:
                raise Reject(f'floor shadow under the bottom rim ({max(thick)} px vs rim {rim:.0f} px)')
    L, R = poly_of(lens['L']), poly_of(lens['R'])
    lw = (L.bounds[2] - L.bounds[0] + R.bounds[2] - R.bounds[0]) / 2
    lh = (L.bounds[3] - L.bounds[1] + R.bounds[3] - R.bounds[1]) / 2
    mid = (L.bounds[2] + R.bounds[0]) / 2
    checks = {
        'lens area ratio': (min(L.area, R.area) / max(L.area, R.area), 0.75, 1.0),
        'lens height ratio': (min(L.bounds[3] - L.bounds[1], R.bounds[3] - R.bounds[1]) /
                              max(L.bounds[3] - L.bounds[1], R.bounds[3] - R.bounds[1]), 0.85, 1.0),
        'lens aspect': (lw / lh, 0.85, 2.7),
        'front aspect': (ow / oh, 1.7, 4.8),
        'lens share of front': ((L.area + R.area) / (ow * oh), 0.18, 0.80),
        'bridge share': ((R.bounds[0] - L.bounds[2]) / ow, 0.04, 0.30),
    }
    for k, (v, lo, hi) in checks.items():
        if not lo <= v <= hi:
            raise Reject(f'{k} {v:.2f} outside {lo}-{hi}')
    iou = mirror_iou(L, R, mid)
    if iou < 0.70:
        raise Reject(f'left/right lenses not mirror images (IoU {iou:.2f})')
    # alpha + un-mixed colour for the texture
    alpha = np.clip((dist - ALPHA_T0) / (ALPHA_T1 - ALPHA_T0), 0, 1)
    al = np.clip(alpha, 1e-3, 1)[..., None]
    rgb = np.where(alpha[..., None] > 0, np.clip((a - (1 - al) * bg) / al, 0, 255), a)
    lens_mask = np.zeros((h, w), np.uint8)
    for hull in lens.values():
        cv2.fillPoly(lens_mask, [hull.astype(np.int32)], 1)
    return dict(a=a, rgb=rgb, alpha=alpha, closed=closed, outer=outer, lens=lens, mids=mids, box=box,
                mid=mid, lensW=lw, lensH=lh, rimPx=rim, iou=iou, closeR=close_used, lensMask=lens_mask.astype(bool),
                shape=(h, w), opaque=opaque)


# ---------------------------------------------------------------- tags

def lens_shape(hull, side):
    p = poly_of(hull)
    x0, y0, x1, y1 = p.bounds
    w, h = x1 - x0, y1 - y0
    per = p.length
    circ = 4 * math.pi * p.area / per ** 2
    rect = p.area / (w * h)
    approx = cv2.approxPolyDP(hull.astype(np.float32).reshape(-1, 1, 2), 0.035 * per, True)

    def width_at(fy):
        line = Polygon([(x0 - 1, y0 + fy * h - 0.5), (x1 + 1, y0 + fy * h - 0.5),
                        (x1 + 1, y0 + fy * h + 0.5), (x0 - 1, y0 + fy * h + 0.5)])
        return p.intersection(line).bounds[2] - p.intersection(line).bounds[0] if p.intersects(line) else 0

    top, bot = width_at(0.25), width_at(0.75)
    # where the lens reaches its top: outer side (cat-eye lift) or the middle
    ys = hull[:, 1]
    top_pts = hull[ys < y0 + 0.08 * h]
    top_x = (top_pts[:, 0].mean() - x0) / w if len(top_pts) else 0.5
    outer_top = (1 - top_x) if side == 'L' else top_x      # 1 = topmost point at the temple side
    if circ > 0.86 and w / h < 1.18:
        return 'round'
    if bot > 1.06 * top and h / w > 0.72:
        return 'aviator'
    if top > 1.12 * bot and outer_top > 0.62:
        return 'cat-eye'
    if 5 <= len(approx) <= 8 and rect < 0.86 and circ < 0.86:
        return 'geometric'
    if rect > 0.86:
        return 'rectangle' if w / h > 1.35 else 'square'
    return 'oval'


COLOURS = [  # (name, rgb)
    ('black', (25, 25, 28)), ('grey', (120, 120, 125)), ('silver', (185, 185, 190)), ('white', (235, 235, 230)),
    ('gold', (190, 160, 90)), ('brown', (100, 65, 40)), ('tortoise', (120, 75, 35)), ('red', (170, 35, 40)),
    ('orange', (215, 100, 50)), ('beige', (205, 180, 140)), ('pink', (220, 140, 160)), ('purple', (110, 60, 130)), ('blue', (40, 70, 150)), ('green', (50, 110, 70)),
    ('clear', (215, 215, 210)),
]


def colour_name(pixels):
    med = np.median(pixels, 0)
    spread = pixels.std(0).mean()
    lab = lambda c: cv2.cvtColor(np.uint8([[c]]), cv2.COLOR_RGB2LAB)[0, 0].astype(float)
    m = lab(np.clip(med, 0, 255))
    best = min(COLOURS, key=lambda c: np.linalg.norm(lab(c[1]) - m))[0]
    if best in ('brown', 'tortoise', 'gold') and spread > 38:
        best = 'tortoise'
    elif best == 'tortoise':
        best = 'brown'
    return best, [int(v) for v in med]


def side_colour(path):
    if not os.path.exists(path):
        return None
    a = load_work(path)
    _, dist, _ = colour_distance(a)
    m = clean_mask(dist > 30, 2)
    if m.sum() < 500:
        return None
    return [int(v) for v in np.median(a[m], 0)]


# ---------------------------------------------------------------- geometry

def to_mm(geom, ox, oy, s):
    """work px (x right, y down) -> mm (X = wearer's right = image left, Y up), origin (ox, oy)."""
    g = affinity.translate(geom, -ox, -oy)
    return affinity.scale(g, xfact=-s, yfact=-s, origin=(0, 0))


def extrude(poly_mm, depth, z0):
    parts = list(poly_mm.geoms) if isinstance(poly_mm, MultiPolygon) else [poly_mm]
    ms = []
    for p in parts:
        if p.area < 1.0:
            continue
        m = trimesh.creation.extrude_polygon(orient(p, 1.0), depth)
        m.apply_translation([0, 0, z0])
        ms.append(m)
    return trimesh.util.concatenate(ms)


def bar(p0, p1, wx, wy, round_=False):
    """A box (or a round rod of diameter wx) from p0 to p1 (both in the YZ plane, x = 0)."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    d = p1 - p0
    L = np.linalg.norm(d)
    b = trimesh.creation.cylinder(radius=wx / 2, height=L, sections=10) if round_ else trimesh.creation.box([wx, wy, L])
    ang = math.atan2(-d[1], d[2])                 # rotate +Z towards d (about X)
    T = trimesh.transformations.rotation_matrix(ang, [1, 0, 0])
    T[:3, 3] = (p0 + p1) / 2
    b.apply_transform(T)
    return b


def temple_mesh(length, wx, wy, round_=False):
    """Temple in hinge coordinates: straight back along +Z, then a fixed ear bend downwards."""
    straight = length - EAR_BEND_MM
    a = math.radians(EAR_BEND_DEG)
    end = [0, -EAR_BEND_MM * math.sin(a), straight + EAR_BEND_MM * math.cos(a)]
    parts = [bar([0, 0, 0], [0, 0, straight], wx, wy, round_), bar([0, 0, straight], end, wx, wy, round_)]
    if round_:   # a ball at the knee so the round rod has no gap
        parts.append(trimesh.creation.icosphere(1, radius=wx / 2).apply_translation([0, 0, straight]))
    return trimesh.util.concatenate(parts), end


def material(rgba, texture=None, blend=False, metal=0.0, rough=0.5):
    return trimesh.visual.material.PBRMaterial(
        baseColorFactor=[int(c) for c in rgba], baseColorTexture=texture,
        metallicFactor=metal, roughnessFactor=rough, alphaMode='BLEND' if blend else 'OPAQUE',
        doubleSided=blend)


def fill_background(rgb, mask):
    """Push-pull fill so texture filtering never pulls in white at the frame edges."""
    out = rgb.copy()
    known = mask.astype(np.float32)
    for sig in (2, 6, 18, 60):
        num = cv2.GaussianBlur(rgb * known[..., None], (0, 0), sig)
        den = cv2.GaussianBlur(known, (0, 0), sig)[..., None]
        fill = (den[..., 0] > 0.02) & (known == 0)
        out[fill] = (num / np.maximum(den, 1e-6))[fill]
        known = np.maximum(known, fill.astype(np.float32))
        rgb = out
    return out


def jpeg(img_arr, max_w, quality=85):
    im = Image.fromarray(np.clip(img_arr, 0, 255).astype(np.uint8))
    if im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, 'JPEG', quality=quality)
    buf.seek(0)
    return Image.open(buf)    # format == JPEG: trimesh embeds it without re-encoding


def analyse_category(path, category):
    try:
        return analyse_front(path)
    except Reject:
        if category != 'sunglasses':
            raise
        return analyse_front(path, opaque=True)


def build(sku, front_path, side_path, category):
    f = analyse_category(front_path, category)
    h, w = f['shape']
    ox, oy, ow, oh = f['box']
    s = TOTAL_WIDTH_MM / ow
    mid = f['mid']
    closed = f['closed']
    # bridge: frame material on the midline between the lens tops
    col = closed[:, int(round(mid))]
    ys = np.flatnonzero(col[oy:oy + oh]) + oy
    if len(ys):
        y0 = ys[0]
        y1 = y0
        while y1 + 1 < h and col[y1 + 1]:
            y1 += 1
    else:
        top = min(hh[:, 1].min() for hh in f['lens'].values())
        y0, y1 = top, top + f['rimPx']
    bridge_y = (y0 + y1) / 2
    org = (mid, bridge_y)

    o = f['outer']
    k = 5                                     # circular moving average removes the pixel staircase
    o = np.stack([np.convolve(np.concatenate([o[-k:, i], o[:, i], o[:k, i]]), np.ones(2 * k + 1) / (2 * k + 1), 'valid')
                  for i in (0, 1)], 1)
    outer = poly_of(cv2.approxPolyDP(o.astype(np.float32).reshape(-1, 1, 2), 0.8, True)[:, 0, :])
    holes = [poly_of(hh) for hh in f['lens'].values()] + [poly_of(m) for m in f['mids'] if len(m) >= 3]
    front_px = outer.difference(unary_union(holes)).buffer(0)
    front_px = front_px.simplify(0.8)
    front_mm = to_mm(front_px, *org, s)
    front = extrude(front_mm, FRONT_DEPTH_MM, 0.0)
    # planar UV from the photo
    tx0, ty0 = ox - 4, oy - 4
    tw, th = ow + 8, oh + 8
    def planar_uv(verts):
        vx = -verts[:, 0] / s + org[0]
        vy = -verts[:, 1] / s + org[1]
        return np.stack([(vx - tx0) / tw, 1 - (vy - ty0) / th], 1)

    frame_mask = closed & (f['alpha'] > 0.25) & (True if f['opaque'] else ~f['lensMask'])
    tex = fill_background(f['rgb'], frame_mask)[max(0, ty0):ty0 + th, max(0, tx0):tx0 + tw]
    tex_img = jpeg(tex, TEX_MAX_W)
    frame_px = f['rgb'][frame_mask]
    colour, rgb_med = colour_name(frame_px)
    rim_mm = f['rimPx'] * s
    shape = lens_shape(f['lens']['R'], 'R')
    if f['opaque']:     # the rim of a tinted lens can't be measured from the silhouette: guess from the shape
        material_name = 'metal' if shape == 'aviator' else 'acetate'
    else:
        material_name = 'acetate' if rim_mm > 2.2 else 'metal'
    is_metal = material_name == 'metal'
    front_mat = material([255, 255, 255, 255], tex_img, metal=0.6 if is_metal else 0.0, rough=0.35 if is_metal else 0.45)

    scene = trimesh.Scene()
    # caps carry the photo; the 4 mm side walls get the rim colour (a planar photo would streak there)
    cap = np.abs(front.face_normals[:, 2]) > 0.5
    edge = front.submesh([np.flatnonzero(~cap)], append=True)
    front = front.submesh([np.flatnonzero(cap)], append=True)
    front.visual = trimesh.visual.TextureVisuals(uv=planar_uv(front.vertices), material=front_mat)
    edge.visual = trimesh.visual.TextureVisuals(material=material(
        [*[int(c * 0.85) for c in rgb_med], 255], metal=0.6 if is_metal else 0.0))
    scene.add_geometry(front, node_name='frame_front', geom_name='frame_front')
    scene.add_geometry(edge, node_name='frame_edge', geom_name='frame_edge')
    # contact shadow (MEGANE idea, simplified): a soft dark copy of the front on the skin just behind it,
    # a little lower (light from above). The app hides it in the frame viewer, where there is no face.
    sh_mask = (closed | (f['lensMask'] if f['opaque'] else np.zeros_like(closed))).astype(np.float32)
    sh = cv2.GaussianBlur(sh_mask, (0, 0), max(1.0, 1.8 / s))[max(0, ty0):ty0 + th, max(0, tx0):tx0 + tw]
    sh_img = Image.fromarray(np.dstack([np.zeros(sh.shape + (3,), np.uint8), np.clip(sh * 120, 0, 255).astype(np.uint8)]), 'RGBA')
    sh_img = sh_img.resize((256, max(1, round(sh_img.height * 256 / sh_img.width))), Image.LANCZOS)
    buf = io.BytesIO()
    sh_img.save(buf, 'PNG', optimize=True)
    buf.seek(0)
    sh_png = Image.open(buf)
    qx = [(-(tx0 + tw) + org[0]) * s, (-tx0 + org[0]) * s]          # mm x of texture right / left edge
    qy = [-(ty0 + th - org[1]) * s - 2.5, -(ty0 - org[1]) * s - 2.5]  # mm y of bottom / top, shifted down
    zq = FRONT_DEPTH_MM + 8.0
    quad = trimesh.Trimesh(vertices=[[qx[0], qy[0], zq], [qx[1], qy[0], zq], [qx[1], qy[1], zq], [qx[0], qy[1], zq]],
                           faces=[[0, 2, 1], [0, 3, 2]], process=False)
    quad.visual = trimesh.visual.TextureVisuals(uv=planar_uv(np.array(quad.vertices) + [0, 2.5, 0]),
                                                material=material([0, 0, 0, 255], sh_png, blend=True, rough=1.0))
    scene.add_geometry(quad, node_name='contact_shadow', geom_name='contact_shadow')
    # lenses
    if category == 'sunglasses':
        lens_rgb = np.median(f['a'][f['lensMask']], 0)
        lens_rgba = [*[int(c * 0.8) for c in lens_rgb], 200]
    else:
        lens_rgba = [225, 235, 245, 40]
    lens_info = {}
    for side, hull in f['lens'].items():
        p = to_mm(poly_of(hull), *org, s).buffer(0.4)      # tuck the lens edge into the rim groove
        lm = extrude(p, LENS_DEPTH_MM, (FRONT_DEPTH_MM - LENS_DEPTH_MM) / 2)
        if f['opaque']:     # tinted lens: the photo shows its gradient, keep it slightly see-through
            lm.visual = trimesh.visual.TextureVisuals(uv=planar_uv(lm.vertices), material=material(
                [255, 255, 255, 225], tex_img, blend=True, rough=0.05))
        else:
            lm.visual = trimesh.visual.TextureVisuals(material=material(lens_rgba, blend=True, rough=0.05))
        name = 'lens_R' if side == 'L' else 'lens_L'           # image left = wearer's right
        scene.add_geometry(lm, node_name=name, geom_name=name)
        c = p.centroid
        lens_info[name] = [round(c.x, 2), round(c.y, 2)]
    # temples, hinged at the outer edges, slightly below the lens tops
    tcol = side_colour(side_path) or rgb_med
    wx, wy = (2.0, 2.0) if is_metal else (3.0, 4.5)     # metal: 2 mm round rod; acetate: 3 wide x 4.5 tall
    temple, end = temple_mesh(TEMPLE_MM, wx, wy, round_=is_metal)
    fx0, fy0, fx1, fy1 = front_mm.bounds
    lens_top = max(to_mm(poly_of(hh), *org, s).bounds[3] for hh in f['lens'].values())
    hinge_y = lens_top - 0.25 * f['lensH'] * s
    hinges = {}
    for name, x in (('temple_R', fx1 - wx / 2 - 0.5), ('temple_L', fx0 + wx / 2 + 0.5)):
        t = temple.copy()
        t.visual = trimesh.visual.TextureVisuals(material=material([*tcol, 255], metal=0.6 if is_metal else 0.0))
        T = np.eye(4)
        T[:3, 3] = [x, hinge_y, FRONT_DEPTH_MM]
        scene.add_geometry(t, node_name=name, geom_name=name, transform=T)
        hinges[name] = [round(x, 2), round(hinge_y, 2), FRONT_DEPTH_MM]
    meta = dict(
        frontWidthMm=round(fx1 - fx0, 2), frontHeightMm=round(fy1 - fy0, 2),
        lensWidthMm=round(f['lensW'] * s, 1), lensHeightMm=round(f['lensH'] * s, 1),
        bridgeMm=round((poly_of(f['lens']['R']).bounds[0] - poly_of(f['lens']['L']).bounds[2]) * s, 1),
        rimMm=round(rim_mm, 2), noseRestMm=[0.0, round(-(y1 - bridge_y) * s, 2), 0.0],
        lensCentresMm=lens_info, hingesMm=hinges, templeEndLocalMm=[0.0, round(end[1], 2), round(end[2], 2)],
        shape=shape, rim='half' if f['closeR'] >= 10 else 'full',
        colour=colour, colourRgb=rgb_med, material=material_name, quality=round(f['iou'], 3),
    )
    return scene, meta, f


def parametric_glb(a_mm=52, dbl_mm=18, temple_mm=140, b_mm=None, rgb=(30, 30, 30)):
    """Fallback for frames without a usable photo: rounded-rectangle rims from A-DBL-temple only."""
    b_mm = b_mm or round(a_mm * 0.75)
    rim = 3.0
    cx = (a_mm + dbl_mm) / 2
    lens = [affinity.translate(Polygon([(-a_mm / 2, -b_mm / 2), (a_mm / 2, -b_mm / 2), (a_mm / 2, b_mm / 2),
                                        (-a_mm / 2, b_mm / 2)]).buffer(-6).buffer(6), x, 0) for x in (-cx, cx)]
    rims = unary_union([l.buffer(rim) for l in lens])
    bridge = Polygon([(-cx + a_mm / 2, b_mm / 2 - 8), (cx - a_mm / 2, b_mm / 2 - 8),
                      (cx - a_mm / 2, b_mm / 2 - 3), (-cx + a_mm / 2, b_mm / 2 - 3)]).buffer(1)
    front2d = unary_union([rims, bridge]).difference(unary_union(lens))
    off = b_mm / 2 - 5.5
    front2d = affinity.translate(front2d, 0, -off)
    scene = trimesh.Scene()
    fm = extrude(front2d, FRONT_DEPTH_MM, 0)
    fm.visual = trimesh.visual.TextureVisuals(material=material([*rgb, 255]))
    scene.add_geometry(fm, node_name='frame_front', geom_name='frame_front')
    for name, l in (('lens_R', lens[1]), ('lens_L', lens[0])):
        lm = extrude(affinity.translate(l.buffer(0.4), 0, -off), LENS_DEPTH_MM, 1.4)
        lm.visual = trimesh.visual.TextureVisuals(material=material([225, 235, 245, 40], blend=True, rough=0.05))
        scene.add_geometry(lm, node_name=name, geom_name=name)
    t, _ = temple_mesh(temple_mm, 3.0, 4.5)
    x0, _, x1, _ = front2d.bounds
    for name, x in (('temple_R', x1 - 1.6), ('temple_L', x0 + 1.6)):
        tt = t.copy()
        tt.visual = trimesh.visual.TextureVisuals(material=material([*rgb, 255]))
        T = np.eye(4)
        T[:3, 3] = [x, b_mm / 2 - off - 0.25 * b_mm, FRONT_DEPTH_MM]
        scene.add_geometry(tt, node_name=name, geom_name=name, transform=T)
    return scene


# ---------------------------------------------------------------- catalogue

def thumbnail(f, path):
    ox, oy, ow, oh = f['box']
    a = f['alpha'].copy()
    if not f['opaque']:
        a[f['lensMask']] = 0
    a[~clean_mask(f['closed'], 1)] = 0
    rgba = np.dstack([np.clip(f['rgb'], 0, 255), a * 255]).astype(np.uint8)[max(0, oy - 6):oy + oh + 6, max(0, ox - 6):ox + ow + 6]
    im = Image.fromarray(rgba, 'RGBA')
    im = im.resize((THUMB_W, max(1, round(im.height * THUMB_W / im.width))), Image.LANCZOS)
    bg = Image.new('RGB', im.size, (255, 255, 255))
    bg.paste(im, (0, 0), im)
    bg.save(path, 'JPEG', quality=82)


def entry(sku, category, meta, glb_rel, thumb_rel, index):
    lo, hi = PRICE_BANDS[(category, meta['material'])]
    price = int(round((lo + (index * 137) % (hi - lo)) / 50.0) * 50 - 1)
    a, dbl = round(meta['lensWidthMm']), round(meta['bridgeMm'])
    return dict(
        id=f'k-{sku}', name=f"{meta['colour'].title()} {meta['shape']} {meta['material']}", sku=sku,
        category=category, shape=meta['shape'], rim=meta['rim'], colour=meta['colour'], material=meta['material'],
        materialEstimated=True,
        sizes=dict(a=a, dbl=dbl, temple=int(TEMPLE_MM), b=round(meta['lensHeightMm']),
                   frontWidth=round(meta['frontWidthMm']), label=f'{a}-{dbl}-{int(TEMPLE_MM)}'),
        dimensionsSource='estimated', priceInr=price, priceSource='demo',
        glb=glb_rel, thumb=thumb_rel, source='Kaggle photo-extruded',
        anchors=dict(origin='bridge centre', noseRestMm=meta['noseRestMm'], lensCentresMm=meta['lensCentresMm'],
                     hingesMm=meta['hingesMm'], templeEndLocalMm=meta['templeEndLocalMm'], units='mm',
                     axes='+X wearer right, +Y up, +Z towards the face'),
        quality=meta['quality'],
    )


def products(root, category):
    out = []
    for p in sorted(glob.glob(os.path.join(root, category, '*', 'product', '*_0.jpg'))):
        sku = os.path.basename(p)[:-len('_0.jpg')]
        out.append((sku, p, p[:-len('_0.jpg')] + '_2.jpg'))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('root')
    ap.add_argument('out')
    ap.add_argument('--eyeglasses', type=int, default=300)
    ap.add_argument('--sunglasses', type=int, default=50)
    ap.add_argument('--only', default='')
    ap.add_argument('--scan', type=int, default=0, help='max products analysed per category (0 = all)')
    a = ap.parse_args()
    only = set(filter(None, a.only.split(',')))
    os.makedirs(os.path.join(a.out, 'glb'), exist_ok=True)
    os.makedirs(os.path.join(a.out, 'thumb'), exist_ok=True)
    log = open(os.path.join(a.out, 'rejected.log'), 'w', encoding='utf-8')
    catalog = []
    for category, want in (('eyeglasses', a.eyeglasses), ('sunglasses', a.sunglasses)):
        items = products(a.root, category)
        if only:
            items = [t for t in items if t[0] in only]
        elif a.scan:
            items = items[:a.scan]
        # pass 1: score every product (fast analysis), best-quality first
        scored = []
        for sku, fp, sp in items:
            try:
                f = analyse_category(fp, category)
                scored.append((f['iou'], sku, fp, sp))
            except Reject as e:
                log.write(f'{category}\t{sku}\t{e}\n')
            except Exception as e:  # noqa: BLE001 - log any broken photo and go on
                log.write(f'{category}\t{sku}\terror {type(e).__name__}: {e}\n')
        scored.sort(reverse=True)
        print(f'{category}: {len(scored)} usable of {len(items)}', file=sys.stderr)
        n = 0
        for q, sku, fp, sp in scored:
            if n >= want and not only:
                break
            try:
                scene, meta, f = build(sku, fp, sp, category)
            except Reject as e:
                log.write(f'{category}\t{sku}\t{e}\n')
                continue
            glb_rel, thumb_rel = f'glb/{sku}.glb', f'thumb/{sku}.jpg'
            scene.export(os.path.join(a.out, glb_rel))
            thumbnail(f, os.path.join(a.out, thumb_rel))
            e = entry(sku, category, meta, glb_rel, thumb_rel, n)
            e['glbBytes'] = os.path.getsize(os.path.join(a.out, glb_rel))
            catalog.append(e)
            n += 1
            print(sku, e['shape'], e['rim'], e['colour'], e['material'], e['sizes']['label'], e['glbBytes'], file=sys.stderr)
    log.close()
    with open(os.path.join(a.out, 'frames_catalog.json'), 'w', encoding='utf-8') as fo:
        json.dump(dict(version=1, licence='Kaggle Glasses dataset: licence unknown, demo use only',
                       frames=catalog), fo, indent=1, ensure_ascii=False)


if __name__ == '__main__':
    main()
