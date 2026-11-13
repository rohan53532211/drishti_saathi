"""Copy a varied set of generated 3D frames into the APK assets for offline demos.

Usage: python -P -E tools/bundle_frames3d.py <generated_dir> <app_assets_dir> [--eyeglasses 24] [--sunglasses 6]

Picks round-robin over shapes (best quality first within each shape) so the bundled set shows every shape,
then writes <app_assets_dir>/frames3d/frames_catalog.json with glb/ and thumb/ next to it.
The output folder is git-ignored (Kaggle licence unknown, demo use only); rerun after frames_to_glb.py.
"""
import argparse
import json
import os
import shutil
from collections import defaultdict


def pick(frames, n):
    by_shape = defaultdict(list)
    for f in sorted(frames, key=lambda f: -f.get('quality', 0)):
        by_shape[f['shape']].append(f)
    out = []
    while len(out) < n and any(by_shape.values()):
        for shape in sorted(by_shape, key=lambda s: -len(by_shape[s])):
            if by_shape[shape] and len(out) < n:
                out.append(by_shape[shape].pop(0))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('generated')
    ap.add_argument('assets')
    ap.add_argument('--eyeglasses', type=int, default=24)
    ap.add_argument('--sunglasses', type=int, default=6)
    a = ap.parse_args()
    cat = json.load(open(os.path.join(a.generated, 'frames_catalog.json'), encoding='utf-8'))
    eye = [f for f in cat['frames'] if f['category'] == 'eyeglasses']
    sun = [f for f in cat['frames'] if f['category'] == 'sunglasses']
    chosen = pick(eye, a.eyeglasses) + pick(sun, a.sunglasses)
    out = os.path.join(a.assets, 'frames3d')
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, 'glb'))
    os.makedirs(os.path.join(out, 'thumb'))
    total = 0
    for f in chosen:
        for k in ('glb', 'thumb'):
            shutil.copyfile(os.path.join(a.generated, f[k]), os.path.join(out, f[k]))
            total += os.path.getsize(os.path.join(out, f[k]))
    json.dump(dict(version=cat.get('version', 1), licence=cat.get('licence', ''), frames=chosen),
              open(os.path.join(out, 'frames_catalog.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    shapes = defaultdict(int)
    for f in chosen:
        shapes[f['shape']] += 1
    print(f'{len(chosen)} frames, {total / 1e6:.2f} MB, shapes {dict(shapes)}')


if __name__ == '__main__':
    main()
