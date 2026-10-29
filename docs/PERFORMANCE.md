# Performance

Butter smoothness is a core feature. The app is built to render at the
display's native refresh rate (90/120 Hz where available) with nothing on the
UI thread that doesn't belong there. This file documents every technique in
use and the reasoning, so future changes don't regress them.

Web-specific GPU / paint findings (2026-07-16) live in
[§ Web rendering and GPU](#web-rendering-and-gpu) below.

## The frame budget mindset

At 120 Hz a frame is **8.3 ms**. The app's rule of thumb: recomposition is
for *content* changes; everything that merely *animates* must live in the
draw phase.

## Techniques in use

### English leaf pagination

The English leaf uses `EnglishJustify` to choose breaks and share line width
between glyph expansion, tracking and word spaces. Its line count can differ
from greedy wrapping and can fall as a prefix grows. The ruler therefore
counts each candidate's justified setting and searches past an overflow until
the offer's width bound excludes later fits.

Word advances are remembered across the book; prefix probes reuse the offer's
positions rather than laying out every candidate again. Only visible leaves
fit spans against the actual drawn edges, with two measurement passes. The
horizontal cache keys omit vertical `lineHeight`: the line-count probe, height
probe and drawing share the same justified paragraph and word advances while
each still measures with its own leading. Font size, face, tracking, spans,
measure and carried-line setting remain in the keys.
The ruler, height fitter and renderer use the same carried-line setting. A line
without gaps keeps its natural width, so the model cannot count it as fitting
by shrink alone. The hand still calibrates at natural spacing.
Every leaf keeps that same book hand. Page fitting adjusts only leading and
checks its drawn height; it never reruns the layout at a smaller font size. Oversized
verses are carried by the measured pagination. The debug justification audit
also reports leaf font-size extrema and vertical overflow.

Pagination runs off the main thread and is written to `EnglishBookCache`.
The cache key includes the justification tuning and algorithm version; a
SHA-256 digest of the complete layout and prose identity keeps filenames
within the filesystem limit. A changed line-breaking rule invalidates the old
book. The leaf waits for the measured book and retains the soft directional wash.

The 2026-10-14 comparison used the same pooled emulator, debug probes and
eight leftward swipes from An-Nur's opening. Ten paragraphs required 29 → 10
cold justification passes and 1,743 → 475 word layouts on the UI thread;
total justification CPU time fell from 1,668 → 651 ms. The same paragraphs and
leading were rendered. These are measurements of duplicate layout work on
one host, not physical-device frame rates. Temporary probes were removed.

English ownership is solved lazily per verse and cached for the selected text
source. Semantic ranges and continuous forward wash bands are projected once
when the paragraph is built. Frames read the existing Arabic motions; adjacent
paper covers with equal alpha merge, and one band carries the active soft edge.
The pure whole-verse phrase plan is cached against the immutable alignment,
text source, reciter, actual timing snapshot, first-voice gate and measured
quiet; a provider timing replacement rebuilds it. Drawing and page follow read
the same plan. Connected translation speech uses one cumulative audible-time
clock, projected into cached geometry partitions. Its normalized speech union
and quiet subtraction are built once; frames binary-search cached interval sums.
The alignment retains its printed source text by reference. A plan builds
`EnglishReadingExtent` offset/prefix arrays once, giving blank-space travel zero
time with O(1), allocation-free forward/inverse character projection. Sources
whose whitespace is normalized by the leaf retain the prior projection instead.
Verified literal word endpoints and whole-unit protection intervals are also
cached with the plan. `EnglishReadingPace` fits a deterministic sequence of
compatible endpoints once, with analytic derivative bounds. Draw frames
binary-search its knot arrays and evaluate a cubic; they allocate nothing and
never solve an inverse. Inverse root searches run when cached windows/finish
times are constructed. No live integrator, per-word timer or cadence optimizer
is introduced.
Printed word ranges are cached with the paragraph. Both the base cover and
paper material use the same per-word projection of that edge; only one next
unread word per verse requests material prewarming.
First-pass glimmer shares that band; repeats keep the semantic ranges. Geometry
stays cached by layout and range, and only repeat/glint layers request shaped
paths.
The moving English leaf edge interpolates between cached source/printed letter
anchors with one binary search. Exact word boundaries retain their integer map;
animation never rounds its position to a whole letter.

The measured book carries the repository's existing immutable prose map into
the pager, so opening a leaf never reloads its text or scans the runtime corpus
for page membership. The pager retains the previous and next English leaves.
Each leaf fits its paragraphs on `Dispatchers.Default` with its own text
measurer, then publishes the finished setting for drawing. Preparing a new
neighbour therefore does not run paragraph justification on the swipe's UI
thread. Changing the prose, geometry, type or carried-line setting starts a new
fit; cancelled work cannot publish over it. The initial opening and distant
dial landing still defer neighbour composition to protect their first frames.

Twelve recent completed leaf fits stay in a process-local LRU, keyed by the
styled prose, chapter openings, well, measure, density, font resolver, direction,
type palette and carried-line setting. Reopening one uses its fit immediately.
Verse ownership and tap/wash callbacks are rebuilt for the current visit and
are excluded from this cache. A process restart still restores the book from
disk and fits its first leaves; a changed layout or text must be measured again.

### 1. Draw-phase-only animations (zero recomposition fades)

Every fade in the app — word highlights, recited-word settling, ayah
dimming, the chrome recede — animates a `State<Float>` that is read **only
in the draw phase**. Most use a `graphicsLayer { }` block:

```kotlin
val ink = animatedInkAlpha(state)          // State<Float>, not read here
Modifier.graphicsLayer { alpha = ink.value }  // read in the draw phase
```

Because composition never reads the animated value, a running fade
recomposes and re-lays-out **nothing**: it only updates a render-node
property, which is close to free. The same pattern applies to the player-bar
chrome (`chromeAlpha: () -> Float` — a deferred read, not a Float
parameter).

Word-local glyph fades use `Modifier.glyphLayerAlpha` instead. It keeps the
same draw-phase-only behavior but expands the offscreen layer beyond the
logical word box, preserving EB Garamond serifs and Hafs marks that overhang
their advances while alpha is animated.

The paper stack follows the same rule: its live page position is read only
inside `graphicsLayer` blocks — the sheet's transform and the alpha of its drop
shadow. Only threshold-derived booleans return to composition, so dragging or
settling a page does not wake the root three-sheet composition every frame. A
bare `stackPosition.value` anywhere in the stack's composition (a `BackHandler`
`enabled`, an `if`) undoes this for every turn; wrap it in `derivedStateOf`.

The shadow is a sibling layer, not a draw modifier on the sheet. A draw callback
that reads the position invalidates the sheet's own layer, which re-records all
of the sheet's content that has no layer of its own for each frame of the turn.
The cast is recorded once at full strength and the turn changes only its layer
alpha (`CompositingStrategy.ModulateAlpha`, so no offscreen buffer clips the
spill past the sheet's edge).

The entrance cover follows that rule too. Caption alpha stays a `State` until
its layer reads it, and the loading rule reads its sweep only in its Canvas;
neither animation recomposes the cover. The medallion retains its
stroke-by-stroke build and the titles retain their soft letter washes.

The gilded figures are recorded once and re-lit, never redrawn. Each figure
(`TooledGold`) records its embossed relief and its opaque gold leaf into
remembered layers at first size; a sheen step re-records only the gold pass —
three cheap ops: replay the leaf layer, rotate, and a gradient rect
`BlendMode.SrcIn` into an offscreen spacer. The ramp's axis is frozen
horizontal through the figure's centre (identical to the brush at rest) and the
sheen rotates it, mirroring the web port's glint, so no per-step re-recording
of the band's or seals' thousands of points ever happens again.

That is half the law. The other half is where the render thread spends its
frames: a node with no persistent buffer has its display list re-executed on
the RT for every frame in which the surface is damaged — recording once saves
the UI thread, not the RT, and the RT re-rasterises the giant paths each time.
Measured on the emulator, the settled cover ran at ~20 fps with the RT pinned
near a full core while the UI thread was idle; its real per-frame work was a
few ms of stencil-covered giant paths. Everything static and heavy therefore
lives in a `CompositingStrategy.Offscreen` layer, which renders its content
once and composites the result as a texture: the leather's field weave (its
four full-screen giant paths) and each gilded figure's box. After that flip the
same emulator measures the settled sweep at 0/56 janky frames (p50 17 ms), the
medallion build at 1.95% (p99 57 ms), and the swing at 2.2% (p95 32 ms), with
zero slow-UI frames in the sweep. The reader's chapter backdrops and surah
band keep their own weave layers on the same rule.

The swing itself stays a pure layer transform by freezing the light on top of
that: `beginOpening` snaps the sheen into a frozen `State` first, so from
Opening until the ceremony leaves, the gold nodes do not re-record at all and
the hinge turns only RenderNode properties. Edge-on the moving light is
invisible anyway. The tilt sensor's demand is kept alive by an explicit
`GildingDemand(liveSheen)` in the ceremony, since the renderers' own demands
see the frozen wrapper.

The QF SQLite cache opens and migrates lazily on the first background read.
The application launches its checkpoint/refresh decision on `Dispatchers.IO`,
so these disk operations do not delay the first cover composition. The bundled
database verification and existing entrance readiness checks still run.

The scroll playback bar keeps one owner throughout every stack turn.
Home covers the mounted bar before playback creates a session; its draw-phase
transform shares the reader's reveal offset, scale, and whole-sheet pivot.
After playback it sits above Home and stays still through that reveal. Its owner never changes at a swipe threshold. On a reader
→ Settings turn it follows the reader's transform through a draw-phase layer,
with its pivot at the sheet's centre. The earlier 1.02-page
visibility gate swapped the pinned bar for an embedded reader bar just after
finger-down; that changed both sheets' layout and rebuilt the transport during
the swipe. Settings still receives its own chrome: the pinned bar travels
fully offscreen with the reader. The chapter's top-left arrow stays in its
sheet throughout a turn, so no drag/settle state swaps that slot either.
Gilded ornaments have their own recording layers so tilt-driven sheen redraws do not invalidate the sheet around them.

Developer → **Record performance profile** works in release builds too. It
captures ten seconds of frame callback intervals, main-thread stack samples,
and navigation marks, then offers one share containing the text log and an
available system trace. Capture is explicit; release builds register no
automatic startup traces. See [PROFILING.md](PROFILING.md).

The no-gloss Arabic path (`ResponsiveHafsAyah`) cannot put `letterFadeIn` on
the whole ayah. It keeps the shaped ayah as static full-ink spans and applies
`shapedWordBloom` in the draw phase: upcoming words get a full-strength paper
cover from the first Upcoming frame — and the same cover is used while the ayah
is recessed (`dimmed`), so landing on the next verse does not change unread ink.
On Paper, the verse that just owned the lyric line ramps that cover in over the
existing recess tween; verses still ahead mount at the full Upcoming cover, so
the soft outgoing handoff cannot introduce a full-ink flash.
Block alpha stays at 1 during recitation in every mode (word-layer alpha for
gloss; paper covers over opaque glyphs for shaped English/Hafs). First-pass
pulls the cover back on the ink-wash curve; repeat SrcIn-tints the same shaped
glyphs orange then DstIn-washes. Progress is read only at draw time, so the
sweep never reshapes the ayah or paints onto neighbouring words.

`AyahBlock` creates one `InkMotion` per word before selecting a renderer.
Layered gloss text and both shaped modes therefore share the same sweep,
entry-mask, repeat gate/release, feather, and glint clocks. The renderers only
adapt those deferred values to `letterFadeIn` layers or `ShapedWordBloom`;
switching paint strategy does not create a second animation lifecycle.

Tarjīʿ follows the same rule. The audio thread publishes one small volatile
state per 20 ms content hop; only the Active strong-hold word runs a vsync
sampler, and its `State` is consumed in the glint draw layer. Do not batch
those audio updates: a 232–256 ms batch undersamples the 5–10 Hz effect. Do
not collect them in composition either; that would recompose an ayah at audio
rate for a paint-only alpha change.

Because the active word's sweep is read inside that draw scope, the **whole
bloom list is rebuilt every frame** — one `UpcomingDim` per unread word, up to
127 of them on 2:282. So each bloom must derive only the geometry it actually
paints with:

- Paper covers (`UpcomingDim`, `InkReveal`) need per-line word boxes. Those are
  pure geometry of (layout, range) and change on relayout, not as the wash
  advances, so they are memoised per `TextLayoutResult` and dropped whole when
  the layout changes identity.
- Only `ColorReveal` needs `getPathForRange` — it re-draws and tints the shaped
  run. Deriving that path for every bloom (which an earlier version did,
  unconditionally, then discarded for two of the three kinds) cost ~2 selection
  paths per word per frame for nothing.
- A glimmer cannot blur that selection path because it is rectangular.
  `GlyphHaloCache` renders the selected laid-out glyphs into a tiny alpha mask
  once per word and blur radius, then each animation frame only recolours that
  cached mask. The cache is layout-local and retains the eight most recent
  words, covering overlapping dry-downs without growing across a long ayah.

Settled inactive ayahs do **not** redraw continuously: their draw-scope state
reads are static once the 400 ms recess/focus tween finishes.
The fibre fringe reads one shared 720 × 720 paper tile (2 MB, outside the
32 MiB word cache), made once per process on its own background thread when
paper ink first warms a word, so it never queues behind word fields. The
tile took 0.59–0.88 s on the emulator (debug build). Only AA taps inside the
band a fibre can reach sample it. On the emulator, four interleaved 40 s
playback pairs (debug, 1080 × 2400, Al-Baqarah) kept the median frame at
18 ms in most runs; p95/p99 were 21–25/25–31 ms with the fringe against
18–22/21–28 ms without, a tail cost of roughly 2–3 ms within run-to-run
noise. That is not a Pixel measurement.
See [PAPER_INK.md](PAPER_INK.md#fibre-fringe).
Paper-cover bleed is horizontal-only and clipped to each text line's measured
top/bottom; an unread line therefore cannot fade descenders belonging to the
read line above it.

### 2. Recomposition confined to one ayah

The active word is exposed as an un-delegated `State` at screen level and
read through a **per-item `derivedStateOf`**:

```kotlin
val activeWordPosition by remember(ayah.number) {
    derivedStateOf { activeWordState.value?.takeIf { it.ayah == ayah.number }?.wordPosition }
}
```

A word boundary therefore recomposes exactly one `AyahBlock` (whose
`WordUnit` children skip via stable parameters, so only the two words whose
state changed re-execute). The same pattern applies to `isActiveAyah` /
dimming: each list item reads `activeAyah` through a per-ayah
`derivedStateOf`, so an ayah boundary only wakes the two blocks whose
active bit flips. The rest of the screen — list, top bar, player — is
untouched.

### 3. A cheap position ticker that publishes only boundaries

The sync loop polls `player.currentPosition` every 33 ms, but the flow chain
applies `distinctUntilChanged` on the **derived word position**, so
downstream state changes ~2–3×/second during recitation, not 30×. The loop
runs only while the surah is loaded (`flatMapLatest` + `WhileSubscribed`),
drops to a gentle 250 ms poll while paused, and stops entirely when the
reader leaves the screen. Play/pause and Media3's authoritative position-event
counter are part of the `flatMapLatest` identity, so resuming or receiving any
seek/loop/adjustment cancels a paused sleep and samples immediately; a word tap
cannot start audio up to 250 ms before its ink restart. Repeat / high-water
tables are built once when timings load (`HighlightEngine.PreparedTimings`);
each immutable `ActiveInfo` is prepared beside those tables. The poll's binary
search returns that same object throughout a segment, and `ActiveWordPollCache`
reuses the matching UI snapshot until the word, ayah, or genuine ink activation
changes. The steady 30 Hz word poll therefore allocates no per-word objects;
both allocation and downstream emission happen only at real ink boundaries
(~2–3×/second).

`WordInkPublication` applies an explicit word tap synchronously before its
player command, then consumes those boundary polls. Keeping tap intent and
the displayed word in one publication avoids an asynchronous `combine`
turn where playback has begun recessing the page but the tap prefix is
still missing. Frame clocks and per-ayah derived subscriptions are unchanged.
The word-play exception follows the player's position intent, so a second
MediaController acknowledgement of the same seek does not discard its
prefix. Actual media generations still own the captured wash windows.

### 3b. Playlist preload + cache warm

ExoPlayer's `PreloadConfiguration` buffers ~5 s of the *next* ayah in the
playlist (Media3 1.10), cutting inter-ayah join latency. `AudioPrefetcher`
still warms a few ayahs beyond that on any network, and the whole surah on
unmetered Wi‑Fi, writing into the same 1 GB LRU listen cache the player
reads. Fully kept downloads (the non-evicting keep tree) are skipped — they
are already on disk, and refetching them into listen was wasted network.
`Job.cancel` does not stop Media3's blocking `CacheWriter.cache()`; the
prefetcher cancels the active writers on supersession and release, with the
same register-under-lock race RecitationDownloads uses. No extra network
framework.

### 4. Edge fades without offscreen compositing

The scroll-edge dissolve is drawn as a **gradient overlay of the solid paper
color** (one rect per edge in `drawWithContent`). The obvious alternative —
an alpha mask with `BlendMode.DstIn` — requires
`CompositingStrategy.Offscreen`, which renders the whole list into an
offscreen buffer **every frame while scrolling**. The overlay is visually
identical on a solid background and costs almost nothing.

### 4b. Transient progressive vellum

The contextual guide's progressive vellum is developer-gated and transient.
On Android 13+ one AGSL brush draws the arbitrarily angled tapered pigment
field and samples its analytic alpha mask five times only inside the feather. A second
pass records each visible ayah—not the virtualized `LazyColumn`, whose child
display lists cannot be sampled reliably—into a small graphics layer. A native
GPU blur replaces the sharp ayah progressively through paired `DstOut`/`DstIn`
feather masks, preserving the real scripture and ribbon outside it. Android
11–12 uses a smooth gradient fallback. Idle reading creates neither guide
shader layers nor draw cost.

### 4c. Layout reads confined to derived state and coroutines

`LazyListState.layoutInfo` changes on every scroll frame, so reading it in
plain composition would recompose the whole reader while scrolling. The focus
engine (`ReaderFocusController`) reads it only inside `derivedStateOf`
(`focusedAyah`, `focusedPosition`, verse placement) — which re-notifies only
when the *derived answer* changes — and inside the `focus()` scroll coroutine.
The verse-position math itself lives in the pure `FocusEngine`, so it is
allocation-free and unit-tested off-device. The word-follow gate is read
behind an `isActive &&` short-circuit, so only the single reciting `AyahBlock`
ever subscribes to it.

### 5. Virtualized, keyed, stable lists

- `LazyColumn` everywhere; ayah items are keyed by ayah number so scroll
  position and item identity survive data refreshes.
- All item parameters are stable (immutable data classes, enums, primitives,
  remembered lambdas), so unaffected items skip recomposition entirely.
- Top and bottom `contentPadding` ≥ the fade height on every sheet, so
  content sits clear of the soft edges at rest and can scroll under them.

### 5b. Mushaf pager — same isolation as the verse list

The 604-page pager is virtualized (`beyondViewportPageCount = 1`). A word
tick must not remasure three pages or recreate 150 `Text` nodes.

- `activeWord` stays an un-delegated `State`. Follow-page turns collect it
  from `snapshotFlow`; each ayah's ink slot reads it through
  `derivedStateOf`. The page layout never sees the value.
- Ink packs are published per ayah into a `SnapshotStateMap`. Pack identity is
  read in composition so only that ayah can swap between its cheap recess and
  live wash modifier chains; the pack's animated values are read during draw,
  so wash frames invalidate paint rather than layout.
- Each QCF word wraps its ayah-map lookup in `derivedStateOf`: a pack publish
  performs one keyed lookup, while every wash frame reads only the cached
  pack. Draw must see the latest pack so a completed sweep from the previous
  word cannot flash before the next entry mask. A recess-only chain that sees
  Active before recomposition is held at Upcoming alpha until its wash layer
  is attached. Ayah selection reuses that pack's draw-phase recess-cover
  `State`; its fade does not recompose or relayout the leaf per frame.
- QCF page fonts are held as one atomic family/typeface pair in a bounded LRU
  and preloaded on `Dispatchers.Default` for the settled page ± 2, so a swipe
  does not `Typeface.createFromAsset` on the UI thread. For Arabic, that same
  window warms the existing glyph-ink profiles off the UI thread; a cold page
  otherwise rasterized and scanned each word during the pager's prefetch measure.
  A delayed playback turn rechecks the captured ink frontier, playlist ayah,
  playback generation, and word state after the target face loads, discarding a
  stale turn before its animation starts.
  The cache, reference resolution, and spacing calculations are unchanged.
  Line geometry is keyed by page, display row, size, and measure. The page's
  seventeen-row reflow is a linear token-width pass remembered by page + typeface; playback ticks and
  ink animation frames never repeat it. Geometry remains in the bounded
  process cache. Non-adjacent chapter and search jumps warm that same target
  window before moving the pager. The dial warms only the target that rests
  under the finger (fly-over cells are debounced), including that leaf's
  glyph-ink profiles, then releases its bubble and retract on one frame and
  moves the pager on the next. A fast lift also
  starts an urgent target-only warm-up; it never serially loads the five-page
  window or waits for the retract spring before requesting the selected leaf.
  The return dot's tappable seed is immediate; its entrance starts after the
  selected leaf's first frame. Only a confirmed page-changing release parks
  pager neighbours; taps, cancellations, and same-page gestures never tear
  them down. On a real landing they stay disposed through the entrance and
  return only once the dot is full-sized, avoiding cold leaf work on the
  animation clock.
- Each Madinah line owns one pointer-input node, not one per word. Its QCF word
  nodes retain the directional `shapedWordBloom`, while the leaf itself owns a
  recorded graphics layer using the default compositing strategy, so a fling
  transforms the display list without re-recording the word nodes.
  The pager requests `FrameRateCategory.High` while
  drawing on platforms supporting [adaptive refresh rate](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate),
  allowing the display's high refresh rate without a permanent window override.
  The settled page runs live ink; during an automatic turn the voice's page
  may join it so a short opening word does not restart its wash when the leaf
  lands. Other neighbours
  keep static ink. On the voice's page, only the active ayah owns word motions:
  completed ayahs use a static full-ink pack and later ayahs share a motionless
  recess pack. One page-level accessibility node exposes the canonical
  Arabic instead of hundreds of private-use glyphs.
- Chrome (`MushafReadingSheet`) keys the gilt seed on `settledPage`, not
  `currentPage`, so a fling does not regenerate ornaments mid-turn.

The 2026-10-08 emulator comparison used release APKs, AOT compilation, a
settled page 5, and twelve rightward swipes to page 17 with 600 ms pauses.
Against `0c3df7c5`, background profile preparation reduced the 95th-percentile
frame time from 61 to 44 ms and missed deadlines from 41/338 to 33/348 frames.
Prefetch measure averaged 42.8 → 37.0 ms, with its maximum 83.2 → 45.8 ms.
Page 5's text and controls were pixel-identical below the changed running
head. Forced `Offscreen` page caching was rejected: its 95th percentile rose
to 113 ms. These are a same-host 60 Hz comparison, not a physical-device
120 Hz result; cold page measure still exceeds the 8.3 ms frame budget.

### 6. R8 release builds are what ships

CI publishes `assembleRelease`: R8-minified (full mode), resource-shrunk,
**non-debuggable**. Debug Compose builds carry debug render checks, no
inlining, and JIT-cold code — they can feel 2–3× slower. If a build "feels
janky", first ask: is this the release APK?

### 7. Data access off the UI thread, once

- The prepackaged SQLite DB is copied out of assets once, then opened
  read-only; all queries run on `Dispatchers.IO` through suspend functions.
- During the closed-cover load, Android sizes SQLite's native page-cache ceiling
  to the database file plus 1 MiB and runs `quick_check`, touching the complete
  file before opening. The singleton connection retains that cache for the
  process. It also reads and parses the separate 77,429-row QF cache once into
  process-lifetime word and per-surah lookup maps, so no chapter pays that cost.
  Web's sql.js database already retains the complete fetched buffer. Do not
  create additional per-screen copies of either cache.
- A QF invalidation snapshot is compared with the Android cache and
  only changed row identities are mutated in the transaction. Normal refreshes
  use Content Sync's smaller native upsert/delete set directly. An unchanged
  refresh advances freshness without invalidating the process catalog or
  repaginating English. The English disk-book key includes a digest of its
  exact prose, so changed QF glosses rebuild once while unchanged ones reuse
  their measured leaves.
- First bootstrap streams snapshot records through compiled SQLite statements
  inside the single publish transaction. Do not replace that path with one
  `ContentValues`/statement compilation per row: the snapshot is about 250,000
  rows and the cover must not spend its time allocating wrappers around them.
- A surah loads with exactly three queries (ayahs, words, timings) — no
  per-ayah round trips. Timings for one reciter+surah arrive as one query of
  compact JSON rows.
- Surah and reciter lists are cached in memory after first read.
- The English book's measured leaves stay on disk under
  `EnglishBookCache`. Four most-recently-read configs are retained so
  flipping translation, gloss, or typography profile does not remeasure; in-progress
  writes publish atomically and failed writes remove their own temporary
  files. Successful writes also reclaim temporary files abandoned for a day.
  Access/pruning is serialized; the just-written book is retained even when
  timestamps tie. `ensureMushaf` cancels superseded work, and the pure packer's
  `checkCancelled` callback checks the coroutine before each layout attempt.
  This also stops work on ViewModel teardown. Generation gates still protect
  publication and writes.
  Each typeface keys its own book (`bookCacheSuffix`: the face, its font
  files' hashes and every constant that moves a line), so switching the
  developer comparison does not reuse the other face's measurements. A
  developer switch retains the current measured book while its replacement
  paginates, so the leaf remains available for live comparison. Cold starts
  and content changes still hold English until measured leaves exist.

### 7b. The word-search index is a memory budget, not just a cache

Quran-wide word search eventually builds one entry per **word row: 77,429 of
them**, held for the life of the process after search gains focus. At
that size, per-entry strings are the whole cost, and `Cursor.getString()` hands
back a *fresh* `String` per row — so binding an ayah's text onto each of its
words duplicated it ~12×.

Anything ayah-wide therefore lives behind one shared `WordSearchAyahContext`
per ayah (6,236 instances) instead of per word. The loader reads those large
translation/name strings in a separate 6,236-row cursor rather than decoding
and discarding them on every word row. Product search is English-only, so it
also does not manufacture Arabic or transliteration search keys that no active
reader source can use.

Cold first results do not wait for that process-lifetime index. Focus warms a
179 KB concept-only candidate asset; after the 120 ms typing debounce, Android
asks SQLite for at most 600 literal or relevant semantic ayahs, materializes
only their words, and publishes that first rank before loading the complete
concept/thesaurus/root rank. Those 600 keys used to be applied as
`surah_id * 1000 + ayah_number IN (…)` which cannot use `idx_words_ayah` or
the ayahs primary key and scans all 77,429 word rows. The filter is now
grouped by the indexed columns (`surah_id = ? AND ayah_number IN (…)`). Host
SQLite on this repository (not a phone): five keys `2001,2255,2282,36001,112001`
returned 184 word rows either way; `EXPLAIN QUERY PLAN` went from `SCAN words`
to `SEARCH … idx_words_ayah`. Earlier measurements of the literal-first
pipeline, before this indexed-filter change, on the constrained two-core
debug emulator were **525 ms** for `peace` and **876 ms** for the multi-word concept
`saving money`, from final typed character to visible results and including
debounce. The complete rank then replaces it. App startup stays free of a
full-Quran scan.

The candidate query may use the longest token from a phrase even when that
token is short. This preserves the sub-second first-result path for literal
phrases such as `if he`; single short tokens still skip the broad SQL fallback.

The ranker still returns every matching occurrence, but query-local score
caches evaluate each distinct lowercase field only once. The committed corpus
has 21,947 distinct English glosses across 77,429 word rows, so the common
Scroll path avoids roughly 55,000 redundant relevance evaluations per stage;
thesaurus expansions share one cached result per gloss as well. The cache dies
with the query and does not enlarge the process-lifetime index. Already-folded
index fields also bypass repeat lowercase conversion in the hot loop.

**Rule for anything added to this index:** if the value is the same for every
word of a verse, it belongs in the shared context. A single extra ayah-wide
`String` field on the entry costs ~12× what it looks like it costs.

Run `npm run benchmark:search` from `web/` for the full-corpus exact, concept,
phrase, typo, quoted, and Mushaf-source timing suite. It reports the median of
nine warm searches per case so performance work is measured rather than felt.

### 8. Streaming audio never blocks rendering

ExoPlayer does its own threading; the UI only ever reads
`currentPosition` (cheap, main-thread-safe by design) and receives listener
callbacks. Audio is cached through a 1 GB LRU `CacheDataSource`, so repeat
listening doesn't touch the network at all.

### 9. Baseline + Startup Profiles

Release APKs ship an ART Baseline Profile (`assets/dexopt/baseline.prof`) and a
narrow Startup Profile that guides R8's DEX layout. The baseline covers startup,
paper navigation, reader/focus/ink, data load, and playback startup; the startup
subset is intentionally limited to the application, entrance cover, theme, and
first chapter sheet so it does not crowd the primary DEX.

The committed rules are a conservative seed because this repository's headless
emulator renderer terminates during instrumentation. The `:baselineprofile`
module is the source of truth for regenerating both profiles from real critical
user journeys on stable hardware. Reader interaction journeys (long-ayah
recitation, mushaf page turn, distant dial jump, cold search) live there too
and must pick Scroll vs Mushaf explicitly. See [Profiling](PROFILING.md).

## Deliberate trade-offs

- **The 33 ms poll** instead of frame-callback syncing: audio position is
  the source of truth and only needs word-boundary resolution (~±35 ms,
  under the source data's own ±73 ms accuracy). The visual fade interpolates
  at full refresh rate regardless.
- **Full-height items** (an ayah can be tall) mean occasional heavy text
  layout when a long ayah enters composition. Arabic shaping is expensive;
  LazyColumn's prefetcher hides it in practice.

## Future headroom (not yet done)

Investigate in measured order. Each trades memory, shaping correctness, or
animation appearance for speed, so none should change without a representative
device trace and a pixel/motion comparison.

**Measure first — not a license to redesign.** Per-frame bloom list
allocation and cold-start SharedPreferences reads were recorded as things to
*attribute* on a device. They are not a brief to change ink rendering or to
defer `QuranApp` initialization. Debug `DevProfiling.trace`
sections name synchronous work (`settingsInit`,
`bookmarksInit`, `quranDbExtract`, `searchRankQuick`, `searchRankFull`,
`wordSearchIndexKeys`, `wordSearchIndexFull`, `englishBookCacheRead`,
`englishBookLayout`, `englishBookCacheWrite`). Sections begin and end on the
same thread without suspension, and compile to no-ops in release.
Until a physical trace shows a stall, leave the wash and startup order alone.

- Cold-start main-thread disk I/O: `SettingsRepository` and `BookmarkRepository`
  are constructed in `QuranApp.onCreate()`, and their initial `read()` does
  synchronous `SharedPreferences` reads. Trace it before changing anything —
  the whole prefs file is parsed once and then served from memory, so this may
  well be noise. If a trace shows a real stall, move the *first read* off the
  main thread; do not migrate to DataStore (see `COMPLEXITY.md`).
- Entrance ceremony: cover chrome + ornament are built in `Activity.onCreate`
  before `setContent`; the paper stack / ViewModels mount only after two
  cover frames (`onWarmStack`). Cover-open `PageTurnSounds` is created lazily
  at warm/open, not on first paint. Leather fill uses `drawWithCache`.
- Allocation inside per-frame custom draw lambdas — especially temporary bloom
  lists and gradient construction. Do not replace the directional feathered
  wash or dim Hafs via glyph alpha to make this cheaper.
- The number and retained memory of per-word graphics layers in gloss mode.
- Long-ayah text shaping / prefetch on first exposure.
- Replace the conservative seed Baseline/Startup rules with output captured on
  a stable physical Android 17 device, then retain them only if Macrobenchmark
  confirms an improvement. Do not regenerate the committed seed from an
  emulator.
- Per-word `contentType` hints if word counts per screen grow (e.g. a future
  mushaf mode).
- Gapless surah-file playback (single MediaItem + absolute-offset segments)
  to remove inter-ayah stream startup entirely.

---

## Web rendering and GPU

The web reader mirrors Android’s engines (Focus / Highlight / Ink) but paints
with DOM + CSS. Frame budget thinking still applies: **animate compositor
properties** (`transform`, `opacity`), not layout or paint-heavy style thrash.

Audited 2026-07-16 (static profile + architecture; confirm with Chrome
Performance / Layers on a long surah).

### Cost model (after sliding virtualization)

Mounted window ≈ 31 ayahs (12 before / 18 after). On Al-Baqarah (~21 words/ayah)
that is still ~4–5k word DOM nodes and ~650 paper covers — far better than a
full-surah mount (~50k nodes), but enough that **how** ink and recess animate
dominates GPU cost.

### Critical issues (fixed)

#### 1. Soft directional ink wash (fidelity — never compromise)

**Product law:** the active word must show a **visible faded leading edge**
(smootherstep directional wash = Android `letterFadeIn` / `shapedWordBloom`).
Whole-word opacity pops and hard `scaleX` cuts are forbidden — they look wrong
even if cheaper.

**Implementation:** `runPaperCoverWash` / `runLetterWash` / `runRepeatWashIn`
rewrite quantized `mask-image` (~48 steps) via cached strings. That is the
correct paint path for one active word. Optimize *around* the wash (ayah-level
recess veil, expand-only mount, emit filtering) — do not degrade the wash.

#### 2. Play-start recess storm (was critical)

**Symptom:** Toggling `[data-reciting]` transitioned opacity on every inactive
word cover, gloss, translit, mark, and translation at once (hundreds of
simultaneous transitions) while focus glide also started.

**Fix:** One **`.ayah-recess-veil`** (paper rect) per ayah + `basmalah-block::after`.
Recess is O(ayahs in the window), not O(words). Full-ink Arabic stays opaque
under the veil (no Hafs mark alpha dirt). Only the active ayah omits the veil
and runs karaoke ink.

### High / medium issues (tracked; not all fixed)

| Issue | Severity | Status / mitigation |
|---|---|---|
| Permanent per-word `.ink-paper-cover` overdraw | High | Still present for Upcoming/Active peel; recess no longer animates all of them |
| Focus `ensureCache` mass `getBoundingClientRect` | Medium | Scroll-only glide after warm; rebuild on window slide / resize |
| Sheet `will-change: transform, opacity` while stack open | Low–med | Still on during reader stack; peel-only promotion is future work |
| Unmemoized `WordUnit` on active ayah | Low–med | Only active ayah reconciles; memo is future headroom |
| Rail full canvas redraw + `getComputedStyle` | Low | Skip when receded is future headroom |
| Hafs shaping on window slide | Structural | Virtualization + hysteresis; pre-warm next ayah is future |

### Techniques in use (web)

1. **Sliding ayah window** — never expand long surahs to full mount
   (`useProgressiveAyahWindow`: ~12 before / 18 after, edge hysteresis).
2. **Store selectors** — Home / Settings / Bookmarks skip karaoke word-tick
   re-renders (`useAppSelector`).
3. **`memo(AyahBlock)`** + CSS recess veil — inactive verses do not React-dim.
4. **Directional ink wash** — smootherstep mask on the active word (quantized
   + cached); soft faded edge is required product fidelity.
5. **Edge fade overlays** — solid paper gradients, not alpha-mask of the list.
6. **Focus glide** — Motion `scrollTop` only after geometry cache warm (no
   per-frame `getBoundingClientRect`).
7. **Lazy orange/search overlays** — mounted only while repeating / flashing.
8. **`content-visibility: auto`** on ayah blocks — paint skip for offscreen
   verses (measure target forced visible for focus).
9. **Static chapter ornaments** — memoized header, footer and compact title
   retain their generated geometry across word-boundary reader updates.
10. **Chapter animation outside the keyed reader** — `ReaderChapterHost`
    retains the transition across chapter remounts. Animation frames write DOM
    styles; React handles the start and finish. New navigation, word sheets,
    sheet/layout changes or resizing cancel the pending handoff. Reduced motion
    advances directly. Verse transform/promotion exists only during the
    ceremony, with no permanent idle `will-change` on every mounted verse.

The [2026-10-16 web performance and Android UX review](quality-reviews/WEB_PERFORMANCE_PARITY_2026-10-16.md)
records the reproduced defects, local render measurements and validation.

### Profiling checklist (Chrome)

1. Long surah → wait for window settle → Play.
2. Performance: “Recalculate style” / “Paint” on play and each word.
3. Rendering → Paint flashing during wash (should be small peel region).
4. Layers: no permanent mask layer thrash on every word.
5. Compare play-start cost: veil transition count ≈ inactive mounted ayahs.

### Web future headroom

- Paper cover only for Upcoming / Active words in view (fewer permanent rects).
- `will-change` on sheets only during the 360 ms peel.
- `memo(WordUnit)` with ink state equality.
- Rail: skip paint when receded; cache CSS colors.
- Focus: ResizeObserver per block or spacer estimates instead of full scan.
