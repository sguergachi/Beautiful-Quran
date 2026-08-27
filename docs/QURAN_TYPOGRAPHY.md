# Setting the mushaf: the rules

What a Qur'ān page is, typographically, and what we are therefore not free to
change. Each rule says where it comes from, what we do about it, and — where we
have measured the mushaf ourselves — the number that settles it.

The measurements are ours: taken with HarfBuzz and fontTools over the QCF V2
page faces and `data/quran.db`, sampling every page or every twelfth page
depending on the cost. They are reproducible with the tools in `tools/`.

---

## 1. The page is the unit, not the verse

The Madinah mushaf is 604 pages of 15 lines, and every copy breaks at the same
word. A reader who has memorised by page expects the page to end where it has
always ended. The page assignment in `data/quran.db` (`qcf_page`) is therefore
authority; `qcf_line` remains the source composition from which the larger
reader hand is reflowed within that boundary.

*Pages 1–2 are the exception: al-Fātiḥah and the opening of al-Baqarah are set
in decorated frames with fewer lines.*

**We follow the page boundary.** `MushafCatalog` builds the same 604 leaves
straight from those columns, and `tools/fetch_mushaf_lines.py` cross-checks the
words on every page against quran.com's published layout. For the reader's
larger hand, `reflowMushafPage` balances those same words over one additional
visual line inside each leaf; it never moves a word to another page and keeps
every chapter opening as a hard boundary.

## 2. One hand for the whole book

The type size is a property of the book, not of the page. A leaf whose type
grew or shrank against its neighbour reads as a fault, however well each leaf
is set on its own.

**We follow it.** `MUSHAF_DESIGN_LINE_EM` fixes one size for all 604 pages;
nothing sizes type per page or per line. `MUSHAF_TYPE_SCALE` applies the same
16/15 enlargement to that fitted hand on every leaf. Each page's unchanged
content is then balanced over one more visual line, so the larger type wraps
instead of being narrowed back into the original fifteen rows. The
sixteenth row takes the paper formerly reserved by the wide head and tail
gutters; it is not squeezed into the old fifteen-row well.

## 3. Every full line is flush

A mushaf line runs margin to margin. The calligrapher fills it — this is the
whole art of the page — and a digital setting must do the same. The one line
that may stand short is a chapter's last, where the text simply runs out.

Quran.com's own reading view sets `text-align: justify; text-align-last:
justify`, i.e. flush including the final line of each element.

**We follow it.** Lines reach the measure by space and by letterform (rule 4),
and where neither alone suffices the letters are held at their bound and the
space carries the rest. Measured, 1.2% of lines cannot reach it even so — four-
and five-word lines, a chapter's last — and only those are centred.

## 4. The line is filled by the letterform, not by the space

This is the deepest difference between Arabic and Latin setting. Latin
justification stretches word spaces. Arabic justification elongates letters —
*kashīda* — because the script is built on horizontal strokes whose length is
flexible by design. Reading University's TypoArabic research names the three
historical means as letterform variation, density change, and word
configuration; word spacing is not among them. Skilled scribes adjusted
proportion and spacing invisibly *before* resorting to visible elongation.

Note the distinction the same research draws: *kashīda* is the calligraphic
elongation of a stroke; *taṭwīl* (U+0640) is a straight extension character, a
technological artifact rather than a feature of the script.

**We follow it as far as the medium allows.** We cannot kashida — the QCF faces
carry each word as one drawn glyph — so a line reaches the measure by narrowing
or stretching the whole letterform, bounded at 0.80 and 1.15. The word space is
chosen first and given up only within bounds (rule 5).

## 5. The word space is chosen, never left over

Measured over 5,707 word joins, the page faces leave 0.044 em of air between
words at the median, and **negative** air at a quarter of them — Arabic letters
nest. So the space between words does not come from the font. It is entirely
the renderer's, and it must be *decided*.

Deciding it by subtraction — fit the line, divide what remains — is what
produces both failures at once. Measured over 738 lines under that rule: 36.6%
of lines fell under 0.10 em, the tightest tenth went **negative** (one word's
ink inside the next), and the loosest hundredth reached 1.17 em, a river.

**Our rule.** The page is set on one word space, `MUSHAF_WORD_GAP_EM` = 0.18 em.
A line too wide closes it to 0.13 em before any letter is touched; a line too
narrow opens it to 0.30 em before any letter is stretched. Past those, the
letterform gives — and past *that*, on a line that still will not reach its
margin, the letters stop at 1.15 and the space opens to 0.45 em rather than let
the line stand short (rule 3). Result over the same lines: 52% of the page keeps
its letterforms exactly as drawn.

## 6. The space is measured between ink, not between boxes

A word's advance box is not where its ink is: these faces have side bearings
that vary enormously, and one word's ink routinely overhangs its box by 0.32 em.
Spacing by advance therefore *looks* uneven even when it is arithmetically
equal — on one measured line it put visual gaps of 0.21, **−0.48**, 0.27, 0.40,
0.20 and 0.17 em, two words overlapping while their neighbours drifted apart.

**We follow it.** Lines are laid out from the ink bounds of each word, measured
from the page's own face. On device this took the variation in word gaps on a
page from sd 5.7px to sd 1.8px.

## 7. Words are never broken, and never reordered

No hyphenation, no reflow, no wrapping. A line holds the words the print gives
it, in order, and if they do not fit, rule 4 applies.

**We follow it.** A line's tokens come from the page data and are only ever
scaled or spaced.

## 8. The verse mark belongs to the line

The circled ayah number is set *in* the line and takes its own space in the
justification, with air either side. It is not punctuation hung off the last
word: glued to the word it took the gap on one side only, and the page read
lopsided around every verse.

**We follow it.** The mark is its own cell of the line, and it is levelled with
the rest — but not on a word's terms. The clearance floors exist so that two
words never read as one; a closed gold roundel carries no letterform and cannot
weld to anything, so it is held only far enough off not to touch
(`MUSHAF_MARK_MIN_WHITE_EM`). And the levelling reads the paper a join carries
down the rows its two sides share, of which a mark — a short glyph sitting on
the baseline — shares about half what a word does, missing the open band above
and below it that the eye plainly sees. Measured over forty pages, both faults
together set the mark's ink 0.373 em from its neighbours where two words stood
0.132 em apart: the roundel stood further from its own verse than the words of
that verse stood from each other. `MUSHAF_MARK_WHITE_K` levels it at 0.85 of the
line's white, which sets it the same distance as a word.

A share alone is a straight line, and on a sparse line — one verse ending and the
next beginning on the same measure, four or five words to fill it — a straight
line still opens the mark too far: every join on such a line is set wide, and
because the roundel is short and round the white above and below it joins the
white beside it and reads as a hole in the verse. Past
`MUSHAF_MARK_WHITE_KNEE_EM` (0.42 em) the mark keeps only
`MUSHAF_MARK_WHITE_SLOPE` (0.45) of each further em, so it still opens as its
line opens — an absolute cap was tried first and read worse, the mark pinned at
0.1 em beside words set half an em apart — but it is never the widest join on
its own line. Below the knee nothing moves: over a hundred pages the median mark
join is unchanged and only the top of the distribution comes in, 0.277 em at the
95th percentile to 0.222.

## 9. Leading is set by the ink, not by the nominal size

These faces mark up to 1.368 em above the baseline and 0.747 em below: a line
of this book stands 2.12 em tall whatever leading is claimed for it. Measured
over 817 adjacent line pairs, 8% need more room than the printed page's own
1.85 em leading gives — the print can afford that leading only because the
calligrapher composed each page so that a descender never falls above an
ascender. A renderer cannot rearrange a line and must therefore leave room.

**We follow it.** `MUSHAF_LINE_INK_EM` = 2.20 caps the type against the paper
each line is given, including the reader's own text-size nudge.

## 10. Headers and the basmalah are centred; nothing else is

The chapter's ʿunwān and the basmalah beneath it are display lines, set in the
middle of the measure. Quran.com's page data marks them as distinct line types
for exactly this reason (`surah_name`, `basmallah`, `ayah`).

**We follow it.** The band and the basmalah are centred; ayah lines are flush.

## 11. The basmalah is written in the page's own hand

It is not body text and not a stand-in from another face: the QCF set carries it
as a single drawn glyph (`QCF2BSML`, U+00F3), as it carries each chapter's
header (U+00F2). Set in a different face it arrives in a different hand from
every line beneath it.

**We follow it.** See `MUSHAF_BASMALAH_GLYPH`; its size is matched to the page's
hand by ink height, not by nominal size.

## 12. Illumination is gold; the text is ink

Verse marks, the chapter band and the marks of juzʾ, ḥizb and sajda are
illumination and carry gold. The revelation itself is ink. A running head is a
finding aid, not illumination, and takes ink at low strength — gold on cream
has too little contrast to read.

**We follow it.** See `LocalQuranAccents.gold` and the running head's ink.

---

## What we cannot reproduce

**Kashīda.** The print fills a line by elongating strokes within words. The QCF
faces give us one glyph per word, so our only lever is scaling the whole
letterform. This is the single largest divergence from the print, and it is a
property of the font format, not of our layout.

**Page images.** Quran for Android — the reference most people compare against —
does not set type at all: its pages are images from the quran.com-images
project, so its spacing and letterforms are the calligrapher's and nothing is
computed. Fidelity like that is not available to a renderer that needs to know
where each word is, which we do, for the ink and for the tap.

---

## Sources

- [On Arabic justification, part 1 — TypoArabic, University of Reading](https://research.reading.ac.uk/typoarabic/on-arabic-justification-part-1/)
- [On Arabic Justification — Journal of Electronic Publishing](https://quod.lib.umich.edu/j/jep/3336451.0023.104/--on-arabic-justification?rgn=main;view=fulltext)
- [Kashida — Wikipedia](https://en.wikipedia.org/wiki/Kashida)
- [Uthman Taha Quran — Wikipedia](https://en.wikipedia.org/wiki/Uthman_Taha_Quran)
- [King Fahd Complex for the Printing of the Holy Quran — Wikipedia](https://en.wikipedia.org/wiki/King_Fahd_Complex_for_the_Printing_of_the_Holy_Quran)
- [Rendering Precision: Building a Digital Quran Mushaf — quranportal.io](https://quranportal.io/blog/rendering-the-quran-mushaf-digitally)
- [Page Layout API Guide — Quran Foundation](https://api-docs.quran.foundation/docs/tutorials/fonts/page-layout/)
- [quran_android credits (madani page images)](https://github.com/quran/quran_android)
