"""Measure how much English every Madinah leaf carries.

The English leaf borrows the mushaf's 604 page boundaries and sets what falls
on each of them as a page of a book (see docs/QURAN_TYPOGRAPHY.md §13 and
domain/EnglishLeaf.kt). Its content is therefore fixed by the Arabic, and the
nothing fills the page at all: the book is set on one hand and one leading, and
a leaf simply ends where its content ends. So the hand is cut for the heaviest
leaf in the book — that one has to fit its well — and every lighter leaf stands
short of the foot by exactly as much as it is lighter. This is the measurement
that says where the anchor goes and how short the rest stand.

This is the measurement ENGLISH_LEAF_REFERENCE_PROSE comes from. Rerun it if
the translation or the qcf_page column changes:

    python3 tools/measure_english_leaves.py

A leaf carries the verses that *begin* on the Arabic leaf of the same number:
a verse is a sentence and cannot be cut at a page break, so one that runs over
is set whole on the leaf it starts on. That is the rule englishLeaf() applies,
and it is the rule counted here.
"""

import sqlite3
import statistics
from collections import defaultdict

# Must track EnglishLeafFit.kt.
LEADING = 1.40      # one figure, for every leaf in the book
REFERENCE = 2060.0  # the heaviest leaf, plus 3% for the estimate
MARK_CHARS = 6  # ENGLISH_LEAF_MARK_CHARS: the verse mark and its two spaces

db = sqlite3.connect("data/quran.db")
opens_on = db.execute(
    "select surah_id, ayah_number, qcf_page from words "
    "where position = 1 and qcf_page between 1 and 604"
).fetchall()
translation = {
    (surah, ayah): text
    for surah, ayah, text in db.execute(
        "select surah_id, ayah_number, translation_en from ayahs"
    )
}

prose = defaultdict(int)
verses = defaultdict(int)
for surah, ayah, page in opens_on:
    prose[page] += len(translation[(surah, ayah)]) + MARK_CHARS
    verses[page] += 1

empty = [p for p in range(1, 605) if p not in prose]
print(f"leaves with no verse of their own: {len(empty)} {empty}")
print(f"most verses on one leaf: {max(verses.values())}")

masses = sorted(prose.values())


def pct(q):
    return masses[min(len(masses) - 1, len(masses) * q // 100)]


print(f"{len(masses)} leaves, characters of set prose")
for q in (0, 1, 5, 10, 25, 50, 75, 90, 95, 99, 100):
    print(f"  p{q:<3} {pct(q)}")
print(f"  mean {statistics.mean(masses):.0f}")

print(f"\nat ENGLISH_LEAF_REFERENCE_PROSE = {REFERENCE:.0f}, leading {LEADING}:")
over = sorted(p for p, m in prose.items() if m > REFERENCE)
print(f"  would overflow the well  {len(over):3d}  {over[:8]}")
print(f"  heaviest leaf  page {max(prose, key=prose.get)} "
      f"({max(masses)} chars) fills {100 * max(masses) / REFERENCE:.0f}% of the well")

print("\nhow far down the well a leaf reaches:")
for q in (1, 10, 25, 50, 75, 90, 99, 100):
    print(f"  p{q:<3} {100 * pct(q) / REFERENCE:5.0f}%")

print("\nthe leading buys type, and the type buys leading (hand^2 x leading is fixed):")
for lead in (1.30, 1.35, 1.40, 1.45, 1.50, 1.55):
    print(f"  {lead:.2f} em -> hand x {(LEADING / lead) ** 0.5:.3f} of today's")
