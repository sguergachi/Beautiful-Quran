"""Measure how much English every Madinah leaf carries.

The English leaf borrows the mushaf's 604 page boundaries and sets what falls
on each of them as a page of a book (see docs/QURAN_TYPOGRAPHY.md §13 and
domain/EnglishLeaf.kt). Its content is therefore fixed by the Arabic, and the
one lever left for filling the page is the leading — which is why the book's
hand is anchored on a *reference page mass* rather than on a line count, and
why that anchor is the heaviest leaf in the book: cut for the worst page, the
hand never has to change and nothing can run past the foot.

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
NOMINAL = 1.55
LEAD_MIN = 1.30
LEAD_MAX = 2.00
REFERENCE = 1675.0
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

print(f"\nat ENGLISH_LEAF_REFERENCE_PROSE = {REFERENCE:.0f}:")
leadings = {page: NOMINAL * REFERENCE / mass for page, mass in prose.items()}
inside = [p for p, lead in leadings.items() if LEAD_MIN <= lead <= LEAD_MAX]
tight = sorted(p for p, lead in leadings.items() if lead < LEAD_MIN)
loose = sorted(p for p, lead in leadings.items() if lead > LEAD_MAX)
print(f"  fill the well outright     {len(inside):3d}  ({100 * len(inside) / len(masses):.1f}%)")
print(f"  would overflow the well    {len(tight):3d}  {tight[:8]}{' ...' if len(tight) > 8 else ''}")
print(f"  foot stands short          {len(loose):3d}  {loose[:8]}{' ...' if len(loose) > 8 else ''}")

worst = min(leadings.items(), key=lambda kv: kv[1])
print(f"  worst leaf page {worst[0]} ({prose[worst[0]]} chars) sets at {worst[1]:.3f} em")

print("\nthe anchor is the worst leaf at the tightest leading:")
print(f"  {max(masses)} x {LEAD_MIN} / {NOMINAL} = {max(masses) * LEAD_MIN / NOMINAL:.0f}")

print("\nreference sweep (fill %, leaves that would overflow, leaves left short):")
for candidate in (1440, 1550, 1600, 1675, 1750, 1800):
    lead = [NOMINAL * candidate / m for m in masses]
    fill = sum(1 for x in lead if LEAD_MIN <= x <= LEAD_MAX)
    print(f"  {candidate}  {100 * fill / len(masses):5.1f}%  "
          f"{sum(1 for x in lead if x < LEAD_MIN):3d}  {sum(1 for x in lead if x > LEAD_MAX):3d}")
