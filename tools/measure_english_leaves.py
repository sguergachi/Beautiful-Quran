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
# Fitted on device against eleven real leaves (see QURAN_TYPOGRAPHY.md 13.4).
# The hand is cut so that a leaf of exactly CAPACITY fills the well, so the
# blank at the foot is simply the share of the capacity the leaf did not use.
# WELL_LINES is what that well comes to on a phone; it is only here to say the
# answer in lines, which is the unit a reader sees.
WELL_LINES = 22.0
CAPACITY = 900  # what a leaf holds; a page takes as many leaves as it needs
MARK_CHARS = 3  # ENGLISH_LEAF_MARK_CHARS: the verse mark and its two spaces
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
print(f"  median leaf fills      {statistics.median(fills):.0f}% of its capacity")

# What the reader actually sees: the foot of the page.
def blank_lines(mass, cap):
    return max(0.0, (1 - mass / cap) * WELL_LINES)


empty = sorted(blank_lines(m, CAPACITY) for m in leaves)
print(f"\nblank lines at the foot, out of {WELL_LINES:.0f}:")
print(f"  mean   {statistics.mean(empty):4.2f}")
for q in (50, 75, 90, 95, 99):
    print(f"  p{q:<3}   {empty[len(empty) * q // 100]:4.1f}")
print(f"  leaves with more than 3 blank    {sum(1 for e in empty if e > 3)}")

# The floor: what the book would take if a verse could be split across leaves.
full = sum(leaves) / CAPACITY
print(f"\nthe book needs {full:.0f} full leaves of paper and takes {len(leaves)}:")
print(f"  {100 * (1 - full / len(leaves)):.0f}% of the paper is blank, and all of it is"
      " the whole verse -")
print("  a leaf ends when the next verse will not go on it, and a verse averages"
      " three lines.")

print("\ncapacity sweep (type against a page-sized leaf, leaves, mean blank lines):")
for cap in (750, 800, 850, 900, 950, 1000, 1200, 1650):
    ls = leaves_at(cap)
    e = [blank_lines(m, cap) for m in ls]
    print(f"  {cap:>5}  x{(2160 / cap) ** 0.5:.2f}  {len(ls):>5} leaves  "
          f"{statistics.mean(e):4.2f} blank")
