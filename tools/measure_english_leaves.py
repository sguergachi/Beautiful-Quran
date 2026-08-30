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
LINE_CHARS = CAPACITY // 22       # ENGLISH_LEAF_LINE_CHARS
SPLIT_HOLE = 3 * LINE_CHARS       # ENGLISH_LEAF_SPLIT_HOLE_CHARS
MIN_FRAGMENT = 2 * LINE_CHARS     # ENGLISH_LEAF_MIN_FRAGMENT_CHARS
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


def leaves_at(cap, split=True):
    """buildEnglishBook, in miniature: fill the leaf, carry a long verse over."""
    hole = 3 * (cap // 22)
    frag = 2 * (cap // 22)
    leaves, run, mass = [], [], 0

    def close():
        nonlocal run, mass
        if run:
            leaves.append(run)
            run, mass = [], 0

    for surah, ayah, _page in rows:
        if run and surah == 2 and ayah == 1:
            close()
        if ayah == 1:
            opening = OPENING_CHARS + (BASMALAH_CHARS if surah not in NO_BASMALAH else 0)
            if run and mass + opening > cap:
                close()
            mass += opening
        length = len(translation[(surah, ayah)])
        frm = 0
        while True:
            left, rest = cap - mass, length - frm
            if rest + MARK_CHARS <= left:
                run.append((surah, ayah, frm, length))
                mass += rest + MARK_CHARS
                break
            take = min(left, rest - frag)
            carry = split and left >= hole and take >= frag
            if not carry and run:
                close()
                continue
            cut = take if carry else min(rest, max(left, frag))
            to = length if frm + cut >= length else frm + cut
            run.append((surah, ayah, frm, to))
            frm = to
            if frm >= length:
                mass = cap
                break
            close()
    close()
    return [leaf_mass(r) for r in leaves]


def leaf_mass(runs):
    """What the leaf really sets: its halves of verses, marks and panels."""
    total = 0
    for surah, ayah, frm, to in runs:
        if ayah == 1 and frm == 0:
            total += OPENING_CHARS + (BASMALAH_CHARS if surah not in NO_BASMALAH else 0)
        total += (to - frm)
        if to >= len(translation[(surah, ayah)]):
            total += MARK_CHARS
    return total


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
print(f"  {100 * (1 - full / len(leaves)):.0f}% of the paper is blank.")
whole = leaves_at(CAPACITY, split=False)
we = [blank_lines(m, CAPACITY) for m in whole]
print(f"  with no verse ever carried it would be {len(whole)} leaves and"
      f" {statistics.mean(we):.2f} blank lines,")
print(f"  and {sum(1 for e in we if e > 3)} leaves more than three lines short"
      f" against {sum(1 for e in empty if e > 3)}.")

print("\ncapacity sweep (type against a page-sized leaf, leaves, mean blank lines):")
for cap in (750, 800, 850, 900, 950, 1000, 1200, 1650):
    ls = leaves_at(cap)
    e = [blank_lines(m, cap) for m in ls]
    print(f"  {cap:>5}  x{(2160 / cap) ** 0.5:.2f}  {len(ls):>5} leaves  "
          f"{statistics.mean(e):4.2f} blank")
