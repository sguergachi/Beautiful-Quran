# Translation Mushaf visible word transitions — 2026-10-25

Status: accepted for publication after independent review of the production
diff, visible evidence, durable tool, current source fingerprints and final
JVM/debug/replay/R8 gates. The limits below remain explicit.

Follow-up: the user still found this build awkward and clarified that reliable
word matches should finish in pace with the reciter, ideally 100 ms earlier.
The visible idle reduction below did not establish that property. The
[word-finish follow-up](ENGLISH_WORD_FINISHES_2026-10-25.md) records the endpoint
policy and the separate forward/render checks for it.

The user reported no visible change after the steady-reading build `cea2a088c`
and confirmed **English Mushaf — Translation**. Word-by-word Mushaf already
felt good and stays locked. This follow-up changes presentation distance and
fallback edge width, preserving semantic owners, source timings, output
calibration, translations, font size, layout and the shared audio clock.

## Why a steady clock still looked stop/go

The old and new Hani 64:1 opening rates were nearly identical: 5,750/73 versus
13,234/168 ms per character, a difference below 1 ms per word. That opening
could not demonstrate a noticeable pacing improvement. Later captured field
positions did follow the new plan; there is no basis to blame installation.

The moving character edge still spent time crossing spaces with no glyph
changing. Hani 64:1 has 168 characters, including 30 spaces. At 78.77 ms per
slot, those spaces consume about 2.36 seconds of glyph-free travel. Each
fallback word then restarts the generic 1.6-word-width feather, with a broad
entrance and settling toe. A linear scalar clock does not remove those visible
stops. Paper's shader has a separate one-letter lead-entry field that prevents
onset flashes; it remains unchanged.

## Narrow presentation change

`EnglishReadingExtent` maps the cumulative audible fraction to non-whitespace
character slots, then back to source character coordinates. Spaces take zero
time; the next printed word starts at zero reveal, with no anticipation. In
64:1 the same 13,234 audible milliseconds now cover 138 ink slots, about
95.90 ms per slot. The extra time is redistributed over ink rather than removed
from the verse. Known audio quiet still holds the exact fractional edge.

The extent is built once per cached whole-verse plan. Forward drawing, exposure
times, inverse pause endpoints, material finish times and carried-leaf follow
consume the same map. A composed regression covers normal spacing and a page
cut. If source whitespace is normalized by the printed leaf, the extent alone
is disabled with `normalized_spacing`; steady reading retains its earlier
continuous projection. A synthetic repeated-space case checks that path over
601 samples. No current corpus row needs that guard.

Only published translation plans with a non-null `readingFlow` opt into a
fallback feather of `1 / printedWordLength`, approximately one mean printed
character advance. The zero-progress unread floor and bounded soft edge stay.
This matches an edge-width scale, not Paper's material kinetics; punctuation
and glyph widths vary. Cards, Scroll, source-overlap/repeat occurrence
fallbacks, generic Fade, AGSL and shader lead-entry are unchanged.

## Same-audio, same-layout comparison

Three recordings use Hani 64:1, the same API 35 emulator, Junicode setting,
Translation Mushaf layout, speed and audio. The settled first-line crops
(`x=0..1080`, `y=350..420`) are pixel-identical: maximum difference zero for
both candidates against baseline. All first five words select and retain the
fallback route in all three recordings. There are no AndroidRuntime crashes
or exported sample/frame drops. The final native export replays through the
production HighlightReplayTest filters successfully.

Fixed masks select first/last glyph-body cores below the upper letter region,
excluding background and glyph edges. Median core ink is normalized against
unread 0–2 s and settled 25–29 s. A crossing requires three of four successive
samples. The primary idle metric is the previous last body reaching 80% ink to
the next first body reaching 15%. These are visible glyph thresholds, not
word-level mean brightness or acoustic residuals.

The durable tool's **60 fps resampled traces** give:

| Transition | Baseline ms | Blank-only ms | Blank + letter feather ms |
|---|---:|---:|---:|
| Whatever → is | 216.7 | 266.7 | 133.3 |
| is → in | 100.0 | 83.3 | 83.3 |
| in → the | 183.3 | 133.3 | 100.0 |
| the → heavens | 233.3 | 233.3 | 100.0 |
| heavens → and | 266.7 | 200.0 | 100.0 |
| and → whatever | 283.3 | 200.0 | 150.0 |
| whatever → is | 266.7 | 250.0 | 150.0 |
| is → on | 183.3 | 116.7 | 100.0 |
| on → the | 166.7 | 100.0 | 66.7 |
| the → earth | 200.0 | 133.3 | 133.3 |
| Mean | 210.0 | 171.7 | 111.7 |

Blank-only made the first transition worse, so zero blank duration alone was
not accepted as a visual fix. The final feather reduces the primary mean by
about 47%, while leaving a soft visible entry. A sweep of finish thresholds
70/80/90% and start thresholds 10/15/20% gives 90 paired observations over these
same ten transitions. Every final-vs-baseline observation improves on the
resampled traces. An independent recomputation reproduces all three saved
90-row files exactly; one common normalization across the captures also keeps
all paired improvements. First-five entry cores show no lightening spike over
25 gray levels while entering; this is a limited entry check, not universal
flicker certification.

### Original frame timestamps and sampling limits

The MP4s are variable-rate recordings: 736 / 790 / 747 original frames over
about 34.2 seconds, approximately 21.5 / 23.1 / 21.8 fps. The tool's `fps=60`
filter duplicates frames. Its smallest 16.7 ms grid difference is not guaranteed
source temporal resolution or perceptual precision.

The independent reviewer decoded original frames without an fps filter and
paired them with ffprobe `best_effort_timestamp_time`. It reused the fixed
masks and threshold pairs, normalized against unread 1.5–2.5 s and settled
25–29 s, and required crossings to persist for at least two source frames and
through the next 50 ms of captured samples. Original-PTS primary
means are **210.23 / 171.88 / 111.63 ms**. The final first four idles are
134.28 / 84.27 / 100.31 / 98.53 ms, against baseline
216.93 / 99.83 / 183.13 / 233.73 ms. All 90 paired threshold observations still
improve; observed gains range from 15.566 to 167.044 ms. Sampling uncertainty
remains, and these reuse ten transitions rather than 90 independent trials.
This is positive visible evidence for this passage and fallback route, not
universal readability, shader equivalence or physical audio synchronization.

## Automated and release gates

- Full Android JVM gate: **1,716 tests, zero failures/errors, one existing skip**;
  192 XML suites preserved before the filtered native replay replaced live XML.
- Final exact-source R8 release: **BUILD SUCCESSFUL**; debug build and native
  production replay pass. No further production behavior edits followed them.
- Full runtime audit: **37,411 rows / 6,236 verses / six voices**;
  **633,612 equal-ink-rate checks**, **13,669 quiet plateau/inverse checks**.
- All **33,602** safe steady rows use blank compression; zero normalized-spacing
  fallbacks. **492** source-overlap rows (82 verses), **3,317** source-repeat
  rows and the 69 internal-repeat blocks retain their reviewed policy.
- **948,253** contained letter cores; **zero new positive-legacy collapses**;
  **401 baseline source-order skips** remain unresolved. The separate raw
  whitespace-token collapse denominator is 405. Cards, 24:1 repeats and 24:2
  terminal-tail/carried-cut controls remain intact.

Only Hani currently has measured within-word quiet in this path; other voices
retain timing-gap evidence. The audit's semantic early/late counters are not
accuracy scores: 483,941 tokens finish over 100 ms before their inferred owner
end, and 324,237 after it. Translation order, weak owners and intentional
presentation pacing all contribute. No semantic or acoustic certification is
claimed.

## Reproduction and local evidence

`tools/measure_english_interword_idle.py` requires ffmpeg, ffprobe and numpy.
Ordered static masks contain first/last core coordinates relative to the crop.
The output labels resampling, records actual source frame metadata, windows,
mask/video SHA-256 digests and all threshold observations:

```sh
python3 tools/measure_english_interword_idle.py \
  "$VIDEO" "$MASKS" --crop 1080:70:0:350 --output "$METRICS_JSON"
```

Private artifacts remain outside Git:

- `/tmp/bq-space-{baseline,candidate,letter}.mp4`, corresponding `-native.txt`,
  `-crashes.log`, `-cores.npy`, `-metrics.json` and `-verified.json` files.
- `/tmp/bq-space-body-masks.json`:
  `e946d96d4b87ae370be55335068fa45f5411ae60402e38d386e57cf3301398c3`.
- Baseline video:
  `f51189b0f9d90c0d23b3f5f5beb84e1b5af93ad88c37ad30eb3d7ad60128070c`.
- Blank-only video:
  `90a89a0306f863a1425f4dca8c729441b66a0ea5bec358f34871560ce05289da`.
- Final video:
  `629494fb8f63462ac1cab8d0436f158ef6de5674eea7d7739fa97ea2b6be10cc`.
- `/tmp/bq-space-full-tests`, `/tmp/bq-space-letter-full.log`,
  `/tmp/bq-space-letter-replay.log`, `/tmp/bq-space-letter-release.log`.
- `~/.cache/bq-mushaf-report/blank-audit/`: source/timing/silence fingerprints,
  summary and per-row audit evidence. Corpus digest:
  `6bcfa75c289458d04b1455bfe64f23469219319eac92218dda37ee715af0a566`.
- Independent original-frame evidence:
  `~/.cache/bq-reading-review-20261025/visible-ab/` (`source_pts.py`,
  `source-pts-metrics.json`, `*-source-cores.npz`).
