"""Measure how the English book paginates, and at what size it sets.

The English book has no page boundary of its own to borrow — see
docs/QURAN_TYPOGRAPHY.md §13 and domain/EnglishBook.kt. It paginates itself:
the verses run in the mushaf's order and a leaf closes when the next verse will
not go on it. So the leaf count and the type size are two ends of one number,
the capacity, and this is the sweep that chooses it.

    python3 tools/measure_english_leaves.py

A leaf carries whole verses, because a verse is a sentence and cannot be cut at
a page break. That is the rule englishLeaf() applies, and it is the rule counted
here — along with the one break the packing keeps, Al-Fatihah's.
"""

import sqlite3
import statistics
from collections import defaultdict

# Must track EnglishLeafFit.kt / EnglishBook.kt.
LEADING = 1.40   # one figure, for every leaf in the book
CAPACITY = 900  # what a leaf holds; a page takes as many leaves as it needs
MARK_CHARS = 6  # ENGLISH_LEAF_MARK_CHARS: the verse mark and its two spaces
OPENING_CHARS = 92   # ENGLISH_LEAF_OPENING_CHARS: the panel and its air
BASMALAH_CHARS = 78  # ENGLISH_LEAF_BASMALAH_CHARS: the preface line and its air
NO_BASMALAH = (1, 9)  # Al-Fatihah and At-Tawbah open without one

db = sqlite3.connect("data/quran.db")
rows = db.execute(
    "select surah_id, ayah_number, qcf_page from words "
    "where position = 1 and qcf_page between 1 and 604 "
    "order by qcf_page, surah_id, ayah_number"
).fetchall()
translation = {
    (surah, ayah): text
    for surah, ayah, text in db.execute(
        "select surah_id, ayah_number, translation_en from ayahs"
    )
}
def verse_mass(surah, ayah):
    """englishLeafVerseMass: the prose, and the paper an opening takes."""
    mass = len(translation[(surah, ayah)]) + MARK_CHARS
    if ayah == 1:
        mass += OPENING_CHARS
        if surah not in NO_BASMALAH:
            mass += BASMALAH_CHARS
    return mass


seq = [(surah, ayah, page, verse_mass(surah, ayah)) for surah, ayah, page in rows]

masses = sorted(m for _s, _a, _p, m in seq)
print(f"{len(masses)} verses, characters of set prose")
for q in (50, 90, 99, 100):
    print(f"  p{q:<3} {masses[min(len(masses) - 1, len(masses) * q // 100)]}")


def leaves_at(cap):
    """The book packed continuously — buildEnglishBook."""
    out, run = [], 0
    for surah, ayah, _page, mass in seq:
        if run > 0 and (run + mass > cap or (surah == 2 and ayah == 1)):
            out.append(run)
            run = 0
        run += mass
    out.append(run)
    return out


leaves = leaves_at(CAPACITY)
fills = sorted(100 * m / CAPACITY for m in leaves)
print(f"\nat ENGLISH_LEAF_CAPACITY_CHARS = {CAPACITY}, leading {LEADING}:")
print(f"  leaves                 {len(leaves)}")
print(f"  median leaf fills      {statistics.median(fills):.0f}% of its well")
for q in (10, 25, 50, 75, 90):
    print(f"    p{q:<3} {fills[len(fills) * q // 100]:5.0f}%")
print(f"  leaves under 70% full  {sum(1 for f in fills if f < 70)}")

print("\ncapacity sweep (type against a page-sized leaf, leaves, median fill,")
print("leaves under 70% — a verse too long to join the leaf it met):")
for cap in (750, 800, 850, 900, 950, 1000, 1200, 1650):
    f = sorted(100 * m / cap for m in leaves_at(cap))
    print(f"  {cap:>5}  x{(2160 / cap) ** 0.5:.2f}  {len(f):>5} leaves  "
          f"{statistics.median(f):>3.0f}%  {sum(1 for x in f if x < 70):>3} short")
