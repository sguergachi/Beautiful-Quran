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

The leather under all of it (`CoverLeather`) is a radial ramp between two
greens only a few eight-bit steps apart, so on Android 13+ it is an AGSL copy
of the gradient with ±1 step of triangular noise (paint dither off, so it does
not stack with Skia's). The shader and paint are built once; a board resize
rewrites two uniforms, and no frame allocates. Below 13 it is the plain
gradient it always was.

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
In every theme, the verse that just owned the lyric line ramps that cover in over the
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
The fibre fringe reads one shared 720 × 720 alpha paper tile, shipped
precomputed (518 KB asset, outside the 32 MiB word cache) and read once per
process: 5 ms on the emulator, where building it on device took 0.6–1.1 s
(debug). The shader reads it at most once per pixel, only within a fibre's
reach of a contour, instead of at each of the eight AA taps (sixteen during a
repeat). Its two uniforms are set on every draw: they hold per-occurrence
state, and one cached word serves every occurrence of it. The traces below
were taken when an unchanged draw skipped those two calls; the difference is
microseconds and was not re-measured. Perfetto traces of the same Al-Baqarah playback
without glosses (where wet words do reach the shader; emulator, debug build,
two pairs) put the fringe at parity: 56–58 µs per wet paper-ink word draw on
vs 58–63 µs off, RenderThread frames 14.6–15.1 vs 13.1–16.0 ms, within noise.
Skia's CPU rasteriser, a proxy for per-pixel shader cost, times it within
noise of the plain paper tooth (wet −1%, drying −7%, repeat +0%); the
fringe's early-outs skip the tooth's per-tap noise. The emulator cannot time
the host GPU, and none of this is a Pixel measurement.

A word's paper-ink field is built on the CPU, on the paper-ink worker. The
Gaussian behind its liquid normals (reflect-padded once per line, skipping
exact-zero spans of empty paper) and an unboxed contour pass cut it from
75 to 49 ms for a 90 px Arabic word, 34 to 21 ms for 64 px English, and 326
to 129 ms at 160 px (warm desktop JVM), with bit-identical output. The draw
that captures a word only rasterizes it; converting the 2× raster to floats
happens on the worker, and a word with no ink is remembered rather than
re-rasterized every frame. Shaped text warms fields ahead of the voice;
word-by-word gloss tiles now do the same (`WarmPaperInkAhead`, the next ten
words after the active one). On the emulator's debug build, where a field
takes 340–380 ms, live gloss words went from never having their field ready
to having it on two thirds of live frames.
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

### 4a. Cached layers for content at rest

The renderer replays everything on screen every frame. An offscreen layer in
that replay, whether a `saveLayer` (`letterFadeIn`, `glyphLayerAlpha`, a
paper cover's punch) or a translucent `graphicsLayer` with the default
compositing, is a whole GPU render pass each time, even when nothing in it
changed. During playback in the word-by-word view that was 39 render passes
a frame: two per word resting at faded ink (unread, or recessed with its
verse), the Arabic and its gloss.

`cachedWhileStill` gives such content an Offscreen (cached) layer while it is
at rest, reaching the fades' own 14 dp bleed past the content without
changing layout, so overhanging marks are not clipped. It re-renders only when
the content changes; on other frames it costs one textured quad. Word tiles
use it while `InkMotion.restsFaded`; a shaped verse whose words are all quiet,
some faded, uses it whole (tiles are not nested inside it). `cachedAlpha` does
the same for translucent chrome (player-bar buttons and reciter label, ayah
marks): identical compositing, cached instead of redrawn. Live words keep
drawing directly, so their halos and washes are never clipped.

Perfetto on the emulator (debug, Al-Baqarah playback): render passes per frame
fell from 39 to 4.3 in the word-by-word view and from 6.9 to 3.1 without
glosses, and the frames drawn in the same 25 s window rose from about 650 to
1,485 (gloss) and from 980 to 1,487. Total GPU memory during gloss playback
was 29.8 MB (19.1 MB purgeable). Not a Pixel measurement.

Adaptive antialiasing (four outer taps, all eight only where they disagree)
was tried and dropped: on a desktop RTX 3080 through Skia's GL backend it saved
nothing, because the eight taps' cost sits at contours, which must keep them,
not in flat stroke interiors.

### 4a′. Sheets composite from a texture while they move

During a page turn the sheet swinging away (`rotationY`) and the sheet rising
beneath it (scale 0.985 → 1) draw their content flat into an offscreen layer,
and only that texture is tilted or scaled (`cachedWhileMoving` in
`MainActivity`). At rest the content draws directly as before.

Drawing through a perspective matrix is a different GPU program for every kind
of draw on the sheet, and every scale the rising sheet passes through
re-rasterises each glyph on it. A release trace of a cold launch (emulator,
2026-11-07) put ~90 ms of shader compiles (14 programs) and ~50 ms of glyph
uploads into the first frame of the first chapter open, a 172 ms UI / 180 ms
render-thread frame; later opens in the same process found the programs warm.
With the cache the worst frame of that open fell to ~31–47 ms over three runs,
compiles to 8–9 (~35 ms), and a turn costs one quad per sheet per frame while
its content is still. The phone keeps compiled programs on disk only until the
app is updated, so every new install's first open paid the full cost.

Only the content is cached: the sheet's own layer keeps the transform and
elevation shadow, and the cast beside it (`paperDropShadow`) spills past the
sheet's edge, which an offscreen layer on the sheet would clip. The remaining
first-open compiles are not perspective draws (path-atlas, mesh and rect
programs on the main framebuffer) and were not chased further.

The texture only helps while nothing on the sheet changes, and in a 2026-11-21
macrobenchmark of Home↔reader↔Settings turns (emulator, release) both cached
sheets re-rendered on almost every frame: 487 recompositions in ~357 frames,
two 1080 × 2400 layer renders a frame, 41% of frames over the 8.3 ms budget.
A composition tracer and `CompositionObserver.onScopeInvalidated` named the
states. Each was a layout position read in composition. The sheet's
`graphicsLayer` transform moves the window and root bounds of everything on it
every frame, so any `onGloballyPositioned` that writes a position is written
every frame of a turn:

- Home keyed its search-dismiss `pointerInput` on the search field's and dials
  pane's root bounds, so the Scaffold content and every visible chapter row
  recomposed per frame. Only the two gate booleans are keys now. The gesture
  reads the bounds when it runs, which always gives the latest value. The
  dials pane's height is computed in its layout lambda, not in composition.
- The reader keyed the ayah-rail lesson on the rail's root centre
  (`ayahRailTipCenterY`) and read it to size the tip, so `ReaderScreen` and
  all its Scaffold slots recomposed every frame the sheet moved. Composition
  now reads a `derivedStateOf` of whether a centre exists. It reads the value
  itself only while the lesson is up. The lesson's wait still restarts on
  every move of the rail, through `snapshotFlow` + `collectLatest`.
- The reader's entrance fade (`readerContentAlpha`, which runs during the
  opening turn) was plain layer alpha. That is a fresh full-page "alpha caused
  saveLayer" pass every frame, inside the turning sheet's texture. It now uses
  `cachedAlpha`, so the page renders once and the fade only modulates it.

The same journey (same emulator, release, two iterations) was measured after
all three fixes. Full-sheet layer renders per iteration fell 384 → 190.
Render-thread work fell from p50 8.4 → 6.3 ms and p90 11.9 → 10.4 ms. Frames
whose UI + render-thread work exceeded 8.3 ms fell 42.7% → 31.9%. With a
composition tracer on, Home's rows recomposed 2,080 → 300 times per
iteration, and `ReaderScreen` 179 → under 5. The ~190 renders left were not
attributed. One of them is a persistent 932 × 485 "alpha caused saveLayer"
present in every frame, even at rest, whose owner was not found. Emulator
only, not a Pixel measurement.
**Rule:** never read a position from `onGloballyPositioned` (or a
`boundsInWindow`/`positionInRoot`) in composition on a sheet. Read it in a
layout/draw lambda, a gesture or a coroutine, or derive the boolean you need.

### 4a″. Opening onto a verse lays it out once

Continue, a bookmark or a search hit opens the reader mid-chapter. The list
is put on that verse before its first layout (`requestScrollToItem` from a
`SideEffect` as the content arrives), and the instant settle
(`ReaderFocusController`, `animate = false`) snaps from there without the
glide's doorstep. Before, the list first laid out the chapter's opening, then
the settle forced two synchronous remeasures (`scrollToItem` to a doorstep,
then to the verse) between frames, in the middle of the page turn. A Pixel 10
log export showed it as a 113 ms frame, 88 ms of it main-thread delay. Emulator
A/B (Continue to 2:150): forced layout outside frames 58–66 → ~1 ms; the
verse's page is composed once, in the tap frame, before the sheet moves.

### 4a‴. The entrance cover under a moving phone

The gilding follows the phone's tilt, so in the hand the sheen changes almost
every frame. Two things made each change expensive, both invisible on an
emulator whose accelerometer never moves (simulate one with `adb emu sensor
set acceleration x:y:z` in a loop):

- `TooledGold` recorded its relief and gold leaf into plain graphics layers.
  A sheen step re-renders the leaf box, which replayed the leaf and, via the
  damaged box around it, the relief — thousands of path points that Skia
  rasterised into frame-sized CPU masks each time (ten ~928×2018 uploads and
  ~24 texture allocations a frame). Relief and leaf are now `Offscreen`
  graphics layers of their own, and the box around them no longer needs one.
- `generatedFieldWeave` used `drawWithCache`, whose lambda is new on every
  recomposition of the caller: each cover phase change (wash ending, du'a,
  opening) or chapter-opening recomposition dropped the cache and
  re-rendered the four full-screen weave paths (~60 ms render-thread frame).
  It is now a modifier node that compares equal across recompositions.

Emulator, release, swaying accelerometer: the closed cover went from 12–15
frames per half second (p50 34 ms) to 30/30 (16.7 ms) nearly throughout,
the opening's first-frame spike from ~70 to ~30 ms, at +3.6 MB GPU memory
(105 → 108 MB). A Pixel 10 log export had shown the same phase at ~58 fps on
its 120 Hz panel with 17–39 ms of render-thread command issue a frame.

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

The pigment field owns a cached `Offscreen` layer, separate from the pulsing
target and lesson copy. Without it, the pulse keeps replaying the full-screen
shader even after the pigment settles. Progress, geometry and tuning changes
still repaint the field; a settled guide only composites its texture. The
transient RGBA cache costs about 9.9 MiB at 1080×2400 or 17.6 MiB at 1440×3200
and disappears with the guide. Cache the field alone: caching the whole guide
would invalidate it on every target pulse.

A 2026-11-03 Skia/GL check on an RTX 3080 compared 120 synchronized draws per
path: direct pigment versus its cached texture took median 0.56 versus 0.073 ms
at 1080×2400, and 0.95 versus 0.087 ms at 1440×3200. Composites were
pixel-identical at progress 0, 0.25, 0.5, 0.75 and 1. These isolate host GPU
cost, not Android frame rates. Device verification was blocked by the T3
device connection repeatedly refusing the shell connection after reconnect.

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
- A turn slides pictures, not type (`MushafTurnSnapshot.kt`). Every Madinah
  page has its own face and each word is one large glyph, so a leaf's glyphs
  fill about half of HWUI's GPU glyph atlas (its size follows the screen's).
  Two leaves on screen at once did not fit: every frame of a turn evicted one
  leaf's glyphs to draw the other's and uploaded them again — ~130 ms of
  `glTexSubImage2D` per swipe on the emulator, spread over every frame of it.
  So every Arabic leaf not carrying live ink is pictured — a run of quick
  flips never rests, and each leaf it reaches must be a picture by the time
  it slides in. The picture never touches the render thread, which is drawing
  the turn: the leaf's next draw records itself a second time into an
  `android.graphics.Picture` (a software canvas, so Compose draws the leaf's
  own layers' content into it), which is rasterised on a background thread
  into a bitmap in the window's colour space (Display P3 on a wide window,
  whose accents are P3) and copied to a hardware bitmap there, off the render
  thread's upload path. A leaf composed static is pictured as soon as its
  entrance fade is done; one that carried live ink first waits out any fade it
  may have begun (`InkEngine` ink fade / recess, the leaf's face fade, plus
  120 ms). A pictured leaf draws its picture whenever it holds a valid one —
  off screen at rest as well as mid-turn, so a turn starting or ending never
  redraws it (a redraw there could not be told from a change: it threw away
  pictures still rasterising, and had to assume the turn's end changed
  nothing) — and the settled leaf keeps setting live type. Any redraw of a
  leaf showing its picture means the leaf changed: it goes live and its
  picture is retired, so a changed leaf is never shown stale.
  Software rasterises a QCF word exactly as the GPU does — screenshot diffs of
  forced pictures against the live leaf, light and Nightfall, sRGB and P3,
  differ by at most 1/255 (2/255 through P3) — but not everything else. The
  title band's hairline weave anti-aliases differently (up to 119/255 apart),
  and Skia's GPU path draws a glyph past 256 px (the basmalah, a wide word like
  سَتُغۡلَبُونَ on page 51) as an outline where software keeps a mask, visibly
  different strokes. Those (`drawnLiveOverPicture`: the band, the basmalah,
  any word whose ink reaches 232 px, `mushafCellDrawnAsPath`) are left out of
  the picture and drawn over it from layers of their own; none of them uses the
  glyph atlas. Each picture holds a leaf-sized bitmap (~11 MB) for as long as
  its leaf stays composed. Forcing `Offscreen`
  compositing on the leaf is still wrong: its layers are re-rendered inside
  the turn, a whole page's uploads in one 90 ms frame.
- Leaves hold two neighbours either side at rest (`MushafHeldNeighbours`),
  returned a pair at a time after an open or a dial landing. Whatever a turn
  can show is therefore composed and measured before it starts. The pager
  still composes leaves of its own accord — the one joining its window halfway
  through a turn (28–48 ms of composition and measure on that frame) and the
  one it prefetches past the window at rest, whose lines `BoxWithConstraints`
  then measured on the next swipe's first frame (40–70 ms). Both stay blank
  paper (`mushafDefersLeaf`) until the pager rests within reach, unless in or
  beside the view or a jump's destination.
- Leaves are set before they are composed (`MushafLeafWarmup.kt`). Composing a
  leaf nobody had opened measured everything on the UI thread — its reflow,
  each word's ink profile and bounds, the line fit, then a paragraph per word:
  ~75 ms on the emulator, paid at the start of nearly every swipe of a quick
  run, which never settles and so never warmed the leaves it reached. The
  warm-up follows the page in view and the turn's target, not only the settled
  page, and sets the target and the three leaves beyond it in the direction of
  travel, then the ones behind (`mushafWarmOrder`), on `Dispatchers.Default`.
  It fills the caches the composition reads — the display reflow
  (`mushafDisplayPage`), line geometry (`mushafLineGeometry`) and the cell
  layouts (`MushafWordLayouts`) — under the keys the composition uses, from
  the setting the last composed leaf published (`MushafLeafSetting`). A miss
  only means the composition measures as it always did.
- "Resting" is its own state (`restPage`), two still frames after the scroll
  ends. `settledPage` moves on the frame a finger lifts, between the drag's
  scroll and the fling's, while the leaf has half the screen to go: every leaf
  keyed on it (live ink, flash, search focus) recomposed mid-fling.
- The pager's page content is built outside `TurningFrameRate`. Written inline
  in its content, each frame-rate vote change handed `HorizontalPager` a new
  block, recomposing every leaf at a turn's start, its end and at finger lift.
  Taps read `pagerState.currentPage` when they land rather than holding it in
  composition, which recomposed the whole pager halfway through each turn.
  `clipLeafSides` is one modifier instance, so a recomposed leaf is not
  re-recorded.
- Chrome (`MushafReadingSheet`) keys the gilt seed on `settledPage`, not
  `currentPage`, so a fling does not regenerate ornaments mid-turn.
- QCF word and verse-mark cells are `MushafWordText` nodes. They are laid out
  as `BasicText(maxLines = 1, softWrap = false, overflow = Visible)` would lay
  them out, and drawn the way foundation's text node draws. The layouts come
  from `MushafWordLayouts`, a process-wide LRU of 2,048 `TextLayoutResult`s
  keyed by glyphs, exact style (the page face, size, narrowing and colour),
  density, direction, font resolver and constraints. In the page-turn
  benchmark, per-word `TextAnnotatedStringNode` measures were ~55 ms of the
  three slowest frames (145 ms together; 283 layouts, ~0.2 ms each). A leaf
  that comes back (the one
  just turned from, or a dial landing on a visited page) now reuses its cells.
  A leaf's first composition still measures each cell once. The forward-only
  page-turn benchmark only visits new pages, so it is unchanged within noise
  (UI + render-thread frames over 8.3 ms: 17.7% before, 20.4% after, two
  iterations each). The revisit gain was not measured.
- The dial HUD's warning ink (the 300 ms pre-pop pulse and the ripening band)
  is a `ColorProducer` read at draw (`MushafDialHudInk`). Read in
  composition, the pulse recomposed the whole label every frame. The trough
  head's folio span still needs its colour in composition, so that one line
  reads it in a scope of its own. A docked label is measured against the slot
  whenever it already fits the 16 px-quantised clamp
  (`mushafDialHudMeasureWidth`), so each clamp step along the wall no longer
  throws away all four lines' paragraphs. A label the clamp actually cuts
  still follows the clamp. In the distant-dial benchmark (emulator, two
  iterations), UI frames over 8.3 ms fell 4.8% → 1.4% and UI p90 fell
  5.5 → 3.2 ms. The landing frame is still dominated by the new leaf's glyph
  uploads (see Future headroom).

The 2026-10-08 emulator comparison used release APKs, AOT compilation, a
settled page 5, and twelve rightward swipes to page 17 with 600 ms pauses.
Against `0c3df7c5`, background profile preparation reduced the 95th-percentile
frame time from 61 to 44 ms and missed deadlines from 41/338 to 33/348 frames.
Prefetch measure averaged 42.8 → 37.0 ms, with its maximum 83.2 → 45.8 ms.
Page 5's text and controls were pixel-identical below the changed running
head. Forced `Offscreen` page caching was rejected: its 95th percentile rose
to 113 ms. These are a same-host 60 Hz comparison, not a physical-device
120 Hz result; cold page measure still exceeds the 8.3 ms frame budget.

2026-11-22, same emulator (60 Hz panel, so frame work is held to 120 Hz's
8.3 ms budget), release benchmark APKs, `mushafPageTurn` letting each leaf
rest before the next swipe, display-queue waits (`dequeueBuffer`,
`postAndWait`) excluded. Before, over ~400 turn frames per run: UI thread one
frame over 8.3 ms (47 ms, the swipe-start measure); render thread 39 over,
95th percentile 10.3 ms, max 22 ms, almost all glyph uploads. After, three
runs, ~1,220 turn frames: UI thread max 2.0 ms; render thread 99th percentile
≤ 6.8 ms, max 8.1 ms; none over 8.3 ms. The work moved to rest: the outer
leaf's composition (~50 ms UI) and two pictures (~100 ms of render-thread
time, asynchronous) once a turn settles. A turn begun within about a second
of opening a chapter, or one of a rapid series begun before the last settled,
has no pictures yet and draws live as before.

2026-11-23, quick flips (`mushafRapidFlip`: eight short swipes, each starting
while the last turn still slides), same emulator and method. Before, five
runs: UI thread 40 of ~1,500 frames over 8.3 ms (max 83 ms — a leaf composed
and measured cold at each swipe's start, 33 ms of it word ink profiles and
bounds); render thread 140 over, 95th percentile 13.1 ms, max 121 ms (two
live leaves thrashing the atlas, and each newly composed leaf's first draw).
After warm-ups and every still leaf pictured: UI thread 10 over (max 13 ms,
the leaf joining the window, now composed from warm caches in 6–10 ms);
render thread 17 over, 95th percentile 5.9 ms — of those, one per run is the
landing leaf going live at rest (50–65 ms of glyph uploads, nothing moving)
and one per run a first-draw shader compile. At reading pace the turn frames
stay as before (UI max 4.6 ms; render thread 4 of ~1,240 over, max 11.4 ms).

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

The results list lays out only what is on screen. Every section header, hit,
and expand/collapse row is its own keyed `LazyColumn` item; the keys come from
`wordSearchRowKey`, which is positional so two hits on one verse cannot
collide. The results used to sit in one item holding a `Column` of every row
of every section. That item's first layout composed and measured all of them
in one frame: 35–43 ms on the emulator for `peace`, however few rows were
visible. The list has no item spacing, so the rows stack exactly as the
Column stacked them. In the cold-search macrobenchmark (emulator, release, two
iterations each), the worst UI frame fell 43 → 24 ms. Frames whose UI +
render-thread work exceeded 8.3 ms fell 18.2% → 8.9%.

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
- Mushaf glyph-atlas uploads (2026-11-21; turns fixed 2026-11-22 by turn
  pictures, see 5b — the uploads now happen at rest, and still on a dial
  landing's first frames). Every Madinah page has
  its own face, so a leaf's first visible frames rasterise and upload its
  glyphs. One page-turn run spent 1.4 s in `glTexSubImage2D`; a dial landing
  frame took 80 ms, ~25 ms of it uploads. One attempt warmed each held
  neighbour while the reader sat still: once a band per frame, the neighbour
  was drawn into a small `Offscreen` `GraphicsLayer`, off screen, with
  `drawContent()` recorded a second time. Traces showed no layer render
  ("flush layers") for it. With it enabled, the page-turn journey twice
  stuck on page 3, and passed with it disabled. It was dropped; the cause was
  not found. A future attempt needs on-device proof that HWUI renders an
  off-screen layer, and must not draw a leaf's content layer into a second
  parent.
- Neighbour leaf composition (turns fixed 2026-11-22: leaves joining the
  window mid-turn stay blank until rest, see 5b). Bringing a leaf into the
  pager's window still composes ~150 word nodes in one frame (16–44 ms of composition on the
  emulator, on top of first-visit measure). Splitting it with pausable
  composition needs a pager prefetch hook that `HorizontalPager` does not
  expose. Drawing a line's static words from one node would cut the node
  count. However, it would rebuild every word node when ink packs arrive at
  playback start, which is an animated moment.
- The surah band's ground is two `generatedFieldWeave` layers, the second a
  mirror of the first. A new chapter opening tessellates both
  (`AAHairlineOp`, 1–7 ms each, ~19 ms of an 80 ms dial-landing frame).
  Recording once and drawing the texture mirrored would halve that, but a
  mirrored texture is not guaranteed to match mirrored rasterisation pixel
  for pixel, so it was left alone.
- Cold start's first frame (391 ms UI on the emulator): ~38 ms composition,
  of which 15 ms is one `StaticLayout`, two ~8 ms first text measures (font
  loads), and ~35 ms recording the cover. The rest of the doFrame is untraced
  first-use cost: class init, JIT and HWUI/EGL setup. All of it draws the
  closed cover's first frame, and the stack already mounts only after two
  cover frames. Nothing was found that could move later without changing
  that frame.

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
