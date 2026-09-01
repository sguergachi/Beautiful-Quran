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
in decorated frames with fewer lines, as a centred medallion — shorter lines
at the crown and foot, the block in the middle of the page.*

**We follow the page boundary.** `MushafCatalog` builds the same 604 leaves
straight from those columns, and `tools/fetch_mushaf_lines.py` cross-checks the
words on every page against quran.com's published layout. For the reader's
larger hand, `reflowMushafPage` balances those same words over one additional
visual line inside each leaf; it never moves a word to another page and keeps
every chapter opening as a hard boundary. Pages 1–2 are not reflowed: the
print's own line breaks *are* the circle, and stretching them into even rows
destroys it. Line geometry is cached by those reflowed words and the page
face, not by row number — the same slot is a different token list after the
face lands.

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

No hyphenation. Words stay in the print's order, on the print's page. A word
is never split across a line end, and the sequence is never shuffled. If a
display row still will not fit, rule 4 applies.

What *does* move is the display row. `reflowMushafPage` regroups those same
tokens inside the leaf so the larger hand fits one extra visual line. It never
moves a word to another page. Pages 1–2 are not regrouped: their printed
breaks *are* the medallion.

**We follow it.** A token is never cut or reordered. Page boundaries stay put.
Display rows may regroup the print's tokens inside a leaf (except pages 1–2);
those rows are then only scaled or spaced.

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

**And the panel is ruled to one line's ink, not to one line's slot.** A slot is
a line's ink and the leading around it. Ruling the panel to the whole slot —
0.94 of it, so three percent of air a side — left it hard against its
neighbours: measured on the last leaf of the Qur'an, where three chapters open,
the line above each panel closed to 10–16 px of air while the basmalah below
stood at 23. Cramped, and visibly tighter on one side than the other.

Ruled to the ink alone (`MushafPanelBand` = 0.72) the slot's own leading becomes
the panel's air, half above and half below, equal by construction: 23–29 px
above and 36 px below on the same leaf, and what is left is glyph slack — the
line above may end on a descender or an ayah mark where the basmalah below does
not. Against a token gap that slack was the whole difference; against a seventh
of a line it is not something the eye picks out.

The name comes down with the band (`MushafPanelType` = 0.95, under the page's
own hand rather than the step above it the deeper band could carry), because the
cartouche is a quarter shallower and a name set larger than its band stops
fitting inside it.

Nothing else moves. The slot is the same slot, so an opening still costs the
grid one line and the hand is still the one hand of all 604 pages (§2); only the
rules inside the slot come in. This is the same law the English leaf sets the
same panel by — see §13.7, which rules its band to one line's *measured* ink and
centres it in a box of that plus `EnglishLeafPanelAir` on each side. One panel,
one grammar, whichever language the leaf is set in.

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

## 13. The English leaf

The same book, in the reader's language. Rules 1–12 are about the Arabic page;
this section is the whole of what changes when the leaf is set in English, and
nothing here overrides them — the Arabic leaf is unaffected.

*Code: `domain/EnglishLeaf.kt` (what a leaf carries), `domain/EnglishLeafFit.kt`
(what it is set in), `ui/reader/MushafEnglishSheet.kt` (how it is drawn).
Measurements: `tools/measure_english_leaves.py`.*

### 13.1 The book paginates itself

The translation has no pagination of its own — no printing of it breaks where
every other printing breaks. It borrowed the mushaf's for a while: an English
leaf carried the verses that **began** on the Arabic leaf of the same number, so
page 255 in English opened where page 255 opened.

**That boundary is gone, and it was the whitespace.** A borrowed boundary is
still a boundary: every Madinah page ended on a remainder, and once the type was
large enough that a page took two or three leaves, roughly half of every leaf in
the book *was* a remainder. Measured, 365 of 1,254 leaves came out under 70%
full — a reader met a third of a blank page every other turn. The verses are
now packed continuously, straight through the Arabic page breaks, and the same
type gives 1,145 leaves at 91% full with 77 short ones instead of 365.

What is still true, and is what the leaf actually rests on: a verse is a
sentence, and a sentence cannot be cut at whatever word the calligrapher reached
at the foot of *his* page. Verses are set whole across the Arabic break, in the
mushaf's own order, so the English runs continuously with nothing repeated and
nothing dropped. At the English book's own break it is different — that break is
the book's to place, and §13.4 places it.

Al-Fatihah is the one break the packing keeps — it opens the book on a leaf of
its own, as it stands on a page of its own in every mushaf. Every other chapter
runs on, its panel set inside the leaf where it falls, which is how a printed
translation sets them; starting all 114 on a fresh leaf would leave 39 of them
under a third full.

**The two layouts are still one book, through the verse rather than the page.**
`EnglishBook.leafOfVerse` is exact for all 6,236 verses, so the running head,
the juzʾ, the dial, the reciter's own place on the paper and a reader who
changes language all land on the words they were on. Each leaf records the
Madinah page it *opens* on, and that is what the head and the juzʾ are read
from.

One rule the rest of the reader still has to honour: while the voice is inside a
straddling verse, the leaf the reader is on is the verse's *opening* page, not
the page that word is printed on. That is `MushafCatalog.readingPageOf`, and it
is also why the English leaf does not lead-turn (rule 13.6).

### 13.2 The text is the translation, not the gloss

The scrolling reader's English is quran.com's word-by-word gloss, lyricized —
an interlinear aid, and it reads as one ("Indeed this (is) your religion
religion one"). A page of that is a crib. The leaf is a book, so it is set from
the verse translation.

### 13.3 One hand for the whole book, solved from the measure

Rule 2 again, by a different route. The Arabic hand comes from the fixed 16.4 em
line; the Latin one comes from the classical measure — a book line holds around
forty-five characters. The hand is the size at which a leaf's worth of prose
exactly fills the well, and it is *measured* rather than modelled: a reference
block of exactly a leaf's mass (`englishLeafReferenceBlock`) is laid out at a
probe size in the face, measure, rag and line-breaking it will really be drawn
in — real prose of this translation, 74:29-36, whose characters per word and
spread of word lengths are the closest of any run in the Qur'an to the whole of
it — and

```
    H = probe · √( well / measured )
```

because a block's height goes as the square of the hand — it holds `1/k` more
characters to the line *and* each line stands `k` taller. One step lands it; the
caller takes a second for the rounding that discrete line counts leave behind.

It takes no page: the type depends on the leaf's geometry and the face, and on
nothing the page happens to carry.

### 13.4 One hand, one leading — and the leaf is not the page

While the page boundary came from the Arabic leaf, a page's mass was fixed at
somewhere between 1,055 and 1,997 characters. Something had to absorb a range of
nearly two to one, and there were only four candidates: the type, the leading,
the foot, or the number of leaves. Three of them were tried:

- **The type gave**, a few percent on the heaviest leaves. That is the one thing
  §13.3 forbids and the first thing a reader notices.
- **The leading gave**, opening and closing between 1.20 and 2.00 em so every
  leaf filled. A page set at 2.00 em then turned into one set at 1.20 — the same
  book in two different hands' worth of air, and a reader turning pages sees a
  change in line spacing long before they notice a page that ends early.
- **The foot gave**: one hand, one leading, and a leaf ended where its content
  ended. Consistent, and it left the median leaf 68% full and the type dictated
  by the heaviest page in the book, which is to say every page was set for the
  worst one.

**It is the leaf count**, and then §13.1 removed the boundary that made it a
range at all. A leaf holds `ENGLISH_LEAF_CAPACITY_CHARS` = 900 characters and is
filled to it — about 1,145 leaves for the book. Every leaf still records the
Madinah page it opens on, so the juzʾ, the running head and the reciter's own
place on the paper go on meaning exactly what they meant.

What the leaf does take over is the **count**. The folio and the page dial
number *leaves*, not Madinah pages, because those are what a reader turns and
lands on — a folio that repeated itself twice a page would be a lie about where
they are, and a dial with 604 stops for 1,390 leaves could not land on most of
them. The dial's chapter comb is rebuilt the same way, from the leaf each
chapter's first verse falls on (`EnglishBook.leafOfVerse`), so every stop on it
still lands where it says. That is `mushafLeafNumber`, and it is the one place
the English book stops sharing the Arabic one's numbering.

The capacity is chosen for the **line**, not for the page: 900 characters of
prose sets at about 22 sp on a phone and 46 characters to the line, which is a
book measure and the size the scrolling reader has always set its English at.
Half again the type of the page-bound leaf.

The cost is leaves, and with the pagination continuous the capacity buys nothing
but type: the choice is purely how long a line the hand wants. Below about 850
it is shorter than the measure wants and above about 1,000 it is longer.
`tools/measure_english_leaves.py` prints the sweep.

**A long verse is carried over, as a book carries a paragraph.**

A leaf ends when the next verse will not go on it, and a verse averages three
lines, so the foot of the page was blank by up to that much: **2.7 lines out of
22 on average and 7.4 at the ninety-fifth percentile**, 379 leaves more than
three lines short, 12% of the book's paper. Capacity does not touch it (the
sweep is flat), and neither does breaking the book optimally rather than
greedily — a Knuth-Plass pass over the slack redistributes it (p95 7.4 → 6.2)
without reducing it, because the total is fixed by the verses.

The only thing that removes it is the thing a printed book does: carry the
sentence over. The verse continues at the head of the next leaf and is numbered
where it finishes, and neither half repeats or drops a word.

**And the cut is always the end of a sentence.** A page break inside one is the
one thing a book does not do to prose it can help: the reader carries half a
thought over the fold and reassembles it on the other side. So the book cuts at
the last sentence end that fits the room left (`englishSentenceCut`), not the
last word — 2:96 leaves the leaf on *"…more than those who associate others with
Allah."* and the next opens on *"One of them wishes…"*. A verse with no sentence
end in reach is not cut at all: it goes whole on the next leaf, the way a
paragraph too big for the foot of a page does.

**The rules a book actually keeps.** They are short, and this book keeps them:

1. **The page fills.** The type page is a fixed rectangle and text fills it;
   every page carries the same number of lines. Everything else is an exception
   to this one.
2. **A chapter opens a new page.** A printed book will burn a leaf — a whole
   blank verso, in a book with spreads — rather than start a chapter halfway
   down one. This is the one page break a reader is meant to notice.
3. **The last page of a chapter is the only short page**, which is where all the
   ragged paper in a book lives.
4. **Widows and orphans move the break, and nothing else does.**

What is *not* on that list is the sentence. This leaf briefly refused to cut a
verse anywhere but at a full stop, reasoning that a listener should not carry
half a thought over a page turn. It is a real argument for a page being recited
aloud, but it is not what a book does: a paragraph runs straight over the break,
mid-clause, and the break is meant to be *invisible* — the eye carries on.
Holding out for a full stop left the foot of an ordinary leaf blank by 1.47
lines and 3.94 at the ninety-fifth percentile, paper spent on a fault the reader
was never going to see.

So the break falls where the line falls. `englishLeafBreak` still moves it off
the middle of a word, because this book does not hyphenate — a word break, not a
sentence break, and it costs at most a word.

**And the widow rule does not apply here either.** A *widow* is a paragraph's
last line alone at the head of a page with white beside it — and a carried verse
is never alone: the rest of it is followed on the same line by the next verse,
and the next, for twenty-three lines. There is no white beside it to look wrong.
Keeping the rule anyway refused a good cut on 239 leaves and held 5,253
characters off the paper, twenty-two leaves' worth, leaving an ordinary leaf
0.27 of a line short where without it the figure is 0.01. So
`ENGLISH_LEAF_MIN_FRAGMENT_CHARS` is a *word*, and that is arithmetic rather
than typography: a cut of nothing sets an empty run and never advances.

Measured over the book, with the chapter rule in force:

```
                            leaves   carries   mean blank   p95
    ordinary leaves            884       624       0.01     0.10
    chapter-final leaves       114         —      11.50       —
    ----                     -----
    whole book                 998
```

Which is the shape a book has: 884 leaves full to the character, and
every ragged foot in the Qur'an gathered onto the 114 leaves where a chapter
ends and the white reads as an ending. Against the sentence rule it replaced —
1,055 leaves at 1.47 lines blank *each* — it is 57 fewer leaves, and the waste
on the pages a reader spends their time on is gone rather than reduced.

**And the mark had to be priced.** The capacity is a mass of *specimen* prose
and the specimen carries no verse marks, so the hand is cut against text that
has none. A leaf of the same charged mass therefore fits only if what its marks
really take is covered by `ENGLISH_LEAF_REFERENCE_MARGIN`:

```
    marks × (real cost − charge)  ≤  capacity × (margin − 1)  =  9.4
    charge 3:   marks × 1.61      ≤  9.4    →   six marks, and no more
```

The book averages **6.2 marks to a leaf**, so about half of it was over the
line. An over-full leaf does not spill — it closes its leading and squeezes one
more line in, which is the one thing a single leading exists to prevent. The
leaf that made this visible is in As-Saffat, whose verses are short: nineteen
marks, twenty-four lines where the well holds twenty-three, set measurably
tighter than the leaf before it, and the twenty-fourth line holding the single
word "which".

The real cost is measured off that photograph. The mark's gold is the only gold
on the page, so it isolates by hue: 76 px on every one of the nineteen, and 96
with a space either side. The same photograph gives the advance — total ink
across the twenty-four lines, less the marks, over the 883 characters of
translation — at 20.84 px. So a mark costs **4.61 characters** and is charged 5,
at which the term goes negative and the question stops being asked. It had been
6, then 2.8, then 3; 3 is the one a leaf could not survive.

**The margin had to be swept.** Pricing the mark was necessary and not
sufficient: with it corrected the leaves still wanted 24 lines, because the
specimen the hand is cut against is one unbroken run of prose and a leaf is the
same translation broken by marks, brackets and quotation, which does not pack as
tightly. `ENGLISH_LEAF_REFERENCE_MARGIN` is that difference and it is the *only*
knob that moves it — the capacity cancels, since the reference block is itself
the capacity, so a smaller capacity merely buys a larger hand and the leaf keeps
the same share of the well. Swept on device, three builds, eight leaves each:

```
    1.01    24-25 lines wanted of 23.1    leading closes to 77, and to 74
    1.04    23 lines of 23.4, pitch 79    one leaf in six still closes it
    1.05    22 lines of 23.7, pitch 78    none close it
    1.06    22 lines of 23.7, pitch 78    none close it
```

1.04, because the leaf a reader complains about is the one with white on it, and
above 1.04 every leaf in the book gives up most of a line to buy out the last
leaf in six. What is left is a leading 2.5 % tight on that sixth leaf — the
residue of a character estimate, which does not come out with a constant.

What is left at the foot of an ordinary leaf is not blank paper but a short
*last line*: the cut lands wherever the character count runs out, which is
rarely a line ending. Landing it on one would take a real layout rather than an
estimate — which is to say a book that repaginates per device, as an ebook does
and a printed book cannot.

It is not done to save a line. Cutting a verse costs the reader the end of a
thought to a page turn, so it is done only where leaving it whole would waste
`ENGLISH_LEAF_SPLIT_HOLE_CHARS` — **three lines or more** — which needs a verse
of at least five lines, the top sixth of the book by length. And never within
`ENGLISH_LEAF_MIN_FRAGMENT_CHARS` of either end: one line of a sentence stranded
at a foot or left at a head is a widow, so the break moves back up the verse
until both halves clear two lines, or the verse is not cut at all.

| | whole verses | carried |
|---|---|---|
| leaves | 1,118 | **1,041** |
| blank lines, mean | 2.66 | **1.21** |
| blank lines, p95 | 7.4 | **2.8** |
| leaves more than 3 short | 379 | **30** |
| verses cut, of 6,236 | 0 | **302** |

It also retires the one leaf the book could not set: 2:282 is 1,333 characters
and now runs across two leaves at the book's own hand and leading, instead of
alone on one with the leading closed to fit it.

**The turn onto the second half is led, like any other.** A page turned exactly
when the first word overleaf is spoken is always late — the reader is still
looking at the word being said, and the paper only starts moving once the voice
has left. So the turn begins inside the word *before* the cut, `MushafTurnLeadMs`
= 500 ms early, which is the same lead the Arabic leaf takes at a page boundary.
The English leaf could not take that lead before: its last printed word is
usually mid-sentence, because a verse straddling the Arabic break is set whole,
and leading on it would turn the paper away from the sentence being read. The
book's own cut is the opposite case — the leaf really does stop mid-sentence
there — so it is exactly where the lead belongs. See `mushafLeadCarriedTurn`.

**A carried verse is on two leaves, and the reciter is on one of them.** The ink
and the page turn ask which leaf the voice is on, and answering "the leaf the
verse began on" left the leaf holding the tail recessed and silent for as long
as the first half took to recite — its own words being said aloud with no wash
on them, and a tap on it looking like it had done nothing.
`EnglishBook.leafOfVerse` therefore takes how far through the verse the voice
has come: at 0 it is the leaf the verse opens on, which is what a deep link, the
dial and the chapter comb want, and past a cut it is the leaf that picks the
verse up.

The offsets are estimates — the pagination counts characters, not glyphs — so
the leaf snaps them to a word boundary as it sets them (`englishLeafBreak`).
It only ever moves forward and is a pure function of the text, so the leaf that
ends at an offset and the leaf that begins there land on the same character
without either knowing about the other. It never stops inside brackets either:
the reader may have asked for the translator's asides to come off, those are
stripped per half, and half a bracket on each leaf would strip from neither.

**The constants are fitted, not guessed.** Eleven real leaves were rendered on
device and their line counts solved for what the layout actually does:

| | guessed | fitted |
|---|---|---|
| characters to the line | — | **39.3** |
| a verse mark, in characters | 6 | **2.8** |
| reference block, against a leaf | ×1.05 | **×1.06** |

The mark is set a size down and its cups are narrow, so charging it 6 characters
spent a third of a line per leaf on paper that was there all along. And the
reference block is one unbroken run of prose where a leaf is not: the specimen
sets 41.6 characters to the line and the book sets 39.3, so the block has to be
6% longer than the leaf it stands for. At 1.05 it was not, the hand came out
small, and 108 leaves ran past their well and closed their leading to hide it.

**Leaves are filled, not evened.** Verses go on the leaf until the next one will
not fit, and then the next leaf starts — the compositor's order, and the reason
no leaf is ever handed out over its capacity.

**An opening is charged the paper it takes — and only that.** The capacity is a
mass of *prose*, and a chapter opening sets none: it sets a panel, the air on
either side of it, and a basmalah. Left uncounted that is paper the pagination
believes is free, and the last leaf of the Qur'an, which opened four chapters
before chapters took leaves of their own, spent fifteen of its lines before a
word of translation was set on it; the leading closed to pay and the lines ran
into one another.

The charge is *measured*, off a device capture of an opening leaf, and this is
the one number in the pagination that a simulation cannot check — the model
believes the leaf is full either way, so the error is only ever visible on
glass. On the reference leaf, at a line pitch of 80 px:

```
                          measured   charged        was
    panel slot             126 px     1.58 lines    2.25 lines  (92 ch)
    basmalah slot          113 px     1.41 lines    1.91 lines  (78 ch)
    ----                   ------     ----------    ----------
                           239 px     2.99 lines    4.16 lines
```

So `ENGLISH_LEAF_OPENING_CHARS` = 64 and `ENGLISH_LEAF_BASMALAH_CHARS` = 58.
They were 92 and 78 — four and a sixth lines charged for three — and every
chapter's leaf came up an eighth of its well short. Half of the excess was a
charge for "the ragged end of the paragraph above", real when a panel could
land halfway down a leaf and a charge for nothing the day chapters started
opening leaves of their own. Sad is the case to look at: it used to stop
mid-verse at *"about My message. Rather,"* with two and a half lines of white
under it, and now carries 38:1–8 whole and fills to the foot.

**One leaf is over capacity and always will be.** 2:282 is a single sentence of
1,333 characters, half as long again as a leaf holds.

**The rescue, in order.** The leaf is measured as it will be drawn, and if the
block would run past the foot its leading closes — only on that leaf, only by
the overflow. The leading stops at `ENGLISH_LEAF_MIN_LEADING_EM` = 1.15, because
unbounded it will close as far as the arithmetic asks and a page whose ascenders
touch the descenders above them is not a tight page but an unreadable one. What
the floor cannot take, the hand does: `englishLeafOverflowHandPx` gives up a few
percent of type on that leaf alone. That breaks §13.3 knowingly — on 2:282 the
alternatives are overlapping lines or revelation clipped off the foot, and a page
set a little small is the only one of the three a reader can still read.

### 13.5 Ragged right, and deliberately not hyphenated

`TextAlign.Start` with `LineBreak.Paragraph`.

The mushaf's own rule is that every full line reaches both margins (rule 3) —
but that is a rule about Arabic, which fills a line by the letterform, and it is
the calligrapher's art. Latin has only the word space to fill with, and on a
measure of about fifty characters that is not enough of a lever: the spaces open
unevenly, the same line's colour changes from one page to the next, and the
reader pays for a straight right edge with rivers of white running down the
page. An even rag is the more readable page, and on a phone it is not close.

`LineBreak.Paragraph` stays, and earns more here than it did under
justification: it breaks the whole block at once rather than greedily line by
line, which is what makes the rag *even* — the difference between a right edge
that undulates and one that lurches.

Hyphens are off, and this is load-bearing rather than an omission. Hyphenation
is the one thing that breaks a *word* across two lines, and
`ShapedWordBloom.ColorReveal` takes the union bounds of a range's glyph path —
a tinted wash over a broken word would sweep the width of the whole line.
(`InkReveal` was taught to advance one wash across a range's line fragments in
order, because the verse wash below needs exactly that; the tinted layers were
not.) Anyone turning hyphens on must fix ColorReveal the same way first. Ragged
setting needs them far less anyway — the rag absorbs the long word that
justification would have had to stretch a line around.

### 13.6 The ink is on the word you are hearing

The reciter's timings name Arabic words, and this page prints none of them. For
a while the leaf refused to bridge that and washed by proportion — word three of
seven meant three sevenths of the characters. It is a true statement about where
the voice is, and it is not what a reader hears: the reciter says ٱلْكِتَٰبُ and
the ink is somewhere in the middle of "about which".

The link is recoverable. Every Arabic word carries its own gloss — the
interlinear crib the scrolling reader lyricizes — and the translation is a
translation of the same sentence, so the two share most of their content words.
`EnglishWordAlignment` aligns the gloss stream to the translation and hands back
the share of the sentence each Arabic word ends at. Over all 6,236 verses **84 %
of Arabic words land on a lexical anchor**; an unanchored run is spread between
the anchors around it, which is the old proportion applied locally, so the map is
never worse than what it replaces and with no anchors at all it *is* that.

Three rules make it usable:

- **Monotone.** Arabic is not English word order — لَا رَيْبَ فِيهِ is "no doubt
  in it", and Sahih International sets "about which there is no doubt". A
  faithful alignment would run backwards there, and the wash cannot: laid ink
  never lifts (`docs/INK_ENGINE.md`). So the alignment is constrained to advance
  and a reordered clause is absorbed by sliding a word or two.
- **Snapped to word ends.** An interpolated boundary lands wherever the
  arithmetic put it, and "slumbe|r" is not a place ink rests. Boundaries that
  collapse onto each other are correct: the English has no separate words for
  that Arabic one.
- **One map for everything.** The wash, the tap, and the leaf a carried verse's
  voice is on all read it (`EnglishVerseAlignments`, solved per verse on first
  ask). If the page turn read the cut with a different map than the ink, it
  would turn away from a wash still running.

Measured on 2:2 (Alafasy) with the app playing: tap "no" and the reciter starts
at 1,776 ms against لَا's 1,760; "doubt" → 2,142 against رَيْبَ's 2,140;
"guidance" → 4,989 against هُدًى's 4,970; "the Book" → 862 against ٱلْكِتَٰبُ's
860. And the wash holds nearly still for the 2.26 s the reciter spends on فِيهِ,
whose English is the three characters ", a", then crosses " guidance" in the
0.3 s of هُدًى — which is the shape of the recitation, not of the sentence.

Verses still to come wait under the same recess as the Arabic leaf's; verses
already read hold their ink; the packs are the very same `AyahInkPack`.

**And it blooms one word at a time, which is the whole point.** The scrolling
reader gives the word being said an `InkReveal` over its own glyphs, on its own
letter sweep, with the engine's own feather; the words behind it hold full ink
and draw nothing; the words ahead sit under paper. That is this app's ink. The
leaf could not copy it while it had no alignment, so it swept one continuous
front across the sentence instead — and a front crossing a paragraph is not a
word blooming, however narrow its edge is made. Two attempts at the edge width
(a line of the page, then 1.6 word-widths of the sentence) both missed for the
same reason: the shape was wrong, not the size.

With an alignment it is a direct copy, because the states are contiguous —
everything before the word being said is read, everything after is not. So the
sentence is drawn as three bands rather than one bloom per word
(`englishWashBands`): the read band, the word being said, and the band still to
come. Same picture as fifty per-word blooms, at three. The bloom's range *is* a
word now, so it takes `Tuning.washFeather` unmodified, exactly as the scrolling
reader does.

**Bands that abut must not reach past their boxes.** A paper cover is drawn
`PaperCoverPad` (4 dp) wider than its own line box, to catch ink that overhangs
it. That is right where a bloom covers a word with whitespace either side, as
in the scrolling reader: the reach lands on the space and nothing shows. These
bands touch, so the reach put paper over the same strip of prose twice, and an
8 dp notch of half-erased text travelled along with the voice — the shimmer at
the wash's edge. The leaf passes `coverPad = 0`; the bands tile the sentence
exactly, so they have nothing to close over. Their edges are also kept out of
the middle of a word (`englishBandEdge`), because two abutting covers meeting
inside a letter is a hard cut down it — the alignment's own boundaries are word
ends already, but a carried verse or one with its asides hidden is a shorter
string than the shares were measured against.

The three bands also give the recess its place. **A verse seeked into rises out
of the paper rather than appearing on it:** tapping the middle of a sentence
makes everything before the tap already read, and drawing that at full strength
on the next frame made the sentence flash on. The cover rides the read band and
lifts over `Tuning.recessMs`, which is precisely what the Arabic leaf does with
its already-read words; the word being said never carries it, because it is
revealed by its own bloom. Measured on device: a mid-sentence tap that used to
complete in a single frame takes ~370 ms of rise.

**But it reads the plain clock out of them, not the paced one.** The word's own
share comes from `InkMotion.plainSweepProgress` — linear across the word's
karaoke hold — and never from `sweepProgress`, which carries the two corrections
that make the Arabic wash right and the English one wrong: the tajweed letter
map (`TajweedPacing.Curve`) and the wasl carry-in. Both are statements about
where inside an Arabic *word* the time is going. A word holding a madd spends
about half its dwell parked on one letter (`Hold.waqfShare` is 0.55, `creep`
0.08), and mid-ayah words run ~1 s: drawn on English prose that is half a second
of a sentence frozen where nobody is holding anything, then a sprint to catch
up, which is exactly what "the ink is behind the voice" looks like. The
scrolling reader's English mode refuses the same curve at the source
(`rememberInkMotions(pacing = null)`); the mushaf cannot, because one pack draws
both leaves — so the correction is refused at the *reader*, which is why the
plain clock exists.

The chapter-opening basmalah takes the same rule twice over
(`BasmalahWash.plainProgress`). Its calligraphy wash is doubly Arabic: tajweed
inside each word, and word *bands* measured off the artwork, where the kashida
gives بِسۡمِ over half the width for a half-second syllable. Laid over "In the
name of Allah, the Entirely Merciful, the Especially Merciful" that inks past
the middle of the line while the voice is still on the first word. The prose
line gets even quarters — one per word — crossed linearly, and its own feather
cap (`BasmalahWash.PLAIN_MAX_FEATHER`) so the last quarter stays untouched until
its turn.

**Which English, though, is the reader's to choose.** The leaf is set from the
published translation by default, and `Settings.englishLeafText` will set it
from the word-by-word gloss instead — the same text the scrolling reader has
always shown. The two are not the same trade. The translation reads as a book
and lines up with the recitation only through the alignment above; the gloss
reads as a crib and lines up exactly, because every Arabic word carries its own
English and there is nothing to align. Someone listening to learn the Arabic
wants the second; someone listening to follow the meaning wants the first.

The pagination follows the choice: the two texts are different lengths, so
`EnglishBook` is rebuilt when it changes and the leaves fall differently.

**A tap reads the same map backwards.** It says *where* in the sentence, and
`englishSeekWordPosition` answers with the word whose share of the sentence
covers that point — tap "the Book" and the reciter says ٱلْكِتَٰبُ. Without an
alignment it falls back to plain proportion, which is near but not exact. What
both replaced was worse than approximate: every tap restarted the verse, so a
reader who wanted the last clause of a thirty-second verse heard the whole of it
again.

**The orange repeat rides the same map.** A word the reciter goes back over is
tinted on its own English, one `ColorReveal` per word of the chain, on that
word's own repeat clock — the occurrence being spoken sweeps its orange on, the
ones before it hold theirs, and they release together when the chain completes.
That is the scrolling reader's construction unchanged. The leaf carried no
repeat while it had no alignment, for the honest reason that a repeat is a
statement about one Arabic word and there was no word here to say it of; the
alignment answers that. A repeated word takes the orange *instead of* the
first-pass wash, as it does everywhere else — running both over the same span
would wash it white and tint it at once.

The wet-ink glint stays off. It is the sheen on ink being laid this instant,
and a span of prose is too big a thing to glisten.

It does lead-turn, but only at its own cut (§13.4). The Arabic lead is measured
from the last word *printed* on the page, which is routinely mid-sentence on a
leaf that set that sentence whole; the book's own cut is the one place the leaf
really does stop mid-sentence, so that is where the lead belongs.

### 13.7 The grid

The leaf is one grid, and everything on it lands on the grid.

**Vertically**, each setting divides the leaf into three bands and
`MushafLeafBands` holds it to its own total — the bands must sum to `slots`, or
the last line runs off the paper at one end and a strip of nothing is
unaccounted for at the other. They spend the leaf differently because their ink
does:

```
                head   gutter   well   = slots
    Arabic      0.30     0.50     16     16.80
    English     0.30     1.00     15     16.30
```

The Arabic leaf spends almost nothing on the gutter and buys a sixteenth row of
revelation with it. It can, because the QCF faces mark 1.37 em above the
baseline and 0.75 below, so a band of nearly nothing still leaves visible air —
and every unit not spent on furniture is type size (§2). The English leaf has no
sixteenth row to buy, and its ink stops *exactly* at the ascent and the
descender, so it keeps the canonical gutter, which was sized for precisely this:
a head that sits closer than about a line's pitch reads as part of the block.

**Nothing stands under the text.** There used to be a tail and a folio band —
0.35 of a unit of paper and 0.40 for the figure, three quarters of a line spent
below the last line of every leaf in the book — and both have gone. The folio
now stands in the air the dial already kept above its rule (`MushafDialHeadAir`,
24 dp of pure padding, now 8), which puts the figure lower on the screen, in a
band it shares with the transport. That is where a folio belongs on a device:
the page number is furniture of the frame, not of the paper, and the reader's
thumb is already down there.

The leaf keeps the three quarters of a line. On the Arabic hand it goes into
type size, since the well's share of the leaf rises from 16 / 17.05 to 16 /
16.80. On the English hand, whose type is solved from the well, it is a whole
extra line: the well's share rises from 15 / 17.05 to 15 / 16.30, 4.6 % more
paper, and 4.6 % of twenty-two lines is one — so `ENGLISH_LEAF_CAPACITY_CHARS`
goes from 900 to 940 and `ENGLISH_LEAF_LINE_CHARS` divides it by 23 rather than
22. It is the only change here that a reader will count.

The two settings no longer sum to the same figure, which is why each divides by
its own `slots` rather than by one shared `SLOTS`. The shared total was always a
convenience: what a leaf must not do is spend more height than it has, and its
own sum is the thing that says whether it does.

That last point is why the block is set `LineHeightStyle.Trim.Both`. Untrimmed,
a line box carries half its leading above the first ascent and half below the
last descender — and since the leading is the thing that varies from leaf to
leaf (§13.4), so did that half. Measured on device, the first line of a leaf set
at 2.00 em sat 7 dp lower than the first line of one set at 1.30 em: the head
gutter the grid promised was not the paper the reader saw. Trimmed, the block's
own edges are the ink, and the first line and the last land on the same paper on
every leaf in the book. It also makes the block's height exactly `(n − 1)`
pitches plus one line's ink, which is what the leading is solved from.

**Horizontally**, there is one measure, and the running head and the folio are
set to it. They are furniture *of the text block*, not of the paper: standing
them at their own inset put the head a finger's width outside the block it
names. The Arabic leaf's measure keeps its bare 10 dp fore-edge, because every
unit of paper it does not spend is type size and the QCF measure is what caps
that type. The English leaf's is 5.5% of the leaf — the hand is solved from the
measure there, so paper given to the margin comes back as a shorter, more
readable line rather than as smaller type, and a book with no outer margin reads
as a printout.

**The chapter's panel is one line of the page, with a line's air around it.**
The band is one line's own type box, so it stands exactly as deep as a line of
the revelation; on each side of it sits about one of the page's interlines, so
the whole slot comes to around a line and a half.

Both halves are measured rather than chosen as a fraction, because a fraction
was what got it wrong. The prose block is set `Trim.Both`, so its last line
stops at the descender and contributes no trailing white of its own —
everything separating the panel from the text has to come out of the panel's
own slot. Sized as a share of the band, that came to 14 px above and 20 px
below on a page whose lines sit 27 px apart: tighter than the text it divides,
and visibly tighter on one side than the other. Built from the line's ink and
the page's interline it measures 24 px above and 29 px below against a 23–25 px
interline, and what is left is glyph slack — the line above may have no
descender, the line below may open on a capital rather than an ascender.

Half a pitch a side reads better still and is not worth its price: on a leaf of
juzʾ 30 with two chapters opening on it, it took the page's own interline from
27 px down to 20 to pay for itself, which is the rest of the page giving up its
air so the panel can have some.

The panel therefore rides the leading, and is a little deeper on an open leaf
than on a close-set one. That is right rather than a fault — the eye reads the
panel against the lines it sits *among*, not against a panel on some other leaf
it saw ten minutes ago. (Set to a fixed number of ems it was constant across the
book and a line and a half deep on a page set at one line, which read as a plate
dropped onto the paper.)

**The basmalah under it is one line.** Set at the page's own hand it takes two
on a phone measure, and a basmalah broken across a line-end is not a display
line — it is a paragraph of one sentence sitting where a heading should be. So
it is set to the measure: the hand comes down until the line fits, which is the
Latin form of §4's "a line that will not fit is made to fit", by the only lever
this script gives. It is still one size for the whole book, because the measure
does not change from leaf to leaf, and a display line set smaller than the body
is what a printed translation does with it anyway. Its slot is measured too, and
all of its air falls below it — centred, half of it landed above instead, under
the chapter's panel.

Everything else is unchanged: the page dial, the chapter ornament, the fore-edge
fade, and the right-to-left page turn. It is the same book — only the writing is
the reader's.

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
