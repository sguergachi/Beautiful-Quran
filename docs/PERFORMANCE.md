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
Block alpha stays at 1 during recitation in every mode (word-level Upcoming ink
for gloss/English; paper cover for Arabic-only). First-pass pulls the cover back
on the ink-wash curve; repeat SrcIn-tints the same shaped glyphs orange then
DstIn-washes. Progress is read only at draw time, so the sweep never reshapes
the ayah or paints onto neighbouring words.

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
reader leaves the screen. Play/pause is part of the `flatMapLatest` identity,
so resuming cancels any paused sleep and samples immediately; a word tap cannot
start audio up to 250 ms before its ink restart. Repeat / high-water tables are
built once when timings load (`HighlightEngine.PreparedTimings`); the lookup
itself is a binary search + O(1) table read that allocates nothing.

The *poll* is not allocation-free, though: every tick builds an
`ActiveInfo` and an `ActiveWord`, and `distinctUntilChanged` discards them
only afterwards — two short-lived objects per tick per polling flow, which
the young generation absorbs. Only the **emission** is boundary-rate
(~2–3×/second); the sampling and its garbage are 30 Hz. Do not cite this
loop as allocation-free.

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

### 4b. Layout reads confined to derived state and coroutines

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
