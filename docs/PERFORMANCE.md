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

The paper stack follows the same rule: its live page position is read inside
each sheet's `graphicsLayer` and shadow draw callback. Only threshold-derived
booleans return to composition, so dragging or settling a page does not wake the
root three-sheet composition every frame.

The no-gloss Arabic path (`ResponsiveHafsAyah`) cannot put `letterFadeIn` on
the whole ayah. It keeps the shaped ayah as static full-ink spans and applies
`shapedWordBloom` in the draw phase: upcoming words get a full-strength paper
cover from the first Upcoming frame — and the same cover is used while the ayah
is recessed (`dimmed`), so landing on the next verse does not change unread ink.
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

### 3b. Playlist preload + cache warm

ExoPlayer's `PreloadConfiguration` buffers ~5 s of the *next* ayah in the
playlist (Media3 1.10), cutting inter-ayah join latency. `AudioPrefetcher`
still warms a few ayahs beyond that on any network, and the whole surah on
unmetered Wi‑Fi, writing into the same 1 GB LRU cache the player reads.

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
  does not `Typeface.createFromAsset` on the UI thread. Line geometry is keyed
  by page, display row, size, and measure. The page's sixteen-row reflow is a
  linear token-width pass remembered by page + typeface; playback ticks and
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
  nodes retain the directional `shapedWordBloom`, while the leaf itself owns an
  offscreen layer so a fling transforms a recorded page. The settled page runs
  live ink; during an automatic turn the voice's page may join it so a short
  opening word does not restart its wash when the leaf lands. Other neighbours
  keep static ink. On the voice's page, only the active ayah owns word motions:
  completed ayahs use a static full-ink pack and later ayahs share a motionless
  recess pack. One page-level accessibility node exposes the canonical
  Arabic instead of hundreds of private-use glyphs.
- Chrome (`MushafReadingSheet`) keys the gilt seed on `settledPage`, not
  `currentPage`, so a fling does not regenerate ornaments mid-turn.

### 6. R8 release builds are what ships

CI publishes `assembleRelease`: R8-minified (full mode), resource-shrunk,
**non-debuggable**. Debug Compose builds carry debug render checks, no
inlining, and JIT-cold code — they can feel 2–3× slower. If a build "feels
janky", first ask: is this the release APK?

### 7. Data access off the UI thread, once

- The prepackaged SQLite DB is copied out of assets once, then opened
  read-only; all queries run on `Dispatchers.IO` through suspend functions.
- A surah loads with exactly three queries (ayahs, words, timings) — no
  per-ayah round trips. Timings for one reciter+surah arrive as one query of
  compact JSON rows.
- Surah and reciter lists are cached in memory after first read.

### 7b. The word-search index is a memory budget, not just a cache

Quran-wide word search builds one entry per **word row: 77,429 of them**, held
for the life of the process from the first ≥2-character cover-sheet query. At
that size, per-entry strings are the whole cost, and `Cursor.getString()` hands
back a *fresh* `String` per row — so binding an ayah's text onto each of its
words duplicated it ~12×.

Anything ayah-wide therefore lives behind one shared `WordSearchAyahContext` per
ayah (6,236 instances, ~2.3 M characters) instead of per word (~31 M
characters). Only genuinely per-word fields — the surface form, its normalized
and lowercased search keys, the gloss — are stored inline.

**Rule for anything added to this index:** if the value is the same for every
word of a verse, it belongs in the shared context. A single extra ayah-wide
`String` field on the entry costs ~12× what it looks like it costs.

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
user journeys on stable hardware. See [Profiling](PROFILING.md).

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
  lists and gradient construction.
- The number and retained memory of per-word graphics layers in gloss mode.
- Long-ayah text shaping / prefetch on first exposure.
- Replace the conservative seed Baseline/Startup rules with output captured on
  a stable physical Android 17 device, then retain them only if Macrobenchmark
  confirms an improvement.
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
