"""Measure how much English every Madinah leaf carries.

The English leaf borrows the mushaf's 604 page boundaries and sets what falls
on each of them as a page of a book (see docs/QURAN_TYPOGRAPHY.md §13 and
domain/EnglishLeaf.kt). Its content is therefore fixed by the Arabic, and the
nothing stretches to fill the page: the book is set on one hand and one leading
and a leaf ends where its content ends. What absorbs the range instead is the
leaf *count* — a page takes as many leaves as its English needs at a legible
size. This is the sweep that chooses the capacity: too large and the type
shrinks back toward the old page-bound size, too small and pages split into
half-empty leaves faster than the type grows.

This is the measurement ENGLISH_LEAF_CAPACITY_CHARS comes from. Rerun it if
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

# Must track EnglishLeafFit.kt / EnglishBook.kt.
LEADING = 1.40   # one figure, for every leaf in the book
CAPACITY = 900  # what a leaf holds; a page takes as many leaves as it needs
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

STUB = 0.55  # ENGLISH_LEAF_STUB_FRACTION


def split(masses, cap):
    """Fill the leaf, then carry back a stub — englishPageParts."""
    runs, cur = [], []
    for i, m in enumerate(masses):
        if cur and sum(masses[j] for j in cur) + m > cap:
            runs.append(cur)
            cur = []
        cur.append(i)
    runs.append(cur)
    while len(runs) >= 2:
        above, last = runs[-2], runs[-1]
        ma = sum(masses[i] for i in above)
        mb = sum(masses[i] for i in last)
        if mb >= STUB * cap or len(above) < 2:
            break
        c = masses[above[-1]]
        if abs((ma - c) - (mb + c)) >= abs(ma - mb):
            break
        runs[-2], runs[-1] = above[:-1], [above[-1]] + last
    return [sum(masses[i] for i in r) for r in runs]


page_masses = defaultdict(list)
for surah, ayah, page in opens_on:
    page_masses[page].append(len(translation[(surah, ayah)]) + MARK_CHARS)

print(f"\nat ENGLISH_LEAF_CAPACITY_CHARS = {CAPACITY}, leading {LEADING}:")
leaves = [m for p in page_masses for m in split(page_masses[p], CAPACITY)]
multi = sum(1 for p in page_masses if len(split(page_masses[p], CAPACITY)) > 1)
fills = sorted(100 * m / CAPACITY for m in leaves)
print(f"  leaves                 {len(leaves)}   ({multi} pages take more than one)")
print(f"  median leaf fills      {statistics.median(fills):.0f}% of its well")
for q in (10, 25, 50, 75, 90):
    print(f"    p{q:<3} {fills[len(fills) * q // 100]:5.0f}%")

print("\ncapacity sweep (type against a page-sized leaf, leaves, median fill,")
print("leaves past their capacity — one is 2:282, which nothing can split):")
for cap in (750, 800, 850, 900, 950, 1000, 1200, 1650):
    ls = [m for p in page_masses for m in split(page_masses[p], cap)]
    f = sorted(100 * m / cap for m in ls)
    over = sum(1 for x in f if x > 105)
    print(f"  {cap:>5}  x{(2160 / cap) ** 0.5:.2f}  {len(ls):>5} leaves  "
          f"{statistics.median(f):>3.0f}%  {over:>2} over")
