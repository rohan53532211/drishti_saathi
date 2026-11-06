"""Cut out front-view frame photos for the live try-on overlay (Track E).

Usage (run with `python -I` so nothing is imported from the image folder):
    python -I tools/frames_cutout.py <src_dir> <out_png_dir> <meta_json> [debug_dir]

For every <sku>_0.jpg in <src_dir> (2400x1200 product photos on a near-white background):
  1. Estimate the background colour from the image borders.
  2. Alpha = soft ramp on the colour distance from the background; colours are un-mixed from the
     background so thin metal rims keep no white fringe.
  3. Lens openings = enclosed background regions inside the rim (after a small closing so thin or
     half rims still seal). Per side, the convex hull of the large holes becomes the lens. Inside it
     everything is made (almost) transparent with a faint tint, which also removes the temple arms
     that show through the clear lenses in the photo. Small parts on the nasal side of the lens
     (nose pads) are kept.
  4. Crop to the frame front with a small margin, downscale to width <= MAX_W, save <sku>.png.
  5. Write metadata: image size, lens centres (centroid of each lens), lens width/height,
     bridge gap and front width, all in output-image pixels.
Pure Pillow + numpy; no OpenCV/scipy.
"""
import json
import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

WORK_W = 1200          # processing resolution (source is downscaled 2x)
MAX_W = 900            # output width cap
MARGIN = 8             # px margin around the frame at output resolution
ALPHA_T0, ALPHA_T1 = 7.0, 34.0   # colour distance ramp: transparent below T0, opaque above T1
MASK_T = 14.0          # distance that counts as "frame" for hole finding
CLOSE_R = 3            # closing radius (work px) to seal thin rims
LENS_ALPHA = 22        # residual lens opacity (0..255) for a faint tint
LENS_TINT = (228, 236, 242)
PAD_KEEP_FRACTION = 0.30   # nasal fraction of the lens width where parts (nose pads) are kept
MIN_HOLE_FRAC = 0.004  # holes smaller than this fraction of the image are ignored
LENS_ERODE = 2         # shrink the lens hull a little so the rim's inner edge is not cut
SHADOW_RIM_FACTOR, SHADOW_SLACK = 1.1, 1   # bottom rim may be a little thicker than the sides (+ px)
SHADOW_MAX_CHROMA = 9.0    # shadow is grey
SHADOW_MAX_DARKNESS = 110  # and not darker than this below the background


def border_bg(a):
    b = np.concatenate([a[:6].reshape(-1, 3), a[-6:].reshape(-1, 3), a[:, :6].reshape(-1, 3), a[:, -6:].reshape(-1, 3)])
    return np.median(b, axis=0)


def shift_or(m, dy, dx):
    out = np.zeros_like(m)
    h, w = m.shape
    ys, yd = (slice(dy, h), slice(0, h - dy)) if dy >= 0 else (slice(0, h + dy), slice(-dy, h))
    xs, xd = (slice(dx, w), slice(0, w - dx)) if dx >= 0 else (slice(0, w + dx), slice(-dx, w))
    out[ys, xs] = m[yd, xd]
    return out


def dilate(m, r):
    out = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dy * dy + dx * dx <= r * r and (dy or dx):
                out |= shift_or(m, dy, dx)
    return out


def erode(m, r):
    return ~dilate(~m, r)


def label(mask):
    """4-connected components of a boolean mask. Returns (labels int32, list of sizes)."""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    flat = mask.ravel()
    lf = lab.ravel()
    sizes = [0]
    n = 0
    for start in np.flatnonzero(flat):
        if lf[start]:
            continue
        n += 1
        lf[start] = n
        q = deque([start])
        cnt = 0
        while q:
            p = q.popleft()
            cnt += 1
            y, x = divmod(p, w)
            for nb, ok in ((p - 1, x > 0), (p + 1, x < w - 1), (p - w, y > 0), (p + w, y < h - 1)):
                if ok and flat[nb] and not lf[nb]:
                    lf[nb] = n
                    q.append(nb)
        sizes.append(cnt)
    return lab, sizes


def convex_hull(points):
    pts = sorted(set(map(tuple, points)))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def boundary_points(m):
    inner = erode(m, 1)
    ys, xs = np.nonzero(m & ~inner)
    return np.stack([xs, ys], 1)


def fill_poly(shape, poly):
    img = Image.new('L', (shape[1], shape[0]), 0)
    ImageDraw.Draw(img).polygon([tuple(map(float, p)) for p in poly], fill=1)
    return np.asarray(img).astype(bool)


def process(path):
    src = Image.open(path).convert('RGB')
    scale = WORK_W / src.width
    im = src.resize((WORK_W, round(src.height * scale)), Image.LANCZOS)
    a = np.asarray(im).astype(np.float32)
    h, w, _ = a.shape
    bg = border_bg(a)
    # Only darkening or a colour shift counts as frame: bright floor reflections under the frame
    # (whiter than the background, no hue) fall back to background.
    darker = np.maximum(bg - a, 0)
    chroma = (a - a.mean(2, keepdims=True)) - (bg - bg.mean())
    dist = np.sqrt((darker ** 2).sum(2) + (chroma ** 2).sum(2))

    alpha = np.clip((dist - ALPHA_T0) / (ALPHA_T1 - ALPHA_T0), 0, 1)
    fg = dist > MASK_T
    closed = erode(dilate(fg, CLOSE_R), CLOSE_R)
    # remove specks before anything else
    flab, fsizes = label(closed)
    big = np.zeros(len(fsizes), bool)
    big[[i for i, s in enumerate(fsizes) if i and s > 200]] = True
    closed = big[flab]
    alpha[~dilate(closed, 2)] = 0

    # background components: the ones touching the border are "outside", the others are holes
    blab, bsizes = label(~closed)
    edge = set(np.unique(np.concatenate([blab[0], blab[-1], blab[:, 0], blab[:, -1]]))) - {0}
    ys, xs = np.nonzero(closed)
    fx0, fx1, fy0, fy1 = xs.min(), xs.max(), ys.min(), ys.max()
    mid = (fx0 + fx1) / 2
    min_hole = MIN_HOLE_FRAC * h * w
    holes = {'L': [], 'R': []}
    for i, s in enumerate(bsizes):
        if i == 0 or i in edge or s < min_hole:
            continue
        hy, hx = np.nonzero(blab == i)
        cx = hx.mean()
        if abs(cx - mid) < 0.06 * (fx1 - fx0):   # bridge gaps (double bridge) sit on the midline
            continue
        holes['L' if cx < mid else 'R'].append((s, i))

    lens = {}
    out_alpha = alpha.copy()
    rgb = a.copy()
    lens_mask_all = np.zeros((h, w), bool)
    notes = []
    for side in 'LR':
        hs = sorted(holes[side], reverse=True)
        if not hs:
            notes.append(f'no lens hole on {side}')
            continue
        largest = hs[0][0]
        keep = [i for s, i in hs if s >= 0.03 * largest]
        m = np.isin(blab, keep)
        hull = convex_hull(boundary_points(m))
        lm = erode(fill_poly((h, w), hull), LENS_ERODE) | m
        # parts inside the lens: keep those on the nasal side (nose pads), drop the rest (temples)
        inside = closed & lm
        plab, psizes = label(inside)
        lx0, lx1 = np.nonzero(lm)[1].min(), np.nonzero(lm)[1].max()
        lw = lx1 - lx0
        clear = lm.copy()
        for j in range(1, len(psizes)):
            py, px = np.nonzero(plab == j)
            pc = px.mean()
            nasal = (lx1 - pc) < PAD_KEEP_FRACTION * lw if side == 'L' else (pc - lx0) < PAD_KEEP_FRACTION * lw
            if nasal:
                clear[plab == j] = False
        out_alpha[clear] = 0
        lens_mask_all |= clear
        ly, lxs = np.nonzero(lm)
        lens[side] = dict(cx=float(lxs.mean()), cy=float(ly.mean()), x0=int(lxs.min()), x1=int(lxs.max()),
                          y0=int(ly.min()), y1=int(ly.max()), holes=len(keep))
        lens[side]['mask'] = lm

    # Contact shadow: a soft grey band on the "floor" just under the lowest rims, merged with them.
    # Rim thickness is measured above and beside each lens opening; anything neutral and not dark
    # further below the lens bottom than that thickness (plus slack) is shadow and is removed.
    # Black/dark rims and coloured rims are never touched.
    if lens:
        def run(y, x, dy, dx):
            skip = 0
            while 0 <= y < h and 0 <= x < w and not closed[y, x] and skip <= LENS_ERODE + 2:
                y, x, skip = y + dy, x + dx, skip + 1
            n = 0
            while 0 <= y < h and 0 <= x < w and closed[y, x]:
                y, x, n = y + dy, x + dx, n + 1
            return n

        rows = np.arange(h)[:, None]
        zone = np.zeros((h, w), bool)
        t_all = []
        for side, v in lens.items():
            cx, cy = int(v['cx']), int(v['cy'])
            tops = [run(v['y0'] - 1, cx + k, -1, 0) for k in range(-10, 11, 5)]
            sides = [run(cy + k, v['x0'] - 1 if side == 'L' else v['x1'] + 1, 0, -1 if side == 'L' else 1)
                     for k in range(-10, 11, 5)]
            t = min(np.median(tops), np.median(sides))
            t_all.append(t)
            lm = v['mask']
            has = lm.any(0)
            bottom = np.where(has, h - 1 - np.argmax(lm[::-1], 0), h)
            limit = bottom + SHADOW_RIM_FACTOR * t + SHADOW_SLACK
            zone |= (rows > limit[None, :]) & has[None, :]
            v['rimPx'] = float(t)
        covered = np.zeros(w, bool)
        for v in lens.values():
            covered |= v['mask'].any(0)
        low = max(v['y1'] for v in lens.values()) + SHADOW_RIM_FACTOR * max(t_all) + SHADOW_SLACK
        zone |= (rows > low) & ~covered[None, :]
        neutral = np.sqrt((chroma ** 2).sum(2)) < SHADOW_MAX_CHROMA
        light = (bg.mean() - a.mean(2)) < SHADOW_MAX_DARKNESS
        out_alpha[zone & neutral & light] = 0
        # drop specks left over from the shadow
        slab, ssizes = label((out_alpha > 0) & zone)
        for j in range(1, len(ssizes)):
            if ssizes[j] < 40:
                out_alpha[slab == j] = 0
    for v in lens.values():
        v.pop('mask')

    # un-mix the background from semi-transparent edge pixels
    al = np.clip(out_alpha, 1e-3, 1)[..., None]
    fgc = np.clip((a - (1 - al) * bg) / al, 0, 255)
    rgb = np.where(out_alpha[..., None] > 0, fgc, a)
    # faint lens tint
    rgb[lens_mask_all] = LENS_TINT
    a8 = np.round(out_alpha * 255)
    a8[lens_mask_all] = LENS_ALPHA

    # front extent from the final cut-out
    ys, xs = np.nonzero(out_alpha > 0.5)
    fx0, fx1, fy0, fy1 = xs.min(), xs.max(), ys.min(), ys.max()

    # crop to the front (frame mask plus lenses) with margin
    vis = (a8 > 8)
    ys, xs = np.nonzero(vis)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    out_scale = min(1.0, (MAX_W - 2 * MARGIN) / (x1 - x0))
    m_work = int(np.ceil(MARGIN / out_scale))
    x0, y0 = max(0, x0 - m_work), max(0, y0 - m_work)
    x1, y1 = min(w, x1 + m_work), min(h, y1 + m_work)
    rgba = np.dstack([rgb, a8]).astype(np.uint8)[y0:y1, x0:x1]
    out = Image.fromarray(rgba, 'RGBA')
    ow, oh = int((x1 - x0) * out_scale), int((y1 - y0) * out_scale)
    # resize premultiplied to avoid dark/white halos
    pm = rgba.astype(np.float32)
    pm[..., :3] *= pm[..., 3:4] / 255
    chans = [Image.fromarray(pm[..., c]).resize((ow, oh), Image.LANCZOS) for c in range(4)]
    pmr = np.stack([np.asarray(c) for c in chans], 2)
    pmr[..., 3] = np.clip(pmr[..., 3], 0, 255)
    alr = np.maximum(pmr[..., 3:4], 1e-3)
    rgbr = np.clip(pmr[..., :3] * 255 / alr, 0, 255)
    out = Image.fromarray(np.dstack([rgbr, pmr[..., 3:4]]).round().astype(np.uint8), 'RGBA')

    def tx(x):
        return round((x - x0) * out_scale, 1)

    def ty(y):
        return round((y - y0) * out_scale, 1)

    meta = dict(widthPx=ow, heightPx=oh,
                frontWidthPx=round((fx1 - fx0 + 1) * out_scale, 1),
                frontX0Px=tx(fx0), frontX1Px=tx(fx1 + 1), frontY0Px=ty(fy0), frontY1Px=ty(fy1 + 1), notes=notes)
    if 'L' in lens and 'R' in lens:
        L, R = lens['L'], lens['R']
        meta.update(
            leftLensCenterPx=[tx(L['cx']), ty(L['cy'])], rightLensCenterPx=[tx(R['cx']), ty(R['cy'])],
            lensWidthPx=round(((L['x1'] - L['x0']) + (R['x1'] - R['x0'])) / 2 * out_scale, 1),
            lensHeightPx=round(((L['y1'] - L['y0']) + (R['y1'] - R['y0'])) / 2 * out_scale, 1),
            bridgePx=round((R['x0'] - L['x1']) * out_scale, 1),
            holes=[L['holes'], R['holes']], rimWorkPx=[L.get('rimPx'), R.get('rimPx')],
        )
    return out, meta


def main():
    src_dir, out_dir, meta_path = sys.argv[1], sys.argv[2], sys.argv[3]
    debug_dir = sys.argv[4] if len(sys.argv) > 4 else None
    only = set(sys.argv[5].split(',')) if len(sys.argv) > 5 else None
    os.makedirs(out_dir, exist_ok=True)
    metas = {}
    if os.path.exists(meta_path) and only:
        with open(meta_path) as f:
            metas = json.load(f)
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith('_0.jpg'):
            continue
        sku = name[:-len('_0.jpg')]
        if only and sku not in only:
            continue
        out, meta = process(os.path.join(src_dir, name))
        out.save(os.path.join(out_dir, sku + '.png'), optimize=True)
        meta['bytes'] = os.path.getsize(os.path.join(out_dir, sku + '.png'))
        metas[sku] = meta
        print(sku, json.dumps(meta))
        if debug_dir:
            os.makedirs(debug_dir, exist_ok=True)
            # composite on a mid-grey checker + mark centres
            ck = Image.new('RGB', out.size, (120, 140, 120))
            ck.paste(out, (0, 0), out)
            d = ImageDraw.Draw(ck)
            for k in ('leftLensCenterPx', 'rightLensCenterPx'):
                if k in meta:
                    x, y = meta[k]
                    d.ellipse([x - 6, y - 6, x + 6, y + 6], outline=(255, 0, 0), width=3)
            d.line([meta['frontX0Px'], 4, meta['frontX1Px'], 4], fill=(0, 0, 255), width=3)
            ck.save(os.path.join(debug_dir, sku + '.jpg'), quality=85)
    with open(meta_path, 'w') as f:
        json.dump(metas, f, indent=1)


if __name__ == '__main__':
    main()
