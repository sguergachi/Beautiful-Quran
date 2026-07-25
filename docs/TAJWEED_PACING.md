# Tajweed-paced ink

**Status: implemented on Android, off by default behind the Ink Lab's
"Tajweed pacing" toggle (Settings → Developer → Ink Lab overlay → Tajweed
tab). The web port and measured letter widths are not yet built — the design
for those lives below.**

The shipped model is the **gate / cruise / hold** design in §3. The first
revision spread every letter by its raw tajweed counts; because word timings
carry no slack, that made the ordinary letters *faster* than the plain sweep
and (via a narrowed feather) sharper too — losing the whole-word breath the
reveal is built on. §3 explains what replaced it and why.

Today the active word's ink wash sweeps across the word at a constant (eased)
rate: `sweepMs` is the word's dwell time, and progress maps linearly to
horizontal position. But recitation is not linear *within* a word. Tajweed
prescribes how long each letter is held — a madd lazim is six counts, a
ghunnah two, a plain haraka one — and reciters follow those rules closely.
That means the word-level timing we already have can be subdivided: if we know
what fraction of a word's utterance each letter occupies, the ink edge can
dwell on the letter the reciter is actually holding and glide quickly over the
rest. The follow-along stops being word-karaoke and starts tracking the voice
letter by letter — with **zero new timing data**.

## The worked example

`ٱلضَّآلِّينَ` (1:7, last word) is typically held ~2 s by a murattal reciter.
Counting in harakāt:

| Letters | Rule | Counts |
|---|---|---|
| `ٱ` hamzat wasl + assimilated `ل` | silent in context | 0 |
| `ضَّ` | shadda + haraka | 2 |
| `آ` | madd lazim (maddah, next letter mushaddad) | 6 |
| `لِّ` | shadda + haraka | 2 |
| `ي` | madd tabiʿi | 2 |
| `نَ` | haraka | 1 |

Of ~13 counts, the `آ` alone carries 6 — the ink edge should spend nearly half
the word's dwell crawling across one letter near the middle, then release. The
current linear sweep is furthest from the truth exactly on the words listeners
notice most: the long, held ones.

## Why this works without new data

The word-level segment already tells us *how long this reciter actually spent*
on the word — elongation styles, speed, and qirāʾah differences are all baked
into `endMs − startMs`. Tajweed weights only *distribute* that measured time
across the letters. A reciter who stretches the madd spends longer on the
word, and the weights put that extra time on the madd letter. The two sources
correct each other: measured total, rule-based shape.

## Architecture

One new **pure** module, mirrored on both platforms like the other engines
(the web port is still to do):

```
app/src/main/java/com/beautifulquran/domain/TajweedPacing.kt   (implemented)
web/src/domain/tajweedPacing.ts                                (pending)
```

It sits beside `HighlightEngine` (which word, for how long) and feeds
`InkEngine`/renderers (how the ink moves). No Android/DOM types, JVM/Vitest
unit-tested, same purity invariant as `HighlightEngine`.

```
word.arabic (Hafs Uthmani, committed DB) ──► letter events + tajweed weights
                                                    │  time fractions  t₀..tₙ
shaped layout (per platform) ──────────────► width fractions  x₀..xₙ
                                                    │
                              TajweedPacing.Curve: monotone u∈[0,1] → position∈[0,1]
                                                    │
        sweep animates *linear* time; draw maps through the curve
```

Three stages:

### 1. Letter events and weights (pure, shared)

`words.arabic` carries full Hafs orthography — shadda, maddah (ٓ), dagger
alef (ٰ), sukūn marks (ۡ), hamzat wasl (ٱ), quiescent-zero (۟) — so the rules
are deterministic from the string. Tokenize into **letter events** (base
letter + its combining marks), then weight each event in harakah counts:

| Case | Detection | Counts |
|---|---|---|
| Short vowel / tanwīn | fatha, damma, kasra, tanwīn marks | 1 |
| Sākin consonant | sukūn mark | 0.5 |
| Qalqalah sākin | sukūn on ق ط ب ج د | 0.75 |
| Shadda (gemination) | ّ | +1 |
| Ghunnah mushaddadah | shadda on ن or م | +2 instead of +1 |
| Word-internal ikhfāʾ / idghām-ghunnah / iqlāb | ن sākinah or tanwīn before the rule letters | 2 |
| Madd tabiʿi | alif after fatha, و sākinah after damma, ي sākinah after kasra, dagger alef, small و/ي | 2 |
| Madd muttasil / munfasil | maddah ٓ, next base letter *not* mushaddad/sākin | 4 |
| Madd lazim | maddah ٓ, next base letter mushaddad or sākin | 6 |
| Silent letters | hamzat wasl mid-flow, ۟-marked alif, definite-article ل before a sun letter (next letter mushaddad) | 0 |

Normalize to cumulative time fractions `t₀=0 … tₙ=1`. Zero-weight letters
merge into their neighbour's glide (see the curve section). Cross-word **nūn
rules** (idghām / iqlāb / ikhfāʾ) use same-ayah `prevArabic` / `nextArabic`:
hold on the next word's first letter; early exit on the previous word's
trailing nūn. Iẓhār (ءهعحغخ) and cross-ayah wasl are not connected. Madd
ʿāriḍ letter choice at waqf remains a force-weight on the final letter.
Counts live as named constants in `TajweedPacing`.

**The counts pick the moment, not the tempo.** An earlier revision spread
every letter by its raw counts and then softened the ratios with a `contrast`
exponent. Both were wrong, for the same reason: see "No slack" below. The
counts now decide only *which* letter is worth holding and *how much* of the
dwell budget it earns relative to other holds in the same word.

**Orthography facts baked into the parser** (verified against every word in
`quran.db` — this text is the QPC Hafs encoding, and two conventions are
easy to get wrong):

- The **voiced sukūn is U+06E1** (small dotless khah, `ۡ`). The round
  **U+0652 marks a letter that is written but never voiced** — the plural-waw
  alif of `قَالُوٓاْ`, the waw of `أُوْلَٰٓئِكَ` — same role as the
  rectangular zero on `أَنَا۠`.
- Tanween has **two families**: the sequential forms (U+064B/C/D, iẓhār) and
  the un-sequenced trio **U+0657/U+065E/U+0656** (`ٗ ٞ ٖ`, ikhfāʾ/idghām
  tanween — thousands of occurrences). Both weigh one count.
- Ikhfāʾ/iqlāb noon is written **bare** (no sukūn mark): `كُنتُمۡ`'s noon
  carries its ghunnah precisely because it is unmarked before `ت`.
- The 2-count small madds: dagger alef U+0670, small waw/yeh U+06E5/U+06E6,
  and small *high* yeh U+06E7 (`إِبۡرَٰهِـۧمَ`).

### 2. Width fractions (uniform in v1; measured is the follow-up)

**v1 ships uniform widths** — each letter event takes an equal slice of the
word box. That keeps the entire feature pure and free of layout plumbing, and
it already delivers ~90 % of the effect: the *timing* (dwell on the madd,
glide over short letters) is exact, only the *position* of the edge is
approximate, and the feathered wash absorbs letter-width error. Silent
letters keep their width slice but no time of their own — their slice is
folded into the adjacent letter's glide (leading silents ride the letter
after them, trailing silents the one before), so the wash crosses the
article of `ٱلضَّآلِّينَ` in motion during the `ضّ` rather than teleporting.

If the Ink Lab audition shows the edge visibly missing letters on long words,
the measured upgrade is:

- **Android** — both Arabic modes already own a shaped `TextLayoutResult`
  (the ayah's, for Arabic-only `shapedWordBloom`; the word's, in the gloss
  grid). `getHorizontalPosition(offset)` at each event-boundary offset gives
  exact shaped x-positions — ligatures like لا simply collapse two boundaries
  to the same x, which the curve handles (zero-width step). Cache per word per
  layout.
- **Web** — one `Range.getBoundingClientRect` per event boundary on the
  mounted glyph span, measured once at Active entry and cached per
  (word, font size). Browsers return correct cluster geometry for shaped
  Arabic where `measureText` on prefixes would not.

### 3. The pacing curve — gate, cruise, hold

**No slack.** Word timings are contiguous: only **0.2 % of segments have any
breath gap at all** (`end == nextStart` everywhere else). So the time inside a
word is strictly zero-sum — every count handed to a madd is taken from its
neighbours, which then run *faster* than the plain sweep. The first revision
spread all letters by their counts and, at `contrast = 0.7`, left the ordinary
letters running **~1.3× the plain rate**: the feature made most of each word
quicker, which is precisely how it read.

The model is therefore a **gated hint**, built from four parts:

- **Gate.** A word is paced only if it holds a genuinely dramatic letter.
  Otherwise `curve()` returns null and the renderer takes the plain sweep, so
  the page is untouched almost everywhere. Measured over the corpus, the
  default (madd only) gates **6.8 % of word tokens** — about one word in
  fifteen — plus every verse-closing word. Enabling ghunnah roughly doubles it.
- **Cruise.** Non-hold letters move at one constant speed, never the raw
  ratios, bounded by `Hold.cruiseCap` as a multiple of the plain rate. **This
  is the honest dial: hold length and ordinary-letter speed are the same
  number.** At a cap of 1 the ordinary letters are never hurried, which also
  means a mid-word madd can buy no dwell — leaving waqf as the only drama.
- **Hold.** The freed time parks the wash on the held letter, entered at the
  midpoint of its own width slot (`HOLD_ANCHOR`) so the glyph is caught
  mid-bloom rather than sustained before or after itself. It creeps through
  `Hold.creep` of that slot while holding, so the ink breathes instead of
  freezing dead. Multiple holds in one word split the budget by excess counts.
- **Waqf.** A verse's closing word is held **2.9× longer** than a mid-ayah word
  (median 2983 ms vs 1040 ms; `ٱلضَّآلِّينَ` in 1:7 runs 6505 ms). That slack is
  real rather than borrowed, so it is budgeted separately (`Hold.waqfShare`)
  and spent on the final letter — the madd ʿāriḍ li-s-sukūn the reciter is
  actually sustaining. This is the one case that deliberately cruises past
  `cruiseCap` (up to ~2.2× the word's own uniform rate, still slower in
  absolute terms than an ordinary word because the word is so much longer).
- **Wasl connect (nūn rules).** When the **previous** word ends in nūn sākinah
  or tanwīn and **this** word starts with idghām (`يرملون`), iqlāb (`ب`), or
  ikhfāʾ (the fifteen letters), the nūn is absorbed under wasl and the
  reciter sustains **this word's opening letter** (e.g. `مَن يَقُولُ`,
  `مِن قَبۡلُ`, `نُوحٖ وَٱلنَّبِيِّـۧنَ`,
  `رُّسُلٗا مُّبَشِّرِينَ`). `prevArabic` arms that entry hold;
  `nextArabic` softens the previous word's exit (letters finish by ~82 % of
  the spoken span so the trailing nūn is not settled at handoff). During the
  freed tail, a narrow soft wash blooms only the next word's opening glyph;
  it remains inked across the word-timing boundary while the ordinary
  whole-word wash continues from the same spatial leading edge. The prefix
  uses its one-letter fraction for both travel and feather, retaining the
  smootherstep edge without brightening the following glyph. Its completed
  edge becomes the active sweep's starting point, so the opening letter is
  not replayed and the wash immediately advances through the remaining word.
  That continuation is armed only on a natural adjacent-word handoff, never
  a seek. Same-ayah neighbours only (`Hold.connect`, default on).
  Iẓhār and cross-ayah wasl are left alone.
- **Waqf length scale.** `Hold.waqfLengthScale` (Ink Lab: **Waqf length
  scale**, default 0.7) multiplies `waqfShare` by a letter-count ramp: 0 =
  full share on every closer; 1 = linear from ~0 at 3 letters to full share
  at 8+ pronounced letters. Medium closers like `عَظِيمًا` keep a readable
  run-up when the hold slider is high; long closers still park hard.

Breakpoints `(tᵢ, xᵢ)` — time fraction → width fraction, two per hold and one
per plain letter — are evaluated as a **monotone piecewise-linear** map (a
dozen points; binary search per frame, allocation-free). Monotonicity holds by
construction: both lists are cumulative. Silent letters emit no breakpoint,
which folds their width into the neighbouring glide instead of a same-time
position step. The smootherstep feather softens the remaining corners
spatially; Fritsch–Carlson monotone cubic is a later polish if the corners
ever read mechanical.

Two refinements built into the curve, not the callers:

- **Spoken span vs hold.** `sweepMs` runs to the karaoke hold (`holdEndMs`),
  which would include a breath gap. The curve lays the letters over the
  *spoken* span (`endMs − startMs`) and appends a plateau at
  `(spoken/hold, 1.0)`, so the ink settles when the voice stops. With
  contiguous timings this is almost always a no-op, and correct when it isn't.
- **End easing.** The global cubic-bezier sweep ease is *dropped* for paced
  words (composing it with the curve would distort the hold — the whole
  point). The soft toe and shoulder come from the feather profile, which
  already has zero slope at both ends.

## Integration — what changed where (Android, implemented)

- **`InkEngine`** — `sweepMs` unchanged.
  `InkEngine.pacing(arabic, activeWord, isAyahFinal, prevArabic, nextArabic)`
  returns the nullable curve (gated on `Tuning.tajweedPacing`, default off)
  and assembles a `TajweedPacing.Hold` from the tuning;
  `InkEngine.pacedFeather()` supplies the paced edge. Knobs on the Ink Lab's
  **Tajweed** tab: the `tajweedPacing` master toggle, `holdMadd` /
  `holdGhunnah` / `holdWaqf` / `holdConnect`, and the `cruiseCap`,
  `waqfShare`, `waqfLengthScale`, `holdCreep` and `pacedFeather` sliders.
- **`ActiveWord`** — carries `spokenMs` (segment end − start) alongside the
  karaoke-hold `durationMs`, so the curve can rest after the voice stops.
- **Renderer** — `AyahBlock` computes the active word's curve next to
  `sweepMs` and threads it the same way (`WordUnit`, `ResponsiveHafsAyah`).
  With a curve, `rememberLetterSweep`'s `Animatable` animates **linear** time
  0→1 over `sweepMs` and the returned state is `derivedStateOf
  { curve.at(clock) }` — the bezier sweep easing is dropped for paced words
  (it would distort letter timing; the feather profile keeps the soft
  toe/shoulder). Pacing costs one curve lookup per frame, zero extra
  recompositions. The clock is captured at Active entry like `sweepMs`, but
  the curve itself is tracked live (`rememberUpdatedState`), so every Ink Lab
  knob reshapes the word already on screen instead of waiting for the next
  activation. `AyahBlock` also passes `isAyahFinal` (the active word's position
  vs `ayah.words.last()`), which arms the waqf hold, and the previous/next
  words' `arabic` for wasl nūn entry and exit. A detected connection also
  drives a prefix-only overlay on the next shaped word over the prior word's
  final 18%; on handoff that prefix stays full and the main wash resumes from
  its completed edge, so the wāw/other target never dims or restarts.
- **Feather** — the make-or-break visual change, and the one the first
  revision got wrong. `letterFadeIn`'s wide edge is *what makes the reveal
  ethereal*: at 1.6× the word width the wash reads "closer to a whole-word
  breath than a moving edge" (its own comment in `ui/theme/Fade.kt`). Ratio
  pacing was too subtle to see at that width, so it narrowed the edge to
  0.3–0.8 — up to 3× sharper — and the softness went with it. A hold does not
  need a sharp edge: the bloom visibly *stopping* is legible at any feather.
  So `pacedFeather` now defaults to 1.6, identical to `washFeather`, and stays
  a slider for auditioning. `ShapedWordBloom.InkReveal` keeps its optional
  per-bloom `feather` override for that path.
- **Web renderer (pending)** — `runWash` accepts a `(t: number) => number`
  easing in place of the bezier tuple (Motion supports custom easing
  functions); the active-word wash passes the curve, everything else keeps
  the bezier. The 48-step mask quantization is applied *after* the curve, so
  plateaus render as repeated identical masks — free.
- **Unchanged** — repeat wash (decorative, stays constant-rate bezier),
  `secondaryAlpha` gloss/translit tracking (it reads the paced sweep value,
  so the gloss breath follows the dwell), all `HighlightEngine` semantics,
  the 33 ms poll.

**No DB or pipeline change.** Weights derive at runtime from `word.arabic`
(a one-pass scan of ~10 chars per word boundary), so there is no
`DB_FILE_NAME` bump, both platforms read the same committed text, and the
count constants stay tunable without a data rebuild.

## Failure modes and fallbacks

The curve is nullable end-to-end; `null` means "today's behavior" (bezier
sweep, 1.6 feather). Fall back when: the toggle is off, the word has fewer
than three pronounced letters, or nothing tokenizes. A full-DB sweep of the
implementation over all 77,429 words: zero failures, zero monotonicity
violations, 63,313 words (82 %) get curves, and the 14,116 fallbacks are
exactly the short function words (`فِي`, `مِن`, `مَا`, `ٱللَّهِ`…) where a
constant sweep is already right.

Known approximations, accepted for v1:

- Counts assume uniform tempo within a word; real reciters also glide.
- Widths are uniform per letter event (see the width-fractions section).
- Cross-word nūn rules (idghām / iqlāb / ikhfāʾ) are implemented same-ayah;
  cross-ayah wasl and iẓhār are not. Madd ʿāriḍ at waqf still force-weights
  the final letter rather than detecting the madd letter. Hamzat wasl is
  always treated as elided (true mid-flow, slightly early at an utterance
  start).
- Word-level `startMs`/`endMs` are themselves ±73 ms — letter pacing inherits
  that, which is fine: the feathered edge is a wash, not a cursor.

## Validation and the data-driven v2

The `~/qasr` CTC pipeline (wav2vec2 forced alignment) emits
**character-level token times** — exactly letter-level ground truth. Before
shipping, use it offline to:

1. **Calibrate** the count constants: fit average ms-per-count per rule class
   across a sample of reciters (is Hafs muttasil closer to 4 or 5 for our
   reciter set? how long is a real qalqalah?).
2. **Regression-test** the heuristic: compare predicted letter fractions
   against CTC letter times on a few hundred long words; a mean fractional
   error target (say < 10 % of word duration) decides whether v1 ships.

If the heuristic proves too coarse for some reciters, v2 ships *measured*
per-word letter fractions as a compact DB column (per reciter, quantized
bytes) — same `TajweedPacing.Curve` consumer, different producer, and only then does
the DB version bump. The engine design is identical either way, which is why
the curve, not the weights, is the module boundary.

## Testing

- `TajweedPacingTest` (JVM, implemented) is a spec for the **gate** as much as
  the hold: `صِرَٰطَ` and `أَنۡعَمۡتَ` carry nothing dramatic and must return
  null; `ٱلضَّآلِّينَ` must all but stop on its madd while an equal slice of
  cruising covers real distance; `ٱلنَّاسِ` holds only when ghunnah is enabled;
  `cruiseCap = 1` refuses a mid-word hold but still allows waqf; the closing
  letter of a verse-final word swallows over half the sweep; a bigger
  `waqfShare` buys a longer stillness and `waqfLengthScale` protects short /
  medium closers' run-up;
  wasl entry (`مَن`→`يَقُولُ`, ikhfāʾ, iqlāb) parks on the next opening letter
  and wasl exit finishes the previous word early; the exact 4:163
  `نُوحٖ`→`وَٱلنَّبِيِّـۧنَ` pair blooms its wāw from 82–100% of the prior
  word; no segment anywhere outruns the cruise cap; and every knob combination
  stays monotone and bounded with exact endpoints. **The golden literals must
  stay byte-identical to the DB** — an
  editor or tool that NFC-normalizes the file fuses `ا + ٓ` into precomposed
  `آ` (U+0622) and silently changes the weights (the parser now unfuses U+0622
  defensively, but the DB itself is always decomposed).
- **Full-corpus sweep** — all 21,216 distinct words (77,429 tokens) ×
  `cruiseCap {1, 1.25, 1.6}` × `isAyahFinal {false, true}` ×
  `spokenFraction {0.5, 1}`, with ghunnah on: **zero monotonicity violations,
  zero cap breaches, exact endpoints throughout**; the default gate admits
  6.8 % of tokens and the waqf rule produces a hold for 81.8 % of words when
  they close a verse (the rest are too short to pace). Run against the
  compiled implementation as a temporary test and deleted — JVM unit tests
  have no SQLite driver (invariant #5).
- **On-device audition** — playing 1:7 with pacing on and sampling the frame
  luminance over `ٱلضَّآلِّينَ` at 15 fps gives the intended three-act shape:
  the wash cruises from 24 % to 68 % ink over 0.87 s, holds between 68 % and
  70 % for **1.73 s**, then releases to full over the last 0.8 s.
- Existing `HighlightEngine`/`InkEngine` tests untouched — nothing upstream
  changed.

## Non-goals

- No tajweed *color-coding* of letters (this is motion, not annotation — the
  paper metaphor stays monochrome ink).
- No per-reciter rule variation in v1 (qirāʾāt differences, personal madd
  lengths) — that is exactly what the measured v2 is for.
- No changes to word-level sync, repeats, or the poll cadence.
