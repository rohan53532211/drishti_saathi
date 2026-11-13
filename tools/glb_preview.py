"""Software preview of frame GLBs (front, 45 deg, side), no GPU or OpenGL needed.

Usage: python -P -E tools/glb_preview.py <out.png> <a.glb> [<b.glb> ...]
Each GLB becomes one row of three views. Reads the exported file back, so it also checks the GLB.
"""
import math
import sys

import numpy as np
import trimesh
from PIL import Image, ImageDraw

VW, VH = 520, 300
LIGHT = np.array([-0.4, 0.5, -0.75])
LIGHT /= np.linalg.norm(LIGHT)


def parts(path):
    sc = trimesh.load(path, force='scene')
    out = []
    for node in sc.graph.nodes_geometry:
        T, g = sc.graph[node]
        m = sc.geometry[g]
        v = trimesh.transform_points(m.vertices, T)
        mat = getattr(m.visual, 'material', None)
        tex, uv, rgba = None, None, np.array([200, 200, 200, 255], float)
        if mat is not None:
            if getattr(mat, 'baseColorFactor', None) is not None:
                rgba = np.array(mat.baseColorFactor, float)
            if getattr(mat, 'baseColorTexture', None) is not None and getattr(m.visual, 'uv', None) is not None:
                tex = np.asarray(mat.baseColorTexture.convert('RGBA')).astype(float)
                uv = np.asarray(m.visual.uv)
        blend = rgba[3] < 250 or getattr(mat, 'alphaMode', None) == 'BLEND'
        out.append(dict(name=node, v=v, f=m.faces, tex=tex, uv=uv, rgba=rgba, blend=blend))
    return out


def render(ps, yaw_deg):
    a = math.radians(yaw_deg)
    R = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    allv = np.concatenate([R @ p['v'].T for p in ps], 1).T
    sx, sy = -allv[:, 0], -allv[:, 1]
    span = max((sx.max() - sx.min()) / (VW - 40), (sy.max() - sy.min()) / (VH - 40))
    cx, cy = (sx.max() + sx.min()) / 2, (sy.max() + sy.min()) / 2
    img = np.full((VH, VW, 3), 236.0)
    zb = np.full((VH, VW), np.inf)
    # opaque first, then see-through parts far to near
    order = sorted(ps, key=lambda p: (p['blend'], -float((R @ p['v'].T)[2].mean())))
    for p in order:
        v = (R @ p['v'].T).T
        X = (-v[:, 0] - cx) / span + VW / 2
        Y = (-v[:, 1] - cy) / span + VH / 2
        Z = v[:, 2]
        for fi, (i, j, k) in enumerate(p['f']):
            P = np.array([[X[i], Y[i]], [X[j], Y[j]], [X[k], Y[k]]])
            n = np.cross(v[j] - v[i], v[k] - v[i])
            nn = np.linalg.norm(n)
            if nn == 0:
                continue
            n /= nn
            shade = 0.45 + 0.55 * abs(n @ LIGHT)
            x0, x1 = int(max(0, P[:, 0].min())), int(min(VW - 1, P[:, 0].max() + 1))
            y0, y1 = int(max(0, P[:, 1].min())), int(min(VH - 1, P[:, 1].max() + 1))
            if x1 < x0 or y1 < y0:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            d = (P[1, 1] - P[2, 1]) * (P[0, 0] - P[2, 0]) + (P[2, 0] - P[1, 0]) * (P[0, 1] - P[2, 1])
            if abs(d) < 1e-9:
                continue
            w0 = ((P[1, 1] - P[2, 1]) * (gx - P[2, 0]) + (P[2, 0] - P[1, 0]) * (gy - P[2, 1])) / d
            w1 = ((P[2, 1] - P[0, 1]) * (gx - P[2, 0]) + (P[0, 0] - P[2, 0]) * (gy - P[2, 1])) / d
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not inside.any():
                continue
            z = w0 * Z[i] + w1 * Z[j] + w2 * Z[k]
            sub = zb[y0:y1 + 1, x0:x1 + 1]
            vis = inside & (z < sub)
            if not vis.any():
                continue
            if p['tex'] is not None:
                uv = p['uv']
                u = w0 * uv[i, 0] + w1 * uv[j, 0] + w2 * uv[k, 0]
                vv = w0 * uv[i, 1] + w1 * uv[j, 1] + w2 * uv[k, 1]
                th, tw, _ = p['tex'].shape
                tx = np.clip((u * (tw - 1)).astype(int), 0, tw - 1)
                ty = np.clip(((1 - vv) * (th - 1)).astype(int), 0, th - 1)
                texel = p['tex'][ty, tx]
                col = texel[..., :3] * (p['rgba'][:3] / 255.0)
                ta = texel[..., 3] / 255.0
            else:
                col = np.broadcast_to(p['rgba'][:3], gx.shape + (3,))
                ta = np.ones(gx.shape)
            col = col * shade
            tgt = img[y0:y1 + 1, x0:x1 + 1]
            if p['blend']:
                al = (p['rgba'][3] / 255.0 * ta)[vis][:, None]
                gloss = 0 if p['name'] == 'contact_shadow' else 40
                tgt[vis] = tgt[vis] * (1 - al) + col[vis] * shade * al + gloss * al
            else:
                tgt[vis] = col[vis]
                sub[vis] = z[vis]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def main():
    out, paths = sys.argv[1], sys.argv[2:]
    sheet = Image.new('RGB', (3 * VW, len(paths) * (VH + 22)), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    for r, path in enumerate(paths):
        ps = parts(path)
        for c, (yaw, label) in enumerate(((0, 'front'), (45, '45 deg'), (90, 'side'))):
            sheet.paste(render(ps, yaw), (c * VW, r * (VH + 22) + 22))
            d.text((c * VW + 8, r * (VH + 22) + 5), f'{path.replace(chr(92), "/").split("/")[-1]}  {label}', fill=(0, 0, 0))
    sheet.save(out)


if __name__ == '__main__':
    main()
