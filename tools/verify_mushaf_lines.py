"""Measure how evenly the mushaf's lines are set, and against what.

The Madinah page is set to one measure: every full line of the printed page is
the same width, and the QCF V2 page fonts carry exactly the glyphs that make it
so. That gives a precise, programmatic test of our page data — no screenshots,
no eyeballing:

    shape each line's glyph run with HarfBuzz, in em of its own page face,
    and ask how far it sits from that page's median line.

A page whose data is right measures within a couple of percent end to end. A
page carrying a word on the wrong side of a line break shows the signature
plainly: one line short, the next long, the pair averaging out.

    .venv/bin/python tools/verify_mushaf_lines.py            # whole mushaf
    .venv/bin/python tools/verify_mushaf_lines.py 281 3      # named pages

Needs `uharfbuzz`, `data/quran.db`, and the page fonts unpacked by a Gradle
build (app/build/generated/quranAssets/qcf-v2-fonts).
"""

from __future__ import annotations

import sqlite3
import statistics
import sys
from pathlib import Path

import uharfbuzz as hb

DB = Path("data/quran.db")
FONTS = Path("app/build/generated/quranAssets/qcf-v2-fonts")
PAGES = 604

# A line more than this off its page's median is not a line the print would set.
TOLERANCE = 0.05


def line_runs(db: sqlite3.Connection, page: int) -> dict[int, str]:
    rows = db.execute(
        "select qcf_line, qcf_v2 from words where qcf_page=? "
        "order by qcf_line, surah_id, ayah_number, position",
        (page,),
    )
    runs: dict[int, list[str]] = {}
    for line, glyphs in rows:
        runs.setdefault(line, []).append((glyphs or "").replace(" ", ""))
    return {line: "".join(parts) for line, parts in runs.items()}


def shaped_em(font: hb.Font, upem: int, text: str) -> float:
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {})
    return sum(pos.x_advance for pos in buf.glyph_positions) / upem


def page_widths(db: sqlite3.Connection, page: int) -> list[tuple[int, float]]:
    blob = hb.Blob.from_file_path(str(FONTS / f"QCF2{page:03d}.qcf"))
    face = hb.Face(blob)
    font = hb.Font(face)
    return [
        (line, shaped_em(font, face.upem, run))
        for line, run in sorted(line_runs(db, page).items())
    ]


def main(argv: list[str]) -> int:
    db = sqlite3.connect(DB)
    pages = [int(a) for a in argv[1:]] or list(range(1, PAGES + 1))
    verbose = len(argv) > 1

    off_total = 0
    line_total = 0
    pairs = 0
    bad_pages: list[tuple[int, int]] = []
    all_widths: list[float] = []

    for page in pages:
        widths = page_widths(db, page)
        if len(widths) < 10:  # short leaves have nothing to be even about
            continue
        median = statistics.median(w for _, w in widths)
        all_widths += [w for _, w in widths]
        devs = [(line, w, (w - median) / median) for line, w in widths]
        off = [d for d in devs if abs(d[2]) > TOLERANCE]
        line_total += len(widths)
        off_total += len(off)
        if off:
            bad_pages.append((page, len(off)))
        for a, b in zip(devs, devs[1:]):
            lo, hi = sorted((a, b), key=lambda d: d[2])
            if lo[2] < -TOLERANCE < TOLERANCE < hi[2] and abs(
                (lo[1] + hi[1]) / 2 - median
            ) / median < 0.03:
                pairs += 1
        if verbose:
            print(f"page {page}: median {median:.2f} em")
            for line, w, dev in devs:
                mark = "  <-- off" if abs(dev) > TOLERANCE else ""
                print(f"   line {line:2d}  {w:5.2f} em  {dev:+6.1%}{mark}")

    all_widths.sort()
    n = len(all_widths)
    print()
    print(f"lines measured                : {line_total}")
    print(f"more than {TOLERANCE:.0%} off their page's median: "
          f"{off_total} ({off_total / max(line_total, 1):.1%})")
    print(f"pages with at least one       : {len(bad_pages)} of {len(pages)}")
    print(f"short/long adjacent pairs     : {pairs}   (a word on the wrong line)")
    if n:
        print(f"line width p25/p50/p75        : "
              f"{all_widths[n // 4]:.2f} / {all_widths[n // 2]:.2f} / "
              f"{all_widths[3 * n // 4]:.2f} em")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
