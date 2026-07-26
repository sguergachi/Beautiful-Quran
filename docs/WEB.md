# Web port — plan

Plan for a full web implementation of Beautiful Quran: the same three pure
engines (Focus, Highlight, Ink), the same paper metaphor, and the same
performance bar as the Android app. This document is the working spec for
implementation; it does not change Android behavior.

**Status: implemented (v1) + hosted.** The `web/` package ships the three pure
engines with Vitest parity, WASM SQLite over the committed `quran.db`,
paper-stack UI (Bookmarks / Home / Reader / Settings), cold-start entrance cover (closed
mushaf + isti'adha text fade-in), directional ink wash, focus follow, bookmarks, root
viewer, and a PWA shell. Production build is published to GitHub Pages at
[`/app/`](https://sguergachi.github.io/Beautiful-Quran-/app/)
(as an immutable Pages artifact built by CI from `master`). See
`web/README.md` for run instructions. The sections
below remain the design record and quality bar.

The reader also treats its focused ayah as a keyboard cursor: Up/Down move one
ayah, Page Up/Down move five, Home/End reach chapter bounds, Space toggles
playback, `/` opens in-surah search, and `B` toggles the focused bookmark.
Shortcuts yield to native controls, editable fields, overlays, and modifier
chords.

---

## 1. One-sentence goal

Ship a browser app that feels like the Android reader: one sheet of paper,
words written onto the page in time with the reciter, butter-smooth scroll
and ink — offline-first, no accounts, no backend.

## 2. What already exists (do not reinvent)

| Layer | Android source of truth | Web starting point |
|---|---|---|
| Highlight timing | `domain/HighlightEngine.kt` + `HighlightEngineTest` | `web/src/domain/HighlightEngine.ts` + shared cases |
| Focus / scroll | `ui/reader/focus/FocusEngine.kt` + `ReaderFocusController` | Mirrored under `web/src/ui/reader/focus/`; DOM controller stays platform-specific |
| Ink policy | `ui/reader/InkEngine.kt` + `InkEngineTest` | `web/src/ui/reader/InkEngine.ts`; render policy stays outside the engine |
| Draw primitives | `ui/theme/Fade.kt` (`letterFadeIn`, `shapedWordBloom`, `inkSmootherstep`) | Port math; web uses the same smootherstep mask wash (soft faded edge — never hard peels) |
| Marketing ink demo | `docs/ink-fade.js`, `docs/reveal.js` | **Prototype only** — whole-word opacity, not product-grade directional wash |
| Data | `data/quran.db` (27 MB, committed) | Same DB; load via WASM SQLite |
| Design law | `docs/DESIGN.md` | Identical rules on web |
| Perf law | `docs/PERFORMANCE.md` | Same frame-budget mindset |

The marketing site’s opacity wash is explicitly a fallback that works on
mobile browsers. The product web reader must implement the real
smootherstep directional wash (and the Arabic-only shaped bloom), not ship
the marketing compromise as the highlight.

## 3. Architecture (mirror Android)

```text
tools/build_db.py  ──►  quran.db  (shared asset; optional web export step)
                              │
web/                          ▼
  domain/          HighlightEngine / HighlightClock + domain policy
  data/            WASM SQLite wrapper + typed queries (same schema)
  playback/        Gapless-5 (default) / dual-`<audio>` fallback + Media Session + Cache API LRU
  ui/reader/       InkEngine + focus engine/controller + reader state policy
  ui/theme/        shared fade math; DOM masks remain render adapters
  render/          mode-specific word drawing (gloss / English / Arabic-only)
```

Same composition as Android:

```text
FocusEngine      → which ayah has attention / how to scroll it
HighlightEngine  → which word the reciter is on (+ repeat / high-water)
InkEngine        → how each word’s ink should behave (state, sweep, tuning)
Renderers        → how each mode draws text safely
```

**Invariant:** the three engines stay pure (no DOM, no React). Sync
correctness and focus math live in unit tests that can run in Node without
a browser. Controllers and renderers are the only DOM-touching layers.

## 4. Stack recommendation

| Choice | Decision | Why |
|---|---|---|
| Language | TypeScript (strict) | 1:1 port of Kotlin types; shared test cases |
| Bundler | Vite | Fast, simple, good for PWA + WASM |
| UI | React 19 | Familiar; fine if we keep animation off the React tree (see §7) |
| State | Small hand-rolled stores (or Zustand) | Match “no Hilt / no Nav” — no Redux |
| Data | `sql.js` or `wa-sqlite` (OPFS) over the committed `quran.db` | Same pipeline; no second data format unless forced |
| Audio | `<audio>` + Media Session API + Cache API | Offline-first stream/cache like Media3 |
| Virtualization | Custom or `@tanstack/react-virtual` | Surah lists + ayah lists must virtualize |
| Fonts | Bundle Hafs + EB Garamond + Cormorant from `app/src/main/res/font/` | No Google Fonts dependency for scripture |
| Tests | Vitest for engines; Playwright smoke for reader | Engines = JVM parity; UI = few critical paths |
| Hosting | Static (GitHub Pages / Cloudflare Pages) | No backend; PWA service worker for shell + DB + audio cache |

**Rejected for v1:** Next.js/SSR (no SEO need for a reader PWA), Room-like
ORMs, a custom glyph text engine, QCF V2 page fonts (103 MB split archives —
Android path is still evolving; web stays on Unicode Hafs).

## 5. Engine ports (Phase 0 — foundation)

### 5.1 `HighlightEngine` (pure)

Port exactly:

- `activeIndex` binary search (karaoke hold across gaps)
- `activeWord` / `activeSegment` / `activeInfo`
- `PreparedTimings` with precomputed `maxBefore` / `repeatStart`
- `ActiveInfo` fields: `position`, `startMs`, `endMs`, `isRepeat`, `highWater`, `repeatStart`

Acceptance: every case in `HighlightEngineTest` has a Vitest twin; same
inputs → same outputs.

### 5.2 `FocusEngine` (pure)

Port exactly:

- Adaptive `anchorOffsetPx`, `placement`, `readingLinePx`, `glideDeltaPx`
- `planJump` / `jumpDurationMs` / `homeScrollStep` / `shouldTeleport`
- `readoutPosition` + all geometry types (`TargetGeometry`, `JumpPlan`, …)
- Chapter-top basmalah: `playbackFocusTarget` / `CHAPTER_TOP_FOCUS_AYAH` (0)
  maps to a dedicated basmalah block above ayah 1 (same adaptive anchor /
  placement path as any short verse — not the taller surah header)

Acceptance: every case in `FocusEngineTest` ports. DOM controller is
separate (`web/src/ui/reader/focus/ReaderFocusController.ts` — sole writer to the
reader `scrollTop`, using Motion `animate` + cached content-space geometry +
`homeScrollStep` with Material FastOutSlowIn `[0.4, 0, 0.2, 1]` — the same
curve as Android `FastOutSlowInEasing`; resolves ayah 0 to the
`.basmalah-block` above ayah 1 on preface chapters). Glide frames read only
`scrollTop` (no per-frame `getBoundingClientRect`). Recitation-follow,
selector jumps, return-to-ayah, and tall-verse word keep-in-view all go
through it. Verse advances and tall-verse line follow both use continuous
`homeScrollStep` re-aiming (never an instant `scrollTop` snap) so the next
verse and the next line glide smoothly. Word-band math lives in pure
`wordBandDeltaPx`. Non-active ayahs recess only while a reciting session is
live (`isPlaying || isBuffering` on this surah) via `.scroll[data-reciting]`
and one paper veil per inactive ayah — at rest every ayah is Plain. Play/pause
must not React-reconcile the whole surah.
JS-driven washes / ribbons / entrance use the `motion` package
(`web/src/ui/motion/easing.ts` for shared Android curves). Active ink is the
smootherstep directional mask wash (soft faded edge — product fidelity).

Progressive reader mounting must materialize a selector/search target before
the DOM controller measures it. Far jumps re-window tightly around the target;
the controller then anchors from the live scrollport height and the rendered
ayah's actual text height. The focused block is forced out of
`content-visibility: auto` while measured so the browser cannot substitute its
intrinsic placeholder height. A missing target must never be treated as a
successful focus.

**Follow pause (Android parity):** lyric follow is paused only by a vertical
hand drag past touch-slop or a wheel gesture (`followGesture.ts`) — never by
`scroll` events from FocusEngine or `keepWordInView`. Programmatic verse
advances (including the fade-lead bump and the real media-item join) must
keep the active target anchored without clearing `followEnabled`.

**Word tap → start there:** tapping a word calls `playFromWord` /
`seekToWordAndPlay` with that word's timing `startMs` (no basmalah preface),
matching Android `onWordClick` → `playFromWord`.

**Rail jump → Play starts there:** committing an ayah on the selector rail
(Android `requestedJumpAyah`) updates the session `openAyah` anchor, seeks the
loaded playlist to that ayah when the surah is already parked, and keeps a
pending-jump latch until focus + seek settle. The reader Play button uses
`selectedPlaybackAyah` + `playLoadedFromAyah` when that latch is set so
playback does not resume the chapter-opening clip left by `openSurah` /
`loadSurah`. Continue Listening (`settings.lastSurah` / `lastAyah`) only
updates when a verse is actually recited — not on open, scroll, or rail jump.

### 5.3 `InkEngine` (pure policy)

Port exactly:

- `State` { Plain, Upcoming, Active, Recited }
- `word` / `wordState` / `inRepeatChain` / `sweepMs` / `prefaceState`
- `startRevealed` is **web-only now**: it was a constant `false` on both sides and
  has been deleted from Android's `InkEngine` (its gate collapsed to
  `glinting(state) = state == Active`). The web copy is harmless dead weight —
  fold it away next time `WordUnit.tsx` is touched. Behaviour is identical.
- `Tuning` defaults (upcoming alpha 0.2661, ink/mark/recess fade 400 ms, sweep clamps, feather 1.6, easing CPs)

Acceptance: `InkEngineTest` parity. Optional Ink Lab later (session-only
tuning, same as Android).

### 5.4 Fade math

Port from `Fade.kt`:

- `inkSmootherstep`
- `inkWashAlpha`
- Feather / profile-stop constants used by the wash

Renderers consume these; they do not re-derive curves.

## 6. Data & audio

### 6.1 Database

- Ship the same `quran.db` (or a build step that copies it into `web/public/`).
- First visit: download once → cache in Cache API or OPFS; subsequent loads
  are local.
- Queries mirror `QuranRepository`: surah list, surah content (ayahs + words),
  timings for (reciter, surah), morphology for root viewer.
- At startup, all 114 chapters progressively materialize into the repository
  cache, one chapter per browser idle slice (the last-read chapter first).
  This keeps the main-thread sql.js work from freezing the cover or paper
  peel. Timings remain lazy and hydrate after the reader's first frame.
- **Do not** add data-repair logic in the web app (Android invariant #2).

Optional later optimization (not required for v1): export per-surah JSON
shards for faster first paint. Only if 27 MB WASM open proves too slow on
mid-tier phones — measure first.

### 6.2 Audio

- Same everyayah URLs / reciter slugs as Android `Reciter`.
- Playlist model: one clip per ayah (+ basmalah preface where applicable).
- **Speed / pitch:** at 1×, Gapless-5 (when preferred) joins verses seamlessly.
  At 0.75× / 1.25× / 1.5× the player uses the media-element path with
  `preservesPitch` so the reciter's voice stays natural (Gapless-5 Web Audio
  rate-change always pitch-shifts). Pause mid-ayah resumes from the same
  position on both transports (no `gotoTrack` restart).
- **Word-level start:** `PlayerController.seekToWordAndPlay(ayah, positionMs)`
  seeks within the loaded clip (or rebuilds from that ayah) and plays —
  used by word taps. Mid-ayah starts never prepend the basmalah lead-in.
- Poll `currentTime` on `requestAnimationFrame` while playing; publish
  `ActiveWord` only on change (`distinctUntilChanged` equivalent). Stop the
  ticker on pause.
- **Prefetch / buffering (Android parity):** `AudioPrefetcher` read-aheads the
  next ~8 ayahs into a dedicated Cache API store (`beautiful-quran-audio-v1`)
  with parallel fetches (concurrency 3) and in-memory blob URLs; pinned blobs
  for the playhead window are never LRU-evicted mid-join. Desktop browsers keep
  a standby `<audio>` loaded from a **blob**. iOS instead keeps one persistent
  media element and changes its original HTTPS source at verse joins; this
  follows WebKit's single-stream constraint and avoids its fragile blob-media
  promotion path. `MediaElementTransport` owns those element identities and
  drops stale events from a retired desktop element; `JoinCoordinator` owns
  standby preparation, audible bounds, promotion, and cancellable fades.
  `PlayerController` remains the public policy/state facade and exposes
  `isBuffering` so the play button
  can show the join. A playback-clock watchdog remains as a last-resort guard
  for iOS Safari's silent freeze mode (no `waiting` / `stalled` event).
  EveryAyah MP3s include reciter-dependent encoded quiet at both edges (often
  hundreds of milliseconds, occasionally close to a second). The player
  decodes only the warm current/next clips and detects their audible RMS bounds.
  At a join, the outgoing padded tail receives a short equal-power fade-out;
  the next clip begins at its padded audible start with the complementary
  fade-in. The envelope preserves the reciter's release instead of hard-cutting
  it, without stacking two files' padding into a perceptible pause.
  Failed/unsupported analysis falls back to the ordinary `ended` path.
  The play control shows a spinner while fetching / underrunning. `isPlaying`
  flips on play intent (before canplay) so chrome recess starts on the tap.
  Whole-surah warm runs when the connection is not data-saver / slow-2g. Soft
  caps: ~40 blob URLs in memory, ~200 Cache API entries.
- Media Session metadata + play/pause/seek actions for lock-screen / OS
  controls where the browser allows.

### 6.3 Settings / bookmarks

- `localStorage` (or IndexedDB) for settings and bookmarks — never write into
  `quran.db`.
- **Ribbon tip on hover (web).** Unlike Android’s always-visible faint tip, the
  web idle swallowtail is hidden until the verse is hovered (or the ribbon is
  keyboard-focused). Click the tip to unfurl and bookmark. Saved verses still
  show the full ribbon at rest.
- A saved verse also uses that same ribbon component on Chapters. It is part of
  the scrollable chapter document, running from Search to the final chapter
  entry; a newly added mark replays its unfurl when Chapters returns. Tapping
  it, or swiping right on Chapters, slides the searchable Bookmarks sheet in
  from the left above Home.
- The Bookmarks sheet is a single compact bilingual concordance, capped at
  36 rem and aligned to the Android 560 dp composition. A 44 px ribbon lane
  plus 8 px gap defines the 52 px text spine for chapter headings, verse copy,
  references, confirmations, and disclosure actions. Arabic, English, and
  Western references remain separate elements so bidi order is explicit.
- Each chapter previews five bookmarks. A local green action reveals or
  collapses the rest; search bypasses the preview and displays every match.
  Unmarking swaps the fixed-height reference line for an inline confirmation,
  focuses Keep, and reserves ruby for the existing ribbon component.

## 7. Performance bar (non-negotiable)

Translate `docs/PERFORMANCE.md` into web terms:

1. **Paint-phase ink.** Letter wash progress is read in `requestAnimationFrame`
   / CSS custom properties / Canvas — **not** by React re-rendering every
   frame. React commits only on word/ayah *boundaries* (~2–3×/s).
2. **One ayah wakes.** Active-word subscription is scoped per ayah block
   (selector / memo / store slice). Scrolling must not re-render the whole
   surah. **Play/pause recess** toggles `.scroll[data-reciting]` and fades
   **one paper veil per inactive ayah** (`.ayah-recess-veil`) — not per-word
   cover opacity — so play does not schedule hundreds of transitions.
3. **Virtualized lists.** Sliding mount window (~12 before / 18 after); long
   surahs never fully mount.
4. **Edge fades without offscreen masks.** Prefer solid paper-color gradient
   overlays (same trick as Android) over `mask-image` on the whole list.
5. **Cheap ticker.** `requestAnimationFrame` while playing → derive word →
   emit only on change; **stop on pause** (no idle 250 ms wakeups). Target
   display refresh (≥60 fps) for position → ink.
6. **Instant transport.** Pause releases recess in the same tick (hold only
   while `isPlaying || isBuffering` for ayah joins). Pause works mid-buffer.
   Play intent flips `isPlaying` before `canplay`. Focus glide is deferred one
   frame so the icon + CSS recess paint first.
7. **Content-bearing peel.** Startup progressively materializes all chapter
   text during idle slices; `openSurah` commits cached content and the paper
   slide in one state change. There is no intermediate reader-loading sheet.
   Audio and whole-surah timings hydrate after the first reader frame. A new
   chapter gets fresh focus-controller and rail geometry, initialized at its
   target ayah, so stale dial state cannot move during the peel.
   Long surahs first mount a tight ayah window with scroll padding. Expansion
   waits until the 360 ms peel is complete, then mounts only 12 ayahs per idle
   slice so spacer replacement cannot move the rail during navigation;
   parked reader sheets use `content-visibility: hidden`. Same-surah reopen
   peels without remount. Sheet glide is ~360ms so the transition is visible.
8. **Measure on mid-tier mobile Chrome/Safari**, not only desktop. Target:
   scroll and ink at display refresh with no layout thrash during wash.

If a technique forces React to re-render per frame, it is wrong — fix the
technique.

## 8. Rendering modes

| Mode | Approach | Hard constraint |
|---|---|---|
| Arabic + gloss | Per-word spans; Arabic glyphs stay full opaque ink with paper-cover bloom (`WordUnit` / `ink-paper-cover`); gloss/translit use `secondaryAlpha` | Timed word-by-word ink; **never** CSS opacity / glyph alpha on Arabic (overlapping marks look dirty) |
| English lyric | Directional `letterFadeIn` via `runLetterWash` + Upcoming floor | Same timings as Arabic; soft faded edge required; masked layers retain inline bleed for serif overhangs |
| Gloss / translit under Arabic | Whole-word opacity via `secondaryAlpha` (tracks sweep; never letter-reveal) | Same as Android `WordHighlight.secondaryAlpha` |
| Arabic-only (Hafs) | Per-word full-ink glyphs + paper-cover bloom (`HafsWord`) | No per-glyph style runs that break joining; no neighbour bleed; upcoming = opaque paper cover, not alpha |

Product rule from `DESIGN.md` / `CONNECTED_ARABIC_RENDERING.md`: a renderer
may change *mechanism* to protect shaping, but must not degrade to static
color, whole-ayah highlight, or non-animated state change.

**v1 priority:** gloss + English first (prove engines + sync + paper UI).
Arabic script (gloss `WordUnit` and Arabic-only `HafsWord`) keeps glyphs at
full opaque ink and dims via a paper cover (`paperCoverMaskImage`) — same wash
curve as English, without glyph alpha (semi-transparent Hafs marks look dirty
at stroke intersections). A single shaped paragraph with Range/Canvas clipping
remains a later polish if joining artifacts appear.

Repeat orange wash: second overlay on the same wash curve; dissolve when
the repeat chain releases (`InkEngine` + `REPEAT_HIGHLIGHTING.md`).

Fresh-ink glint (Nightfall + Royal Green): a third overlay twin in white gold
(`--glint`, defined on `[data-theme='dark']` and `[data-theme='royal_green']`)
that washes in alongside a genuinely new word's first-pass ink — same duration,
easing, and directional mask — then dissolves to plain recited ink over
`glintFadeMs` (1 s) once the voice moves on. Gate: `InkEngine.glinting` (word
half) × `glintEnabled()` in `render/inkWash.ts` (theme half). Repeat events
always retrigger the glimmer, including when the word starts already revealed;
an ordinary backward-seek re-entry does not. A transparent duplicate glyph
supplies the tight two-radius `text-shadow` halo while the crisp overlay keeps
the directional mask. The halo has no background, box shadow, or filter, and
overflow remains visible so its edge cannot expose the word box. Same policy
and color as Android (`QuranAccents.glintInk`); see [GLIMMER.md](GLIMMER.md) for
the complete layer and visual-verification contract.

## 9. UI surface (paper metaphor)

Three sheets, hand-rolled paper stack (no router chrome):

1. **Home** — surah list, Quran-wide word search (sectioned by surah with
   truncated expand-in-place lists), `surah:ayah` references, continue-
   listening, floating playback control while a verse is loaded (chapter ·
   ayah label, transport, quiet Close that stops the session — Android
   parity). The control spans the full chapter sheet width while its ink and
   transport remain centred. Opening a word hit flashes that Arabic (and English gloss) word
   twice with the orange repeat wash (directional wash in, dissolve out).
   Word search keeps the query in local home state (no global store fan-out),
   builds its slim in-memory index on demand (never during chapter browsing),
   and scans cooperatively with cancellation so typing stays responsive.
   Its fixed masthead, 30 px gilded Settings rosette, rounded search field,
   full-width continue wash, and compact 28 / 26 / 4 px chapter grid mirror
   the Android Home sheet while the whole composition remains centred at a
   640 px maximum measure.
   Developer settings expose the same two persisted bookmark treatments as
   Android—a 96 px top-bound ribbon and the *Saved passages* line—while both
   open the same left-hand Bookmarks sheet. The inline line follows the
   Continue row's weight, spacing, and 28 / 26 / 4 px rule, and stays out of
   active search results; changing this Home treatment never changes Reader
   ribbons.
2. **Reader** — header + ayahs + icon player bar; mushaf page breaks
   (whisper-gold hairline with Western + Arabic-Indic page numbers, Android
   `PageBreak` parity) between ayahs that start a new Madinah page; once the
   opening surah header scrolls off, a compact ornate title (Arabic + chapter ·
   transliteration) reappears in the top bar; ayah selector rail
   (hover-magnified dashes with gold focal tick under the cursor; spring
   expand from the minimized stack into the dial wheel — Android parity;
   centered on desktop, Android-
   style edge-flush on mobile ≤640px so bars grow from the screen edge;
   drag uses tick-spaced wheel scrub so the visible label is the commit target);
   return-to-ayah roundel (gilt corolla, qalam arrow painted toward the
   active verse); bookmark ribbon.
3. **Settings** — Android paper-settings parity: reciter as ink-disc choice
   rows, reading mode / ayah side as ink-brush segmented rows, A—track—A text
   size, display toggles with calligraphic checks, theme choice list with
   gilt swatches, colophon at the foot (triple-tap unlocks developer tools).
   Speed and repeat live on the reader player bar, not here. Opens as a third
   sheet **over** the reader when a surah is open (`stackLayer` 0→1→2). On
   phones sheets are full-bleed — back chevrons (and Escape) peel the stack;
   there is no left/right peek gutter.
   Wide viewports (≥900px) recenter a column with a thin equal L/R peek of
   the under-sheet; tap a peek (or Escape) to peel back. The top sheet uses
   one peek step of inset so Settings covers the reader (the ayah rail does
   not hang in the gutter).

Hard rules from `DESIGN.md` apply unchanged: no dialogs, cards, ripples,
elevation, borders; hierarchy via spacing / size / ink alpha; ink-bleed
overlays for root viewer (and later system prompts). The root viewer bleed
is hosted **inside the reader sheet** so the wash soaks that paper only —
not a full-viewport layer over the under-paper or peeking deck. It uses the
same contrasting workbench as Android (`contrastingOverlayColorScheme`):
**Royal Green** over Paper / Nightfall, and **Nightfall** when the reader is
already Royal Green. Dismiss chrome is an icon (×), not a text label. Enter
blooms with an expanding circle clip; exit punches a hole open from the same
origin (Android `InkRevealOverlay` punchHole), keeping the overlay mounted
until the hole finishes.

Ink wash uses the smootherstep mask from `fade.washMaskImage` (not a blunt
3-stop wipe). Repeat orange is a second overlay that washes in and dissolves
over `repeatFadeOutMs`.

Motion: fade + slide only (≤ 420 ms), except chrome recede (520 ms) and
verse ink recess (400 ms), plus far ayah jumps (up to 1000 ms via
`FocusEngine.planJump`). The root-viewer ink bleed enter/exit pair is also
420 ms. Chrome recess starts on play *intent* (optimistic `isPlaying`),
not after the audio element finishes buffering — otherwise the fade lags
a beat behind the tap while the clip warms.

Themes: Paper / Nightfall / Royal green — same tokens.

**Fonts:** bundle KFGQPC Hafs, EB Garamond, Cormorant Garamond. Nothing
sans.

## 10. Scope by phase

### Phase 0 — Engines + scaffold
- Create `web/` Vite + TS + Vitest.
- Port Highlight / Focus / Ink + fade math with full test parity.
- Copy/link `quran.db` + fonts into `web/public`.
- Doc: this file + short `web/README.md`.

### Phase 1 — Data + silent reader
- WASM SQLite open + repository queries.
- Home list + open surah → render Arabic+gloss (no audio yet).
- Paper theme tokens, typography, edge fades.
- Virtualized ayah list.

### Phase 2 — Playback + highlight + ink
- Audio playlist, basmalah preface, speed, ayah/range repeat.
- rAF ticker → `PreparedTimings` → `ActiveWord` (stopped on pause).
- CSS `[data-reciting]` + per-ayah recess veil so play/pause stays ≥60 fps.
  Fade-lead prepares the *next* verse (Upcoming + veil lift) via
  `readerAyahInkPolicyActive` while karaoke ownership stays on
  `readerInkAyah` — otherwise the first word's wash runs under the lifting
  veil and reads as "no ink animation".
- `InkEngine` → paper-cover directional wash (Arabic) / letterFadeIn mask (English).
- Repeat orange wash for repeat-aware reciters.
- Chrome recede while playing (top bar + ayah rail → fully hidden like
  Android `topBarAlpha`; player peripherals whisper at 0.08 like `chromeAlpha`).

### Phase 3 — Focus + paper stack polish
- ✅ `ReaderFocusController` (doorstep + `homeScrollStep`, recitation-follow,
  centered hover-magnify ayah selector rail with spring expand/collapse and
  tick-spaced wheel scrub (edge-flush on mobile), return-to-ayah, tall-verse
  word keep-in-view).
- ✅ Recess/dim only while playing; full Plain ink when paused or idle.
- Sheet transitions + page-turn feel (audio optional).
- Bookmarks ribbon; settings persistence; continue listening.

### Phase 4 — Arabic-only + depth features
- ✅ Arabic paper-cover bloom for gloss `WordUnit` and Arabic-only `HafsWord`
  (`ink-paper-cover` / `paperCoverMaskImage` — glyphs stay full opaque ink;
  never CSS opacity on overlapping Hafs marks).
- Root Word Viewer (ink bleed) + corpus-backed morphology, lemma-frequency
  analyses, and per-chapter concordance lists truncated to five references
  until expanded.
- PWA installability; offline shell + DB + audio cache.
- Optional Ink Lab (developer unlock).

### Phase 5 — Parity polish / cut line
- ✅ Search within surah (reader top-bar icons + match navigation), floating
  home playback control, Media Session.
- ✅ Prefetch next ayahs into Cache API; verse joins default to Gapless-5
  (HTML5 + Web Audio hybrid). Developer mode can fall back to dual-`<audio>`
  standby (iOS single-element HTTPS path on that legacy engine).
- ✅ Collapsed surah title in the reader top bar once the opening header
  scrolls off (Android `OrnateSurahTitle` parity — Arabic + chapter ·
  transliteration, flanked by gilded flourishes).
- ✅ Mushaf page breaks (Android `PageBreak` — gold hairline + Western /
  Arabic-Indic page numbers from `ayahs.page`).
- Visual QA against Android screenshots (`docs/ss*.png`).
- CI: Vitest on every PR; optional Playwright on `master`.

**Explicitly out of v1 web (unless pulled in later):**
- Timings Lab / timing patch export
- QCF V2 mushaf fonts
- Exact Media3 preload configuration
- Sharing / accounts / analytics (never)

## 11. Repo layout

```text
web/
  package.json
  vite.config.ts
  index.html
  public/
    quran.db          # copy or CI-synced from app assets
    fonts/            # hafs, eb-garamond, cormorant
  src/
    domain/
      HighlightEngine.ts
      HighlightClock.ts
      Basmalah.ts
      WordSearch.ts
    data/
      database.ts
      repository.ts
      models.ts
    playback/
      player.ts
      cache.ts
    ui/
      App.tsx           # paper stack + entrance ceremony
      entrance/         # closed mushaf cover (arrive → du'a text fade → open)
      home/
      reader/
        InkEngine.ts
        ReaderHighlightState.ts
        WordHighlight.ts
        focus/
          FocusEngine.ts
          ReaderFocusController.ts
      settings/
      theme/
    render/
      WordUnit.tsx
      EnglishWordUnit.tsx
      HafsAyah.tsx      # Phase 4
    main.tsx
  # Tests live beside the matching domain / reader / theme policy.
```

Android stays the product of record for mobile. Web is a sibling package in
the same monorepo; shared *truth* is `quran.db` + the engine algorithms +
the design docs — not shared UI code.

## 12. CI / delivery

- `.github/workflows/web.yml`: `npm ci` + Vitest + `npm run build` on every
  push/PR that touches `web/`.
- On `master` only: after tests pass, stage the marketing site from `docs/`,
  run `npm run build:pages` into `_site/app`, and deploy `_site` through the
  GitHub Pages artifact actions. Generated reader files never enter Git
  history and concurrent master builds are serialized by workflow concurrency.
- Android `assembleRelease` stays in `build.yml` — web failures do not block it.

## 13. Quality gates (definition of done)

A phase is done only when:

1. Engine tests match Android for that layer.
2. Design rules hold (paper metaphor — review against `DESIGN.md`).
3. Highlight is timed, animated, word-by-word (no static fallback shipped).
4. Scroll + ink stay smooth on a mid-tier phone browser (manual or
   Playwright trace).
5. Offline: after first load, text+timings work without network; audio
   works from cache when previously heard.
6. Relevant docs updated (`docs/WEB.md` status, `AGENTS.md` repo map).

## 14. Risks & mitigations

| Risk | Mitigation |
|---|---|
| 27 MB DB first load | OPFS/Cache; progress UI as ink on paper; measure; shard only if needed |
| Arabic-only shaping + wash | Defer to Phase 4; use Range/Canvas path bloom; never SpanStyle-style splits |
| React per-frame jank | Boundary-only store updates; rAF/CSS for wash progress |
| Safari audio / autoplay | User gesture to start; Media Session; clear play affordance |
| Font licensing / size | Bundle existing app fonts; subset Latin if needed; Hafs full |
| Drift from Android engines | Shared test vectors; when Android engine changes, update web twin in same PR when possible |
| Marketing opacity wash confusion | Product code lives under `web/src/render`; leave `docs/ink-fade.js` as site-only |

## 15. Decision checklist (approve before coding)

1. **Sibling `web/` package** with Vite + React + Vitest — yes/no?
2. **Same `quran.db` via WASM SQLite** (not a new JSON pipeline) — yes/no?
3. **Phase order** (engines → silent reader → sync+ink → focus → Arabic-only) — yes/no?
4. **Arabic-only in Phase 4** (gloss+English prove the product first) — yes/no?
5. **QCF V2 / Timings Lab out of v1** — yes/no?

If any answer is no, amend this doc before Phase 0 starts.

## 16. First implementation slice (after approval)

When this plan is approved, the first PR should be **Phase 0 only**:

- Scaffold `web/`
- Port the three engines + fade math
- Port the three JVM test suites to Vitest
- Wire `npm test` in CI
- No UI chrome yet beyond a stub page that imports the engines

That keeps the correctness core reviewable before any paper UI lands.
