"""Measure every mushaf line with HarfBuzz, in em of its QCF V2 page face.

The reader sets the whole book at one size (MUSHAF_DESIGN_LINE_EM in
MushafPageFit.kt) rather than fitting each leaf to its own longest line, which
made the hand grow and shrink as pages turned. This is the measurement that
constant comes from — rerun it if the page fonts or the qcf_v2 column change:

    python3 -m venv .venv && .venv/bin/pip install uharfbuzz fonttools
    .venv/bin/python tools/measure_mushaf_lines.py

Reads data/quran.db and app/build/generated/qcfAssets/qcf-v2-fonts (run a
Gradle build first so the fonts are unpacked).
"""

import sqlite3, statistics, sys
import uharfbuzz as hb

db = sqlite3.connect('data/quran.db'); c = db.cursor()
c.execute("select qcf_page, qcf_line, qcf_v2 from words where qcf_page between 1 and 604 "
          "order by qcf_page, qcf_line, surah_id, ayah_number, position")
pages = {}
for p, l, q in c.fetchall():
    pages.setdefault(p, {}).setdefault(l, []).append((q or "").replace(" ", ""))

def shape_width(font, text):
    buf = hb.Buffer(); buf.add_str(text); buf.guess_segment_properties()
    hb.shape(font, buf, {})
    return sum(pos.x_advance for pos in buf.glyph_positions)

per_page_max = {}
per_line = []
for p in sorted(pages):
    path = f'app/build/generated/qcfAssets/qcf-v2-fonts/QCF2{p:03d}.qcf'
    blob = hb.Blob.from_file_path(path); face = hb.Face(blob); font = hb.Font(face)
    upem = face.upem
    best = 0.0
    for line, words in pages[p].items():
        # As drawn: each word its own run, concatenated along the line.
        w = sum(shape_width(font, w_) for w_ in words) / upem
        per_line.append((p, line, len(words), w))
        best = max(best, w)
    per_page_max[p] = best

vals = sorted(per_page_max.values())
n = len(vals)
print(f"pages {n}")
print("page longest line (em): min %.2f  p50 %.2f  p90 %.2f  p99 %.2f  max %.2f" %
      (vals[0], vals[n//2], vals[int(.9*n)], vals[int(.99*n)], vals[-1]))
worst = sorted(per_page_max.items(), key=lambda kv: -kv[1])[:8]
print("widest pages:", [(p, round(v, 2)) for p, v in worst])
lw = sorted(w for _, _, _, w in per_line)
m = len(lw)
print("all lines (em): p10 %.2f  p50 %.2f  p90 %.2f  max %.2f" % (lw[int(.1*m)], lw[m//2], lw[int(.9*m)], lw[-1]))
