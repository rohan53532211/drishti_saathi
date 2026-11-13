"""
Builds app/src/main/assets/head_template.json: a full-head template (skull, ears, back of head, neck) fitted once to
MediaPipe's canonical face model, plus the correspondence the phone needs to deform it to a customer's face.

Source mesh: MakeHuman base mesh "hm08" (makehuman/data/3dobjs/base.obj in github.com/makehumancommunity/makehuman).
Licence: CC0 1.0 Universal. The file header reads "This asset was explicitly released as CC0 in september 2020" and the
repository's LICENSE.md lists "The base mesh and proxies" among the CC0 assets.

Steps (all offline, run once):
  1. cut the head and neck out of the body group (quads -> triangles), keep the largest connected piece;
  2. cap every hole except the neck cut (the eye openings), so no see-through gaps;
  3. similarity-align it to the canonical face (pupils and nose tip), dm -> cm;
  4. non-rigid fit: radial-basis-function deformation (phi(r) = r, plus an affine part) that moves the template's
     surface onto the 468 canonical landmarks while skull anchor vertices stay put; repeated until every landmark lies
     on the template surface (<0.1 mm);
  5. correspondence: for each canonical landmark the template triangle + barycentric weights that sit on it;
  6. regions per vertex (face / ear / scalp / neck), ear rest points for the temples, skull anchors, cylindrical UVs
     (face given most of the atlas) with the seam at the back of the head.

Usage: python -I tools/head_template.py <base.obj> <canonical_face_model.obj> <out.json> [preview.png]
"""
import json
import sys

import numpy as np
from scipy.spatial import cKDTree


def load_obj(path):
    V, groups, g = [], {}, None
    for line in open(path, encoding='utf-8'):
        if line.startswith('v '):
            V.append([float(x) for x in line.split()[1:4]])
        elif line.startswith('g '):
            g = line.split()[1]
        elif line.startswith('f '):
            groups.setdefault(g, []).append([int(t.split('/')[0]) - 1 for t in line.split()[1:]])
    return np.array(V), groups


def canonical(path):
    V = [[float(x) for x in l.split()[1:4]] for l in open(path) if l.startswith('v ')]
    return np.array(V[:468])


def components(F, n):
    parent = np.arange(n)

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for t in F:
        a = find(t[0])
        for b in t[1:]:
            b = find(b)
            if a != b:
                parent[b] = a
    return np.array([find(i) for i in range(n)])


def boundary_loops(F):
    edges = {}
    for t in F:
        for k in range(3):
            a, b = t[k], t[(k + 1) % 3]
            edges[(a, b)] = edges.get((a, b), 0) + 1
    bnd = [(a, b) for (a, b) in edges if (b, a) not in edges]
    nxt = {a: b for a, b in bnd}
    loops, seen = [], set()
    for a, _ in bnd:
        if a in seen:
            continue
        loop, c = [], a
        while c not in seen:
            seen.add(c)
            loop.append(c)
            c = nxt[c]
        loops.append(loop)
    return loops


def rbf_solve(C, D, eps=1e-9):
    """phi(r) = r with an affine term; returns weights (n x 3) and affine (4 x 3)."""
    n = len(C)
    K = np.linalg.norm(C[:, None, :] - C[None, :, :], axis=2)
    P = np.hstack([np.ones((n, 1)), C])
    A = np.zeros((n + 4, n + 4))
    A[:n, :n] = K + eps * np.eye(n)
    A[:n, n:] = P
    A[n:, :n] = P.T
    rhs = np.zeros((n + 4, D.shape[1]))
    rhs[:n] = D
    sol = np.linalg.solve(A, rhs)
    return sol[:n], sol[n:]


def rbf_apply(C, W, Aff, X):
    K = np.linalg.norm(X[:, None, :] - C[None, :, :], axis=2)
    return K @ W + np.hstack([np.ones((len(X), 1)), X]) @ Aff


def closest_on_mesh(V, F, Q, cand=24):
    """Closest point on the triangle mesh for each query: (triangle, barycentric, point)."""
    cent = V[F].mean(1)
    tree = cKDTree(cent)
    _, near = tree.query(Q, k=cand)
    out_t, out_b, out_p = [], [], []
    for qi, q in enumerate(Q):
        best = (1e18, None, None, None)
        for t in near[qi]:
            a, b, c = V[F[t]]
            p, bc = closest_point_triangle(q, a, b, c)
            d = np.sum((p - q) ** 2)
            if d < best[0]:
                best = (d, t, bc, p)
        out_t.append(best[1]); out_b.append(best[2]); out_p.append(best[3])
    return np.array(out_t), np.array(out_b), np.array(out_p)


def closest_point_triangle(p, a, b, c):
    # Ericson, Real-Time Collision Detection 5.1.5
    ab, ac, ap = b - a, c - a, p - a
    d1, d2 = ab @ ap, ac @ ap
    if d1 <= 0 and d2 <= 0:
        return a, np.array([1.0, 0, 0])
    bp = p - b
    d3, d4 = ab @ bp, ac @ bp
    if d3 >= 0 and d4 <= d3:
        return b, np.array([0, 1.0, 0])
    vc = d1 * d4 - d3 * d2
    if vc <= 0 and d1 >= 0 and d3 <= 0:
        v = d1 / (d1 - d3)
        return a + v * ab, np.array([1 - v, v, 0])
    cp = p - c
    d5, d6 = ab @ cp, ac @ cp
    if d6 >= 0 and d5 <= d6:
        return c, np.array([0, 0, 1.0])
    vb = d5 * d2 - d1 * d6
    if vb <= 0 and d2 >= 0 and d6 <= 0:
        w = d2 / (d2 - d6)
        return a + w * ac, np.array([1 - w, 0, w])
    va = d3 * d6 - d5 * d4
    if va <= 0 and (d4 - d3) >= 0 and (d5 - d6) >= 0:
        w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
        return b + w * (c - b), np.array([0, 1 - w, w])
    den = 1 / (va + vb + vc)
    v, w = vb * den, vc * den
    return a + ab * v + ac * w, np.array([1 - v - w, v, w])


def umeyama(src, dst):
    ms, md = src.mean(0), dst.mean(0)
    s, d = src - ms, dst - md
    U, S, Vt = np.linalg.svd(d.T @ s / len(src))
    D = np.eye(3)
    if np.linalg.det(U @ Vt) < 0:
        D[2, 2] = -1
    R = U @ D @ Vt
    scale = np.trace(np.diag(S) @ D) / (s ** 2).sum(1).mean()
    return scale, R, md - scale * R @ ms


def main(base, canon_obj, out, preview=None):
    V, groups = load_obj(base)
    eyeL = V[np.unique(np.concatenate(groups['helper-l-eye']))].mean(0)
    eyeR = V[np.unique(np.concatenate(groups['helper-r-eye']))].mean(0)
    neck_y = 5.65
    # below the jaw only the neck itself (no shoulder slivers); higher up the full width (ears)
    quads = [f for f in groups['body'] if all(V[i, 1] > neck_y and abs(V[i, 0]) < (1.0 if V[i, 1] > 6.4 else 0.6) for i in f)]
    used = np.unique(np.concatenate(quads))
    remap = -np.ones(len(V), int)
    remap[used] = np.arange(len(used))
    P = V[used].copy()
    F = []
    for q in quads:
        a, b, c, d = remap[q]
        F += [[a, b, c], [a, c, d]]
    F = np.array(F)
    comp = components(F, len(P))
    keep_root = np.bincount(comp).argmax()
    keepv = comp == keep_root
    F = F[keepv[F].all(1)]
    used2 = np.unique(F)
    r2 = -np.ones(len(P), int); r2[used2] = np.arange(len(used2))
    P = P[used2]; F = r2[F]

    # clean neck edge: the quad rows are not horizontal, so the cut is a ragged staircase with thin slivers. Drop the
    # triangles below a level line, keep the largest piece, then level the remaining edge vertices onto that line.
    for _ in range(1):
        loop = min(boundary_loops(F), key=lambda l: P[l, 1].mean())
        level = np.percentile(P[loop, 1], 40)
        F = F[P[F, 1].mean(1) > level]
        comp = components(F, len(P))
        cnt = np.bincount(comp[np.unique(F)], minlength=len(P))
        F = F[comp[F[:, 0]] == cnt.argmax()]
    loop = min(boundary_loops(F), key=lambda l: P[l, 1].mean())
    level = np.percentile(P[loop, 1], 60)
    for vi in loop:
        P[vi, 1] = max(P[vi, 1], level)
    used2 = np.unique(F)
    r2 = -np.ones(len(P), int); r2[used2] = np.arange(len(used2))
    P = P[used2]; F = r2[F]

    # neck down to collar level: smooth elliptical rings below the levelled edge (from its own shape to an ellipse,
    # flaring a little towards the collar), closed with a rounded bottom so the head is watertight
    loop = min(boundary_loops(F), key=lambda l: P[l, 1].mean())
    ring = np.array(loop)
    ctr = P[ring][:, [0, 2]].mean(0)
    rel = P[ring][:, [0, 2]] - ctr
    ang = np.arctan2(rel[:, 1], rel[:, 0])
    rx, rz = np.percentile(np.abs(rel[:, 0]), 90), np.percentile(np.abs(rel[:, 1]), 90)
    ell = np.stack([rx * np.cos(ang), rz * np.sin(ang)], 1)
    # edge vertices sticking out of the ellipse (bits of the shoulder) are pulled onto it; the edge is levelled
    r_edge = np.linalg.norm(rel, axis=1); r_ell = np.linalg.norm(ell, axis=1)
    stick = r_edge > 1.05 * r_ell
    rel[stick] = ell[stick]
    P[ring[stick], 0] = rel[stick, 0] + ctr[0]; P[ring[stick], 2] = rel[stick, 1] + ctr[1]
    P[ring, 1] = level
    K, drop = 6, 0.6                                  # 6 rings, 6 cm (dm units here)
    first = len(P)
    rings = [ring]
    new = []
    for j in range(1, K + 1):
        t = j / K
        mix = min(1.0, t * 3.0)                       # ring 1 keeps some of the edge shape, then a clean ellipse
        flare = 1.0 + 0.16 * t * t
        xz = ((1 - mix) * rel + mix * ell) * flare + ctr
        y = level - drop * t
        new += [[x, y, z] for x, z in xz]
        rings.append(np.arange(first + (j - 1) * len(ring), first + j * len(ring)))
    P = np.vstack([P, np.array(new)])
    bottom = len(P)
    P = np.vstack([P, [[ctr[0], level - drop - 0.12, ctr[1]]]])
    tris = []
    for j in range(K):
        A, Bn = rings[j], rings[j + 1]
        for k in range(len(ring)):
            k2 = (k + 1) % len(ring)
            a, b, a1, b1 = A[k], A[k2], Bn[k], Bn[k2]
            tris += [[b, a, a1], [b, a1, b1]]
    last = rings[-1]
    for k in range(len(ring)):
        tris.append([last[(k + 1) % len(ring)], last[k], bottom])
    F = np.vstack([F, np.array(tris)])
    neck_new = np.arange(first, len(P))
    # smooth the joint between the original neck and the extension (x, z only, a 2.5 cm band)
    nb = [set() for _ in range(len(P))]
    for t in F:
        for i in range(3):
            nb[t[i]].update((t[(i + 1) % 3], t[(i + 2) % 3]))
    band = np.where(np.abs(P[:, 1] - level) < 0.25)[0]
    for _ in range(12):
        Q = P.copy()
        for vi in band:
            m = P[list(nb[vi])].mean(0)
            Q[vi, 0] = 0.5 * P[vi, 0] + 0.5 * m[0]
            Q[vi, 2] = 0.5 * P[vi, 2] + 0.5 * m[2]
        P = Q

    # cap any other holes (none expected for hm08)
    loops = boundary_loops(F)
    caps = []
    for i, loop in enumerate(loops):
        c = len(P)
        P = np.vstack([P, P[loop].mean(0)])
        for k in range(len(loop)):
            a, b = loop[k], loop[(k + 1) % len(loop)]
            caps.append([b, a, c])
    if caps:
        F = np.vstack([F, np.array(caps)])
    print('template', len(P), 'verts', len(F), 'tris', len(loops), 'holes capped, neck +', len(neck_new), 'verts')

    # similarity to canonical (cm): pupils and nose tip
    C = canonical(canon_obj)
    pupR = C[[33, 133, 159, 145, 160, 144, 158, 153]].mean(0)
    pupL = C[[263, 362, 386, 374, 387, 373, 385, 380]].mean(0)
    mid = np.abs(P[:, 0]) < 0.04
    face_band = mid & (P[:, 1] > eyeL[1] - 0.8) & (P[:, 1] < eyeL[1])
    nose = P[np.where(face_band)[0][np.argmax(P[face_band, 2])]]
    s, R, t = umeyama(np.array([eyeR, eyeL, nose]), np.array([pupR, pupL, C[1]]))
    P = (s * (R @ P.T)).T + t
    print('similarity scale', s)

    # skull anchors: far from the face (back, top, neck), they keep the head's own shape during the fit
    face_c = C.mean(0)
    dist_face = cKDTree(C).query(P)[0]
    anchor_ids = np.where(dist_face > 5.5)[0]
    rng = np.random.default_rng(1)
    anchor_ids = rng.choice(anchor_ids, size=min(80, len(anchor_ids)), replace=False)

    for it in range(8):
        tri, bc, pts = closest_on_mesh(P, F, C)
        err = np.linalg.norm(pts - C, axis=1)
        print(f'fit {it}: mean {err.mean():.3f} cm, max {err.max():.3f} cm')
        if err.max() < 0.01:
            break
        ctr = np.vstack([pts, P[anchor_ids]])
        dsp = np.vstack([C - pts, np.zeros((len(anchor_ids), 3))])
        W, Aff = rbf_solve(ctr, dsp)
        P = P + rbf_apply(ctr, W, Aff, P)
    tri, bc, pts = closest_on_mesh(P, F, C)
    err = np.linalg.norm(pts - C, axis=1)
    print(f'final: mean {err.mean()*10:.3f} mm, max {err.max()*10:.3f} mm')

    # regions: 0 face (near the canonical landmarks), 1 ear, 2 scalp (hair), 3 neck / other skin
    dist_face = cKDTree(C).query(P)[0]
    lm10 = C[10]
    ear_zone = (np.abs(P[:, 0]) > 6.0) & (P[:, 1] > C[234][1] - 3.2) & (P[:, 1] < C[234][1] + 3.0) & \
        (P[:, 2] < C[234][2] + 0.5) & (P[:, 2] > C[234][2] - 4.0)
    side_r = np.array([np.abs(P[:, 0])[(P[:, 1] > y - 0.4) & (P[:, 1] < y + 0.4) & ~ear_zone].max() if ((P[:, 1] > y - 0.4) & (P[:, 1] < y + 0.4) & ~ear_zone).any() else 0 for y in P[:, 1]])
    ear = ear_zone & (np.abs(P[:, 0]) > side_r - 0.15)
    region = np.full(len(P), 3)
    region[dist_face < 0.6] = 0
    region[ear] = 1
    # hair: above the hairline at the front (landmark 10 + 0.4 cm), the top and back of the skull down to the nape
    nape_y = C[234][1] - 3.0
    scalp = (region != 0) & (region != 1) & (
        (P[:, 1] > lm10[1] + 0.4) |
        ((P[:, 2] < C[234][2] + 0.5) & (P[:, 1] > nape_y) & (np.abs(P[:, 0]) < 8.5) & (P[:, 1] > C[234][1] + 1.0)) |
        ((P[:, 2] < C[234][2] - 2.0) & (P[:, 1] > nape_y)))
    region[scalp] = 2
    # 4: the neck (below the chin, and the extension to the collar)
    neck = (region == 3) & (P[:, 1] < C[152][1] - 0.3)
    neck[neck_new] = True
    region[neck] = 4
    print('regions', np.bincount(region))

    # ear rest points (where a temple sits): top of the ear's root, just above where it joins the head
    sides = []
    for sgn in (-1, 1):   # canonical -x is the subject's right
        e = np.where(ear & (np.sign(P[:, 0]) == sgn))[0]
        top = P[e[np.argmax(P[e, 1])]]
        # root: the front of the ear's top (where the helix meets the head), at the inner (head-side) x of the top
        near_top = e[P[e, 1] > top[1] - 0.8]
        sides.append((np.abs(P[near_top, 0]).min(), top[1] - 0.6, P[near_top, 2].max()))
    # the template is symmetric: average both sides so the temples rest at mirrored points
    ax, ay, az = (float(np.mean([s[i] for s in sides])) for i in range(3))
    rest = [[-ax, ay, az], [ax, ay, az]]
    print('ear rest R/L', rest)

    # UVs: cylinder around the head's vertical axis; the angle is warped so the face gets most of the width
    zc = P[:, 2].min() + 0.5 * (P[:, 2].max() - P[:, 2].min())
    th = np.arctan2(P[:, 0], P[:, 2] - zc)
    u = 0.5 + 0.48 * np.sign(th) * (np.abs(th) / np.pi) ** 0.6
    ytop, ybot = P[:, 1].max(), P[:, 1].min()
    v = (ytop - P[:, 1]) / (ytop - ybot)
    uv = np.stack([u, v], 1)
    # seam at the back: duplicate the vertices of triangles that wrap so every triangle has continuous UVs
    P_list, uv_list = list(P), list(uv)
    region_list = list(region)
    dup = {}
    F2 = F.copy()
    for ti, t in enumerate(F):
        us = uv[t, 0]
        if us.max() - us.min() > 0.5:
            for k, vi in enumerate(t):
                if uv[vi, 0] < 0.5:
                    if vi not in dup:
                        dup[vi] = len(P_list)
                        P_list.append(P[vi]); uv_list.append([uv[vi, 0] + 0.96, uv[vi, 1]]); region_list.append(region[vi])
                    F2[ti, k] = dup[vi]
    P = np.array(P_list); uv = np.array(uv_list); region = np.array(region_list); F = F2
    print('seam duplicates', len(dup), '-> verts', len(P))
    # the landmark correspondence triangles keep their (non-seam) vertices; check
    assert all(uv[F[t], 0].max() - uv[F[t], 0].min() < 0.5 for t in tri)

    data = {
        'source': 'MakeHuman base mesh hm08 (github.com/makehumancommunity/makehuman, makehuman/data/3dobjs/base.obj)',
        'licence': 'CC0 1.0 Universal (released as CC0 in September 2020 by Data Collection AB, Joel Palmius, Jonas Hauquier)',
        'units': 'cm, MediaPipe canonical face space (+x subject left, +y up, +z out of the face)',
        'vertices': [round(float(x), 4) for x in P.reshape(-1)],
        'triangles': [int(x) for x in F.reshape(-1)],
        'uvs': [round(float(x), 5) for x in uv.reshape(-1)],
        'region': [int(x) for x in region],
        'landmarkTriangles': [int(x) for x in tri],
        'landmarkBary': [round(float(x), 6) for x in bc.reshape(-1)],
        'skullAnchors': [int(x) for x in sorted(anchor_ids)],
        'earRest': [round(x, 4) for p in rest for x in p],
    }
    json.dump(data, open(out, 'w'), separators=(',', ':'))
    print('wrote', out)

    if preview:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        cols = np.array(['#d9a07a', '#c0504d', '#3a2a20', '#b08060', '#a07050'])[region]
        fig, ax = plt.subplots(1, 3, figsize=(15, 6))
        for a, (i, j, name) in zip(ax, [(0, 1, 'front'), (2, 1, 'side (z, y)'), (0, 2, 'top (x, z)')]):
            a.scatter(P[:, i], P[:, j], s=1, c=cols)
            a.scatter(C[:, i], C[:, j], s=1, c='#00a0ff')
            a.scatter([r[i] for r in rest], [r[j] for r in rest], s=40, c='lime')
            a.set_aspect('equal'); a.set_title(name)
        fig.savefig(preview, dpi=80)


if __name__ == '__main__':
    main(*sys.argv[1:])
