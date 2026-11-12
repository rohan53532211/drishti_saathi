"""Build the app's bundled fonts: Noto Sans (Latin) + Noto Sans Devanagari merged into one font per weight.

Compose does not fall back glyph by glyph between fonts in one FontFamily (it falls back to the phone's system
font), so Hindi text with Latin parts ("PD", "mm", digits) needs both scripts in one file. Also merged in: the few
arrows / maths signs and ✓ ✗ the UI uses, which Noto Sans lacks (from Noto Sans Math and Noto Sans Symbols 2).

Usage (fonts from https://github.com/notofonts/notofonts.github.io, unhinted/ttf; SIL OFL 1.1):
    python -I tools/build_fonts.py <src_dir> <out_res_font_dir>
src_dir must hold NotoSans-<W>.ttf, NotoSansDevanagari-<W>.ttf for W in Regular Medium SemiBold Bold,
plus NotoSansMath-Regular.ttf and NotoSansSymbols2-Regular.ttf. Needs fontTools (pip install fonttools).
Writes noto_<weight>.ttf (lower case: Android resource names).
"""
import os
import sys
import tempfile

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont

WEIGHTS = ["Regular", "Medium", "SemiBold", "Bold"]

LATIN = list(range(0x20, 0x7F)) + list(range(0xA0, 0x250)) + list(range(0x2000, 0x2070)) + \
    list(range(0x20A0, 0x20C1)) + [0x2122, 0x2116, 0x2212, 0x25CC, 0xFEFF, 0xFFFD]
DEVANAGARI = list(range(0x0900, 0x0980)) + list(range(0xA8E0, 0xA900)) + list(range(0x1CD0, 0x1D00)) + \
    [0x200B, 0x200C, 0x200D, 0x25CC, 0x0964, 0x0965, 0x20B9]
MATH = [0x2190, 0x2191, 0x2192, 0x2193, 0x2194, 0x2248, 0x2260, 0x2264, 0x2265]
SYMBOLS2 = [0x2713, 0x2717]


def subset_to(src, unicodes, dst, layout=True):
    opts = subset.Options()
    opts.layout_features = ["*"] if layout else []
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.drop_tables += ["MATH", "DSIG", "vhea", "vmtx"]
    f = TTFont(src)
    s = subset.Subsetter(opts)
    s.populate(unicodes=unicodes)
    s.subset(f)
    f.save(dst)
    return dst


def build(src_dir, out_dir, weight, tmp):
    parts = [
        subset_to(os.path.join(src_dir, f"NotoSans-{weight}.ttf"), LATIN, os.path.join(tmp, f"lat-{weight}.ttf")),
        subset_to(os.path.join(src_dir, f"NotoSansDevanagari-{weight}.ttf"), DEVANAGARI, os.path.join(tmp, f"dev-{weight}.ttf")),
        subset_to(os.path.join(src_dir, "NotoSansMath-Regular.ttf"), MATH, os.path.join(tmp, "math.ttf"), layout=False),
        subset_to(os.path.join(src_dir, "NotoSansSymbols2-Regular.ttf"), SYMBOLS2, os.path.join(tmp, "sym.ttf"), layout=False),
    ]
    fonts = [TTFont(p) for p in parts]
    # line metrics: tall enough for both scripts (Devanagari matras sit higher and lower than Latin)
    asc = max(f["hhea"].ascent for f in fonts[:2])
    desc = min(f["hhea"].descent for f in fonts[:2])
    win_asc = max(f["OS/2"].usWinAscent for f in fonts[:2])
    win_desc = max(f["OS/2"].usWinDescent for f in fonts[:2])

    merged = Merger().merge(parts)
    merged["hhea"].ascent, merged["hhea"].descent = asc, desc
    os2 = merged["OS/2"]
    os2.sTypoAscender, os2.sTypoDescender = asc, desc
    os2.usWinAscent, os2.usWinDescent = win_asc, win_desc
    for rec in merged["name"].names:
        if rec.nameID in (1, 4, 16):
            rec.string = {1: "Drishti Noto Sans", 4: f"Drishti Noto Sans {weight}", 16: "Drishti Noto Sans"}[rec.nameID]
        elif rec.nameID == 6:
            rec.string = f"DrishtiNotoSans-{weight}"
    out = os.path.join(out_dir, f"noto_{weight.lower()}.ttf")
    merged.save(out)
    return out


def main():
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for w in WEIGHTS:
            out = build(src_dir, out_dir, w, tmp)
            f = TTFont(out)
            cm = f.getBestCmap()
            missing = [hex(u) for u in [ord("A"), 0x0915, 0x093F, 0x20B9, 0x2192, 0x2713, 0x2212] if u not in cm]
            scripts = sorted({r.ScriptTag for r in f["GSUB"].table.ScriptList.ScriptRecord})
            print(f"{out}: {os.path.getsize(out) // 1024} KB, {len(f.getGlyphOrder())} glyphs, GSUB scripts {scripts}, missing {missing}")


if __name__ == "__main__":
    main()
