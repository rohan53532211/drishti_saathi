"""Build the app's bundled fonts: Inter (Latin) + Noto Sans Devanagari (Hindi) merged into one font per weight.

Compose does not fall back glyph by glyph between fonts in one FontFamily (it falls back to the phone's system
font), so Hindi text with Latin parts ("PD", "mm", digits) needs both scripts in one file. Also merged in: the few
arrows / maths signs and the tick and cross the UI uses, which the text fonts lack (from Noto Sans Math and Noto Sans
Symbols 2).

Sources (all SIL OFL 1.1):
  Inter[opsz,wght].ttf            https://github.com/google/fonts/tree/main/ofl/inter  (saved as Inter-VF.ttf)
  NotoSansDevanagari-<W>.ttf      https://github.com/notofonts/notofonts.github.io  fonts/NotoSansDevanagari/unhinted/ttf
  NotoSansMath-Regular.ttf, NotoSansSymbols2-Regular.ttf   same repository

Usage:
    python -I tools/build_fonts.py <src_dir> <out_res_font_dir>
Needs fontTools (pip install fonttools). Writes inter_deva_<weight>.ttf (lower case: Android resource names).

Inter is a variable font: each weight is instanced at optical size 14 and scaled from 2048 to 1000 units per em, so it
can be merged with Noto Sans Devanagari. Inter's tabular figures (the "tnum" feature) stay in the merged font.
"""
import os
import sys
import tempfile

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem
from fontTools.varLib import instancer

WEIGHTS = {"Regular": 400, "Medium": 500, "SemiBold": 600, "Bold": 700}
UPEM = 1000

LATIN = list(range(0x20, 0x7F)) + list(range(0xA0, 0x250)) + list(range(0x2000, 0x2070)) + \
    list(range(0x20A0, 0x20C1)) + [0x2122, 0x2116, 0x2212, 0x25CC, 0xFEFF, 0xFFFD]
DEVANAGARI = list(range(0x0900, 0x0980)) + list(range(0xA8E0, 0xA900)) + list(range(0x1CD0, 0x1D00)) + \
    [0x200B, 0x200C, 0x200D, 0x25CC, 0x0964, 0x0965, 0x20B9]
MATH = [0x2190, 0x2191, 0x2192, 0x2193, 0x2194, 0x2248, 0x2260, 0x2264, 0x2265]
SYMBOLS2 = [0x2713, 0x2717]


def subset_font(f, unicodes, dst, layout=True):
    opts = subset.Options()
    opts.layout_features = ["*"] if layout else []
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.drop_tables += ["MATH", "DSIG", "vhea", "vmtx", "STAT", "avar", "fvar", "gvar", "HVAR", "MVAR"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=unicodes)
    s.subset(f)
    f.save(dst)
    return dst


def scaled(path):
    f = TTFont(path)
    if f["head"].unitsPerEm != UPEM:
        scale_upem(f, UPEM)
    return f


def inter_instance(src_dir, weight):
    vf = TTFont(os.path.join(src_dir, "Inter-VF.ttf"))
    axes = {a.axisTag for a in vf["fvar"].axes}
    loc = {"wght": WEIGHTS[weight]}
    if "opsz" in axes:
        loc["opsz"] = 14
    f = instancer.instantiateVariableFont(vf, loc, inplace=False)
    scale_upem(f, UPEM)
    return f


def build(src_dir, out_dir, weight, tmp):
    parts = [
        subset_font(inter_instance(src_dir, weight), LATIN, os.path.join(tmp, f"lat-{weight}.ttf")),
        subset_font(scaled(os.path.join(src_dir, f"NotoSansDevanagari-{weight}.ttf")), DEVANAGARI, os.path.join(tmp, f"dev-{weight}.ttf")),
        subset_font(scaled(os.path.join(src_dir, "NotoSansMath-Regular.ttf")), MATH, os.path.join(tmp, "math.ttf"), layout=False),
        subset_font(scaled(os.path.join(src_dir, "NotoSansSymbols2-Regular.ttf")), SYMBOLS2, os.path.join(tmp, "sym.ttf"), layout=False),
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
    os2.usWeightClass = WEIGHTS[weight]
    for rec in merged["name"].names:
        if rec.nameID in (1, 4, 16):
            rec.string = {1: "Drishti Inter Devanagari", 4: f"Drishti Inter Devanagari {weight}", 16: "Drishti Inter Devanagari"}[rec.nameID]
        elif rec.nameID == 6:
            rec.string = f"DrishtiInterDevanagari-{weight}"
    out = os.path.join(out_dir, f"inter_deva_{weight.lower()}.ttf")
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
            missing = [hex(u) for u in [ord("A"), 0x0915, 0x093F, 0x20B9, 0x2192, 0x2713, 0x2212, 0x2013] if u not in cm]
            scripts = sorted({r.ScriptTag for r in f["GSUB"].table.ScriptList.ScriptRecord})
            feats = sorted({r.FeatureTag for r in f["GSUB"].table.FeatureList.FeatureRecord})
            print(f"{out}: {os.path.getsize(out) // 1024} KB, {len(f.getGlyphOrder())} glyphs, GSUB scripts {scripts}, "
                  f"tnum {'tnum' in feats}, missing {missing}")


if __name__ == "__main__":
    main()
