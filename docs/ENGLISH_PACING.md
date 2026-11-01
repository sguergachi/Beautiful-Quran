# English pacing — reading the meaning in time with the voice

How English ink follows the reciter: the word being said is the word being
inked, its pace follows his tajweed, it slows where he holds a letter, it
stands still where he stops, and it never jerks or snaps. This covers English
Scroll (one gloss per Arabic word) and the English Mushaf leaf (translated
prose), Android. It records the reasoning and the measurements, so the next
change starts from what was learned rather than from a sense of what feels
right.

The design came from listening. Every step below answers a specific thing the
user heard and named: *it stops after each word*, *it's behind*, *it's jerky*,
*the pause isn't there*, *it flows straight into the next word*. Each was traced
to a measurable cause before anything changed.

## What a reader needs

A reader listens to the Arabic and reads the English at the same time. Their
eye goes to the meaning as the word is heard, so the ink has to:

1. **Be on the right word.** The English for the Arabic being recited is the
   English being inked.
2. **Move at the reciter's tempo.** Slower where he sustains a letter, quicker
   through short ones — the rhythm the ear already has.
3. **Be smooth.** No word filling in a flick, no speed change you can see.
4. **Stop when he stops.** A pause in the voice is a visible stillness in the
   ink, the word before it finished, the word after it untouched until he
   speaks again.
5. **Be slightly ahead, never behind.** The meaning should be readable as the
   word is heard.

These pull against each other. Anchoring every word exactly to its Arabic
word makes the speed jump at every boundary; smoothing the speed blurs the
stops; leading the voice makes a stop end early. The rest of this document is
how each tension was resolved.

## The one rule: the clock owns English Scroll

In English Scroll the verse clock decides both **how far** each gloss is inked
and **which** gloss is the active word. `EnglishScrollPerformance` is the only
place that turns timings into those two answers; the verse block passes its
clock and its active word to `rememberInkMotions` together.

The Arabic's per-word machinery — a word gets ink only while its Arabic word
is active, and its window is released (snapped full) when that word ends —
must never get a say of its own in English Scroll. Anything the clock does
across an Arabic word boundary (borrowed time, smoothing, the lead, a held
pause) is otherwise cut back to the Arabic word's span. That happened once:
levelling passed every clock test and changed nothing on the phone, because
"All-Able (of)" could still only ink inside ذُو's 260 ms.

When adding an English Scroll timing feature, put it in the clock and test it
through `EnglishScrollClock.wordAt`/`window` and `englishClockActiveWord`, not
only `progress`. Exceptions, by design: re-says (the orange repeat) and
unconfirmed taps keep the voiced word, and a verse's first frame (before its
clock exists) uses the voiced word's eased window (`EnglishRevealTiming`).

## The pipeline

```
verse audio ──decode (TarjiVersePulse cache)──► EnglishPauses.analyze
                                                  │  loudness envelope
word timings ─┐                                   ▼
Mushaf text ──┼─ stop marks ──► stop boundaries ─► pauses (deep + at marked stops)
              │                                   │  pinned per verse (EnglishPauses.pin)
              ▼                                   ▼
   TajweedPacing.Curve.tempo ──► per-word tempo ─► verse clock: audible time only,
                                                   each gloss on its own word,
                                                   holds through pauses
                                                          │
                                       EnglishSmoothTrack (σ 100 ms, never
                                       across a pause or a verse's ends)
                                                          │
                     highlight clock + English lead (200 ms) ─► ink progress
```

| Piece | File | Role |
|---|---|---|
| Tempo curve | `domain/TajweedPacing.kt` (`Curve.tempo`) | The reciter's speed through one word, never parked |
| Scroll owner | `ui/reader/EnglishScrollPerformance.kt` | The clock plus the active word it implies; the one rule above |
| Scroll verse clock | `ui/reader/EnglishScrollClock.kt` | One position across a verse's glosses; which gloss is inking |
| Mushaf tempo warp | `ui/reader/EnglishReadingFlow.kt` (`EnglishTempoWarp`) | The same tempo inside the leaf's audible-time clock |
| Speed smoothing | `ui/reader/EnglishSmoothTrack.kt` | Gaussian on position, narrowing to nothing at pauses |
| Heard pauses | `ui/reader/EnglishPauses.kt` | Loudness from the verse audio; stop-mark boundaries; pinning |
| Lead | `ui/reader/ReaderViewModel.kt` (`englishLeadMs`) | English reading runs the highlight clock ahead |
| Word overlap (Mushaf) | `ui/reader/PaperInkEnglishWindows.kt` | Printed words' fills overlap half a letter in connected speech |
| Fallback | `ui/reader/EnglishRevealTiming.kt` | A verse's first frame, before its clock exists (and the web reader) |

Ink Lab → Tajweed: **English tajweed tempo** (0.6; 0 is a steady pace inside
each word — the clock stays on), **English smoothing** (140 ms), **English
ease** (260 ms), **English lead** (200 ms).

## 1. Stop at a pause, not at every word

English Scroll once finished every word's reveal up to 100 ms early and held
until the next onset, meant to make pauses visible. In connected speech it made
*every* word stop. Timed word boundaries are bimodal: **96% leave under 20 ms**
between words and real pauses mostly run past a second. So the hold belongs
only to a real pause (`ENGLISH_PAUSE_MS`, 150 ms); otherwise a word's ink runs
right up to the next word's onset.

## 2. The reciter's tempo, not the Arabic letter map

The reciter recites at tajweed pace; a steady English wash drifts against his
voice. But English cannot take the Arabic letter map directly — it was removed
in 29c47d4e9 for a measured reason: the map parks the wash on a held madd and
then races, 35% of recitation time sits on paced words, and the English ran up
to 1.0 s ahead or 0.7 s behind. Prose has no letter being held.

`Curve.tempo(strength, spokenFraction)` keeps the *speed* of the map and drops
its parking: `strength` of the letter map's slope, the rest the steady clock.
On ٱلضَّآلِّينَ the letter map's speed ranges 0.06×–1.24×; the tempo at 0.6
ranges about 0.43×–1.14×. It slows, it never stops, and it still reaches the
word's end at its voiced end.

## 3. One motion per verse, anchored word by word

Giving each gloss exactly its Arabic word's time is perfectly synchronised and
visibly jerky: a short Arabic word with a long gloss flicks past, the next
crawls. Over 4,236 real Scroll fills (three reciters, 60 verses) the speed
jumped **3.6× at the p90 boundary and 7.6× at p99**.

`EnglishScrollClock` builds one position track for the whole verse. Every gloss
still starts with its Arabic word (or every word it was merged across) and is
through as the next begins; inside it the position follows that word's tempo.
`EnglishSmoothTrack` then convolves the position with a Gaussian (σ 100 ms,
since raised to 140 ms — see section 8):
monotone, no added delay (the kernel is symmetric), speed changes blended over
about ±σ. Boundary jumps fall to **1.3× (p90) / 1.5× (p99)**, no fill is
shorter than 100 ms, and word midpoints stay on their words.

The Mushaf leaf keeps its own continuous audible-time clock (phrase plans,
literal word finishes); the same tempo enters it as a per-word time warp
(`EnglishTempoWarp`, each spoken word's span mapped onto itself) and the same
smoothing is applied to its ink distance — before blank space is mapped back,
so spaces still take no time. On Hani 64:1 the largest 60 ms speed change went
from 2.4× to 1.25×.

On the leaf each printed word also has its own ink field whose liquid eases in
and out, so butted end to end the fill stalled for most of a letter at every
space. Where speech runs on, a word's fill now starts half a letter before the
previous one finishes (`ENGLISH_WORD_OVERLAP_CHARS`): time below a quarter of
the mean fill rate fell from 14.6% to 4.6%. Never across a pause — there it
would show about 11% of the next word's first letter.

## 4. A little ahead

Ink that finishes exactly as the Arabic word ends reads as behind, because the
reader takes in the meaning as the word is heard. In English reading the
highlight clock runs `englishLeadMs` (200 ms) ahead of the voice — a shift of
the whole timeline, not a shortened sweep, so nothing stops at a word. Word
activation, repeats, verse handoff and both English clocks move together.

## 5. Pauses come from the audio

The timings almost never mark a stop: with 96% of word boundaries under
20 ms apart, a breath or waqf sits inside the word before it (or the one
after), and ink paced from timings runs straight through it. The Arabic shows
the stop because tajweed pacing holds it; the English has to *hear* it.

`EnglishPauses` reads the verse's own audio through the tarjīʿ decoder's cache
(`TarjiVersePulse.decodedAudio`), this verse and the next two ahead of the
voice. Loudness per 20 ms hop, median-filtered over 70 ms, relative to the
verse's 95th percentile:

- **Deep quiet anywhere:** below −20 dB for at least 180 ms (80 ms bridges).
  Against Hani ar-Rifai's measured quiet table it finds 26 of 27 on one random
  25-verse sample and 25 of 26 on the tool's fixed sample — starting a little
  before the table, in the voice's decaying tail, which is where the English
  should land. Hani 64:1: 9440–9800 ms against the table's 9442–9813.
- **Shallow quiet at a marked stop:** a reciter's pause at a waqf can be
  shallow. Alafasy after رَبِّنَاۗ in 3:7 sits only 15–19 dB under his voice.
  Next to a word carrying a stop mark (ۖ ۗ ۚ ۘ ۛ, saktah ۜ — never ۙ, which
  forbids a stop) −12 dB for 150 ms counts, searched 600 ms before to 800 ms
  after the next word's timed start. At 3:7's four marked stops he holds below
  −12 dB for 400–850 ms. The rule is confined to marked words because the same
  window around unmarked ones finds up to 440 ms; a marked stop he recites
  through can yield a short (~200 ms) hold.

Both clocks take pause time out of the word: its English lands as the voice
stops and stands still for the whole silence.

## 6. Making the stop visible

Finding the pause was not enough; three things hid it.

- **Smoothing blurred it.** A Gaussian across a pause eases the ink on into
  the silence and starts it moving before the voice returns. The kernel now
  narrows to a third of the distance to any pause edge (and to the verse's own
  start and end), so a word lands crisply and the next leaves crisply.
- **The lead released it early.** Running 200 ms ahead, a stop would end
  200 ms before the voice. Each pause holds on until the voice is back (at most
  60% of the speech after it): the word before still lands early, the word
  after leaves with the reciter.
- **Slivers.** Timings can start the next word a breath before the silence
  (*wa mā* in 3:7 is timed 25 ms before his pause), so that word took a few
  percent of ink and then held — read as flowing on. Scraps under 120 ms that
  a pause cuts from a word's edge belong to the pause.

Replayed through the production code with Alafasy's 3:7 audio and quran.com
timings: "(are) allegorical" lands at 16.6 s and holds through 16.76–17.56 s;
"our Lord" lands and "And not" stays at exactly 0 through 53.16–53.56 s, then
starts with his voice.

## 7. Never jump a running clock

Detection takes a moment (download and decode). A clock rebuilt mid-verse
would jump the ink, so a verse's pauses are **pinned** once its voice begins.
That alone lost every pause of a verse playback *started* on (3:7 is 61 s), so
Scroll — whose words are timed independently — also adopts pauses found late,
but only those starting after the next word and 400 ms of smoothing margin; the
word being said is never reshaped. The Mushaf leaf's clock is one audible-time
run, so it keeps the strict pin.

## 8. Grace: ease into a stop, anticipate the start

With the stops crisp the timing was right but the motion could feel sudden:
the ink braked to a dead stop at full speed and left again at full speed.
Two changes, measured on Alafasy 3:7 with its real audio, timings and marks:

- **Ease.** Within `englishEaseMs` (260 ms) of each stop and of the verse's
  ends, the track's time is re-timed with a raised-cosine weight: speed falls
  to rest into the stop and rises from rest out of it. The time this costs is
  repaid by a gentle swell (at most 1.33×) over the next three ease lengths,
  so the stop stays exactly where and as long as it was, and words further
  from it keep their timing. Repaying it across the whole moving stretch
  instead pushed the p90 word-timing error from 212 to 300 ms; locally it is
  230 ms (the median stays at the intended 200 ms lead).
- **Anticipation.** After a pause the ink now sets off half an ease ahead of
  the voice (about 100 ms before he resumes), gathering speed as he speaks,
  instead of leaving from rest at full speed exactly on his voice
  (`englishPauseHoldMs`).
- **Smoothing** in connected speech widened from σ 100 to 140 ms. A saved
  Ink Lab profile's old default (100) migrates; any other value is kept
  (InkLab schema 10).

Peak acceleration on 3:7 fell from 1,867 to 168 chars/s² (p99 125 → 89);
every pause still holds visibly still for 300–780 ms.

## 9. A short word with a long gloss borrows time

Smoothing blends speed over ±140 ms; it cannot absorb a gloss that must cross
13 letters in a quarter second. Alafasy's ذُو in 3:4 lasts 260 ms and carries
"All-Able (of)": about 30 letters a second on screen between neighbours at 9
and 3. `EnglishScrollClock.levelGlossSpeeds` lets only a gloss faster than
14 letters a second borrow time, and only enough to come down to that speed
or to 1.6× its neighbour's (whichever needs less), by moving the boundary it
shares with the slower neighbour at most 350 ms — never across a stop, never
for a gloss holding a stop inside. The tajweed shape inside each gloss is
stretched with it.

Borrowed time is only real if the word may use it. English Scroll's ink is
gated by word state: a word stays unread until its Arabic word is active, and
its window is released (snapped full) when that word ends. So the active
English word follows the clock (`EnglishScrollClock.wordAt`,
`rememberEnglishClockWord`): the gloss being inked is the active word, timed
across its levelled window (plus 450 ms for its smoothed tail). Re-says and
unconfirmed taps keep the voiced word. The first build of the levelling
missed this, and on the phone "All-Able (of)" still filled inside dhū.

3:4: "All-Able (of)" 355 ms at 29 letters/s → 765 ms at 14; "(as) guidance"
27 → 16; nothing in the verse above 16. Over 4,236 fills (60 verses, three
reciters): the fastest gloss 66 → 39 letters/s, p99 29 → 23. Levelling every
boundary to 1.6× was tried and rejected: it moved ordinary words off their
Arabic (gloss-middle to Arabic-middle p90 35 → 180 ms). Targeted, the median
stays at 10 ms and p90 is 115 ms — the fast glosses themselves.

## Fences

Each of these was tried and measured, and each is load-bearing:

- **Do not let the Arabic's word state gate English Scroll.** The clock owns
  both progress and the active word (the one rule above).
- **Do not finish every word early.** That is a stop per word (section 1).
- **Do not feed English the Arabic letter map.** It parks and races (29c47d4e9).
- **Do not smooth across a pause or a verse's ends.** The stop disappears.
  Ease into it instead (section 8): the stop keeps its place and length.
- **Do not lower the deep threshold globally to catch shallow pauses.** The
  shallow rule exists only beside a stop mark.
- **Do not rebuild a running verse clock with new pauses** except for words
  still to come in Scroll.
- **English must not fade into the next word.** The overlap and the lead are
  shifts of time, not an anticipatory fade (user rule, 2026-10-23).

## Reproducing

- JVM tests: `EnglishPausesTest`, `EnglishSmoothTrackTest`, `TajweedTempoTest`,
  `EnglishTempoWarpTest`, `PaperInkEnglishWindowsTest`,
  `EnglishRevealTimingTest` (`./gradlew testDebugUnitTest`).
- Pauses against real audio (ffmpeg, numpy, network):

  ```sh
  python3 tools/english_pause_probe.py hani --verses 25
  python3 tools/english_pause_probe.py verse --surah 3 --ayah 7
  ```

- On a device: `adb logcat -s EnglishPauses` logs each verse's detected pauses
  and every pin.

## Limits

- The first second or two of a verse playback starts on can miss a pause
  while its audio is decoded; Mushaf misses that verse's pauses entirely.
- The Mushaf leaf's English is translated prose, so a stop lands where the
  translation's phrasing puts it, not strictly word for word.
- Thresholds were set on Hani ar-Rifai and Alafasy; other voices and recording
  rooms are unmeasured.
- Web English Scroll does not have any of this yet.
