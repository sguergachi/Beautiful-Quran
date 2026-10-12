#!/usr/bin/env python3
"""Builds the app's two Junicode cuts, set to measure as EB Garamond.

    python3 tools/build_junicode_fonts.py /path/to/Junicode/VAR

Takes the variable fonts of the Junicode 2.226 release
(github.com/psb1558/Junicode-font, SIL OFL 1.1, no Reserved Font Name) and
writes app/src/main/res/font/junicode_variable.ttf and
junicode_italic_variable.ttf:

* subset to Latin, its extensions and the transliteration marks, unhinted;
* the decorative enlargement axis (ENLA) pinned off;
* the width axis pinned where a sentence of letters sets to EB Garamond's
  length at the app's size for Junicode (JUNICODE_SIZE in ui/theme/Type.kt):
  104 roman, 113 italic;
* the word space set to Garamond's 0.200 em at that size. Junicode's own is
  0.244 em, 18 % wider, and at matched letter widths it opened every line.

Only weight stays variable. Why each number: docs/DESIGN.md, "Type".
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "app/src/main/res/font"
JUNICODE_SIZE = 0.969
SPACE = round(200 / JUNICODE_SIZE)
CUTS = {
    "JunicodeVF-Roman.ttf": ("junicode_variable.ttf", 104),
    "JunicodeVF-Italic.ttf": ("junicode_italic_variable.ttf", 113),
}
FEATURES = [
    "kern", "liga", "clig", "ccmp", "locl", "mark", "mkmk", "calt", "rlig", "rclt",
    "lnum", "onum", "pnum", "tnum", "case", "frac", "numr", "dnom", "sups", "subs", "ordn",
]


def unicodes():
    cps = set()
    for name in ("eb_garamond_regular.ttf", "eb_garamond_italic.ttf"):
        cps |= set(TTFont(FONTS / name).getBestCmap())
    for lo, hi in ((0x20, 0x250), (0x2B0, 0x370), (0x1E00, 0x1F00), (0x2000, 0x2070), (0x20A0, 0x20C0)):
        cps |= set(range(lo, hi))
    return cps | {0x2190, 0x2192, 0x2212, 0x25CC, *range(0xFB00, 0xFB05)}


def build(source: Path, target: Path, width: int, cps) -> None:
    font = TTFont(source)
    options = subset.Options()
    options.layout_features = FEATURES
    options.hinting = False
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=cps)
    subsetter.subset(font)
    # Instancing straight after subsetting trips over lazily loaded tables;
    # a round trip through bytes settles them.
    font = TTFont(_round_trip(font))
    font = TTFont(_round_trip(instantiateVariableFont(font, {"ENLA": 0, "wdth": width})))
    cmap = font.getBestCmap()
    for cp in (0x20, 0xA0):
        glyph = cmap[cp]
        font["hmtx"][glyph] = (SPACE, font["hmtx"][glyph][1])
        font["gvar"].variations[glyph] = []
    # HVAR would vary the old space; advances fall back to gvar's phantom points.
    del font["HVAR"]
    font.save(target)


def _round_trip(font):
    from io import BytesIO
    buffer = BytesIO()
    font.save(buffer)
    buffer.seek(0)
    return buffer


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    var = Path(sys.argv[1])
    cps = unicodes()
    for source, (target, width) in CUTS.items():
        build(var / source, FONTS / target, width, cps)
        print(f"{target}: wdth {width}, space {SPACE}, {(FONTS / target).stat().st_size} bytes")


if __name__ == "__main__":
    main()
