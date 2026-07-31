# Architecture

How Beautiful Quran is put together, and why each piece is the way it is.

## The one-sentence version

A **prepackaged SQLite database** (built offline by `tools/build_db.py` from
open Quran datasets) feeds a **single-module Compose app** whose signature
feature — words lighting up in time with the reciter — is driven by a
**pure-function sync engine** polling a **Media3 player** 30 times a second.

```
tools/build_db.py  (build time, runs in CI)
   quran-json (npm) ─┐
   WBW gloss (npm)  ─┼─► validate, align, pack ─► data/quran.db
   quran-align zip  ─┤
   QAC morphology   ─┘   (roots / lemma / POS — see ROOT_VIEWER.md)
                                                        │
app (runtime)                                           ▼
   QuranDatabase ── copies asset once, opens read-only SQLite
   QuranRepository ── typed queries (surahs, ayahs+words, timings, morphology)
   SettingsRepository ── SharedPreferences behind a StateFlow
   PlayerController ─┬─ MediaController → PlaybackService (ExoPlayer + cache)
                     └─ PlayerUiState StateFlow (what's playing, where)
   HighlightEngine ── pure: (segments, positionMs) → active word position
   OutputLatency ── pure route presets; AudioOutputLatency watches BT/speaker
   ViewModels ── HomeViewModel, ReaderViewModel, SettingsViewModel, …
   UI ── four sheets (Bookmarks, Home, Reader, Settings) + ink-bleed overlays
         (notification prompt, Root Word Viewer, Timings Lab)
```

## Principles

1. **Offline-first, no backend.** All text, translations, and word timings ship
   inside the APK. Only recitation audio streams (and is cached, 1 GB LRU).
   No accounts, no analytics, no API keys — the app works in airplane mode
   once audio is cached.
2. **The data pipeline is a build step, not app code.** Everything fragile
   about data (three sources with different word segmentations, a corrupt file
   in an upstream release, basmalah offsets) is resolved *once*, at build
   time, with validation and logged diagnostics. The app only ever sees a
   clean, consistent database.
3. **Purity where correctness matters.** The sync engine (`HighlightEngine`)
   is a pure function over immutable data — trivially unit-testable, no
   Android dependencies.
4. **Small over clever.** No Hilt (a hand-rolled ViewModel factory over
   Application-scoped singletons), no Room (a 100-line raw-SQLite wrapper),
   no navigation library at all (the four sheets are a hand-rolled paper
   stack in `MainActivity`). Every dependency earns its place.

## The data pipeline (`tools/build_db.py`)

Sources (all fetched over HTTPS, cached in `tools/.cache/`):

| Source | Provides | Why this one |
|---|---|---|
| `quran-json` (npm) | Uthmani Unicode text, Saheeh International translation, surah metadata | Tanzil-derived, verse-keyed, no auth |
| `@kmaslesa/holy-quran-word-by-word-full-data` (npm) | Per-word English gloss + transliteration (Quran.com data) | Only per-word English dataset on an open registry |
| `cpfair/quran-align` release zip | Word-level timestamps per reciter, CC-BY 4.0 | The canonical open word-alignment dataset, matched to everyayah.com audio |
| quran.com `qdc` audio API | **Repeat-aware** word timestamps for reciters in `QDC_REPEAT_RECITERS` | quran-align is one-pass and cannot encode a repeated phrase; quran.com's segments backtrack when the reciter repeats. Accepted qdc payloads are SHA-256 locked so upstream drift cannot silently change the corpus. See [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md) |
| everyayah MP3 ranges | Leading-silence and duration measurements in `tools/audio_onsets/` | Some individual ayah files begin with silence. The offline scanner holds the first wash until sustained voice without moving valid later word boundaries, and records each file's length as the ceiling no timing row may cross. |
| Quranic Arabic Corpus (QAC) v0.4 | Per-word root, lemma, POS, morphology; root concordance | Standard open Quranic morphology / root dictionary. Powers the [Root Word Viewer](ROOT_VIEWER.md) |

The **canonical word segmentation** is the space-split of the Uthmani text.
The other two sources are mapped onto it by position:

- The WBW gloss disagrees on word count for exactly **10 of 6,236 ayahs**
  (off by one); those are clamped by index and logged.
- Timing files use 0-based word indices; the pipeline converts to 1-based
  positions, drops segments that point at basmalah words prefixed to
  first-ayah audio (`adjust_segments`), clamps overshoot, and **fails the
  build** if a reciter's coverage drops below 6,000 ayahs.
- A reciter with no usable timing source ships with `has_timings = 0`. The
  truncated Sudais quran-align file is harmless because his locked qdc source
  supplies the row topology instead.
- **TimingEngine V1.5** gives each timing source one job: qdc supplies repeat
  topology, quran-align supplies the streamed-file clock and monotonic fallback,
  and measured audio supplies physical limits. `rebase_qdc_clock` translates
  repeat-aware rows using matching quran-align boundaries and abstains when
  those witnesses scatter or cross the recording. Full detail:
  [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md).
- `clean_qdc_artifacts` produces one topology candidate from local structural
  evidence. Generated CTC rows then change only differing spans; verified
  same-word repeats are restored per position and multi-word re-says cannot be
  flattened. Ambiguities that topology cannot decide live as small typed
  operations in `tools/timing_corrections/`, never as replacement ayah rows.
- `tools/detect_audio_onsets.py` scans up to the opening eight seconds of the
  exact everyayah files the app streams. It retries a larger byte range when
  the first range ends during silence, rather than treating ffmpeg's
  end-of-input flush as voice. Silence must sustain for 80 ms to register;
  voice onsets of at least 250 ms are committed as compact evidence under
  `tools/audio_onsets/`; `build_db.py` clamps the first wash to that onset after
  the complete row is on the MP3 clock, leaving words 2 onward unchanged. If
  word 2 itself predates the voice, a repeat-aware row is projected into its
  quran-align file-clock window; only a monotonic fallback may shift as a whole.
  The onset is recorded separately as immutable MP3 metadata.
  The repository reapplies that clock to on-device Timing Lab edits, so a saved zero-based
  row cannot restart the wash during encoded silence. `ffmpeg` is needed only
  to regenerate the evidence, never to build or run the app.
- The finalizer ships a row only when it covers every canonical word, has
  unique increasing starts and positive non-overlapping spans, begins on/after
  measured voice, and fits inside its exact MP3. It fills holes from a
  same-clock reference only when the splice preserves the source's exact repeat
  signature; otherwise it uses the complete monotonic quran-align row. If
  neither is physically valid, the row is withheld and the reader honestly
  highlights the whole ayah.

> **Changing the DB content requires a version bump.** `quran.db` is a committed
> asset (regenerated by `build_db.py`), and at runtime it is extracted from assets
> to internal storage keyed on `QuranDatabase.DB_FILE_NAME` (`quran-vN.db`). Any
> content change (new reciter, new timings) must bump that suffix or existing
> installs keep the stale cached database.

Output schema (all read-only at runtime):

```sql
surahs   (id, name_arabic, name_transliteration, name_translation, revelation_place, ayah_count)
ayahs    (surah_id, ayah_number, text_uthmani, translation_en)
words    (surah_id, ayah_number, position, arabic, translation_en, transliteration)
reciters (id, slug, name, style, has_timings)
timings  (reciter_id, surah_id, ayah_number, segments)   -- segments = "[[pos,startMs,endMs],…]"
word_morphology (surah_id, ayah_number, position, root, lemma, pos, features)
roots (root, occurrence_count)
root_occurrences (root, surah_id, ayah_number, position)
```

Morphology tables power the [Root Word Viewer](ROOT_VIEWER.md) (Quranic Arabic
Corpus). Concordance counts and jump lists come from `roots` /
`root_occurrences`.

Timing segments are stored as one compact JSON array per (reciter, ayah)
rather than one row per word: ~37 k rows instead of ~465 k, smaller file,
and the app always loads a whole ayah's segments at once anyway.

## The sync engine

```
ExoPlayer ──(currentPosition, polled every 33 ms while playing)──►
HighlightEngine.activeWord(segments, position)  [binary search]  ──►
StateFlow<ActiveWord?>  (distinctUntilChanged: emits once per word)  ──►
per-item derivedStateOf in the reader list  ──►  one ayah recomposes
```

- `PlayerController` wraps a `MediaController` connected to `PlaybackService`
  (a `MediaLibraryService`), mirroring player callbacks into a
  `PlayerUiState` StateFlow. Playlists are one `MediaItem` per ayah with
  `mediaId = "surah:ayah:reciterId"`, so `currentMediaItemIndex` ↔ ayah is a
  trivial mapping and ayah-repeat is just `REPEAT_MODE_ONE`. Surahs that open
  with a basmalah preface prepend a dedicated everyayah basmalah clip
  (`mediaId` ayah `0`, URI = Al-Fatihah 1:1 / `…/001001.mp3`) so chapter-start
  playback hears the basmalah before ayah 1; playlist indices shift by one when
  that lead-in is present. Using 1:1 (rather than the optional `bismillah.mp3`)
  keeps the clip inside the guaranteed ayah-per-file layout and reuses its
  word timings for the header calligraphy wash. If it still fails to load,
  `PlaybackService` skips into ayah 1 instead of surfacing a playback error.
  Range-repeat (loop ayah *m*..*n*) is enforced in the
  controller's listener: whenever the player crosses past the range's last item
  (auto-advance, "next", or end of playlist) it seeks back to the range's first
  item.
- `ReaderViewModel.activeWord` runs the polling loop only while this surah is
  audible (`flatMapLatest` on the playing state) and publishes only *changes*
  (word boundaries), so downstream recomposition happens ~2–3×/sec during
  recitation, not 30×.
- `HighlightEngine` holds a word lit through inter-word gaps (karaoke
  behavior), lights nothing before the first word (covers the basmalah lead
  on first-ayah audio) and nothing after the last word ends.
- For repeat-aware reciters, `HighlightEngine.activeInfo` also reports
  `isRepeat` (the active word points back at an earlier position) and
  `highWater` (furthest word reached), which drive the orange second fade and
  keep already-recited words lit during a repeat. See
  [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md).
- Word-level accuracy of the source data is ±73 ms on average — inside the
  ~150 ms window that reads as "in sync" to a human.

See [HIGHLIGHT_ENGINE.md](HIGHLIGHT_ENGINE.md) for the engine's model, the
binary search, and the repeat / high-water logic in full. See
[INK_ENGINE.md](INK_ENGINE.md) for `InkEngine`, the thin visual-policy layer
that sits between highlight timing and mode-specific text rendering (word ink
states, repeat wash membership, sweep clamps, and all the feel tuning), and
for the developer-mode Ink Lab that tunes it live.

## The focus engine

Vertical position in the reader — which verse the reader is looking at, and
how to scroll a chosen verse into view — is owned by one component so the
list is never pulled in two directions at once.

```
FocusEngine   ── pure: (viewport, verse geometry) → anchor / placement / glide delta
ReaderFocusController ── holds the LazyListState; the sole writer to it
```

- `ui/reader/focus/FocusEngine` is pure (no Android, JVM-unit-tested like
  `HighlightEngine`). It computes the **adaptive anchor** — a verse that fits
  rests fully in view with a little breathing room; a verse taller than the
  screen has its top pinned so its opening line shows — plus a verse's
  `placement` (above / below / in focus) and the exact pixel `glideDelta` to
  bring it to its anchor. The chapter-opening **basmalah** is a first-class
  focus target: `playbackFocusTarget` resolves the lead-in to
  `CHAPTER_TOP_FOCUS_AYAH` (playlist ayah 0). That key maps to a **dedicated
  LazyColumn item** (the calligraphy block above ayah 1), so lyric-follow,
  placement, and return-to-verse use the same path as any short verse.
- `ui/reader/focus/ReaderFocusController` is the Compose glue and the **single
  writer** to the `LazyListState`. Every programmatic scroll — a selector
  jump, recitation-follow (including the basmalah lead-in), the return-to-verse
  control, the initial continue-listening settle — goes through its one
  `focus()` suspend, which waits for a real viewport before measuring,
  teleports to a doorstep when the target is far off-screen, then glides the
  last stretch by exact pixels. It also exposes the shared read-out
  (`focusedAyah` / `focusedPosition`) the rail and the return control both
  consume.
- Hand-initiated jumps (selector, search, return-to-verse) pass `preRoll` to
  `focus()`. The pure `FocusEngine.planJump` owns the trajectory shape
  (near = full path; far = doorstep + distance-scaled residual up to ~48
  items / 1s). The controller then runs **one continuous home-scroll**
  (`homeScrollStep`): each frame re-aims at the live remaining distance so
  the decelerating glide stays smooth all the way onto the exact verse —
  no rush-then-settle handoff, no chunky end stutter. Recitation-follow
  leaves `preRoll` off but still uses that same continuous home-scroll
  (700 ms soft glide) — never a one-shot `scrollToItem` + animate — so the
  next verse across a mushaf page divider (often not yet laid out) glides
  instead of jumping. Concurrent `focus()` calls are serialized on a mutex
  so a sibling effect cannot cancel the slide mid-flight.
- Opening a bookmark (or another explicit verse target) inside the same paused
  playlist is a manual jump: the held verse yields focus, the playlist seeks
  to the chosen verse without playing, and the transport can then resume from
  that selection.
- Display settings that reflow ayah heights (reading mode, word gloss,
  transliteration, translation, font scale) recover the pinned verse after the
  LazyColumn remasures, so the reading line stays on the ayah the reader was
  looking at instead of drifting with the resize. A Play intent supersedes any
  older manual-reading recovery. Playback-owned reflow pins the actual media
  ayah rather than the fade-led visual target; when that ayah is now taller
  than the viewport, recovery goes directly to its active word instead of first
  pinning line one.
- Word-level follow is the engine's *secondary* constraint: while follow is on,
  each active word reports its list-viewport bounds and
  `ReaderFocusController.keepWordInView` applies a **bottom-only** reading-band
  correction (pure `wordBandDeltaPx`) so the active line lifts clear of the
  player-bar fold / edge fade. Top margin stays 0 so short-verse reading-line
  anchors are not fought. Verse-level `anchorOffsetPx` also takes a bottom
  guard so a near-full-height verse is not parked with its last lines under
  that chrome. A repeat-aware timing backtrack to word one is a fresh verse
  focus event even though its media item and ayah key did not change, restoring
  the adaptive top anchor before the reciter walks the verse again.
- Opening or foregrounding a held session runs one exact word-position restore,
  even while paused. If the ayah is wholly offscreen the controller first
  materializes it; if a tall ayah is already attached it skips the verse-top
  anchor and moves directly to the held word. The request is consumed after a
  real measurement, so normal paused state and end-of-playlist resets cannot
  keep driving scroll. Display reflow issues the same restore directly for a
  visible tall playback ayah, or after the verse-level pin when materialization
  is still needed. The interaction arbiter still makes all of these yield to
  hand scrolling, search, annotation, pending jumps, and the Ink Lab focus
  freeze.
- Re-enabling follow (Play or return-to-ayah), including during a display
  reflow, while the actual playing ayah is tall and still has live geometry
  skips the verse-top anchor entirely and restores the active word directly.
  The verse-first path remains only for wholly offscreen targets that must be
  materialized before their word can be measured.
- Annotation editing uses the same secondary-focus path: the field reports live
  viewport bounds as it grows and as the IME rises,
  `keyboardOverlapPx` removes any bottom chrome already outside the list before
  `annotationFieldDeltaPx` anchors its bottom on the keyboard-safe line, and
  `ReaderFocusController.keepAnnotationInView` serializes that glide with every
  other reader scroll. Playback continues while writing, but lyric auto-follow
  yields until the editor closes so it cannot pull the field back under the IME.

## Playback

`PlaybackService` is a `MediaLibraryService`: lock-screen and notification
controls, audio focus, becoming-noisy handling, wake mode for streaming, plus a
browsable/searchable catalog of all 114 surahs for Assistant, Android Auto, and
other media clients. Search requests are expanded into the same complete ayah
queue used by the reader. Audio flows through a `CacheDataSource` backed by a
1 GB LRU `SimpleCache`, so an ayah streams once and replays from disk afterwards.

## Android voice / Assistant

Voice support is entirely OS-facing; there is no microphone, speech recognizer,
or Assistant UI inside the app. Three Android surfaces deliberately remain
separate:

- `PlaybackService` exposes the 114-surah Media3 catalog and media-session
  controls, including legacy `MEDIA_PLAY_FROM_SEARCH` fulfillment.
- `shortcuts.xml` exposes classic Google Assistant App Actions that resolve to
  `beautifulquran://` deep links parsed by `AssistantIntents`.
- `QuranAppFunctions.kt` exposes global Android 17 agent actions, while
  `ForegroundAppFunctions.kt` registers activity-scoped navigation and current
  verse context only while `MainActivity` is visible.

All three feed the same repositories, player, settings, and hand-rolled paper
stack used by touch input. They do not create a parallel voice state machine.
Developer-only timing tools are intentionally not agent-controlled.

The implementation inventory, deep-link contract, deterministic ADB tests,
Assistant preview steps, Gemini availability gate, troubleshooting guide, and
definition of full support live in
[`docs/ASSISTANT.md`](ASSISTANT.md). Keep that document current whenever an
Assistant, media-catalog, deep-link, or AppFunctions contract changes.

Continue Listening (`settings.lastSurah` / `lastAyah`) updates only when a
verse is actually recited — not on open, scroll, or rail jump. It is keyed on the
**playing media item**, never on the reader's fade-led focus target: that target
names the next verse up to `InkEngine.fadeLeadMs` before a note of it is heard.
Writes go through `SettingsRepository.updateListeningPosition`, which touches
only the two position keys and no-ops when the position is unchanged.

## UI structure

Four full-screen "sheets", one visible at a time. Navigation is a
hand-rolled **paper stack** (`PaperStackApp` in `MainActivity`): the sheets
sit on top of each other like pages of a book, and moving between them is a
horizontal page turn — draggable, fling-able, with page-turn audio
(`PageTurnSounds`) tracking the live sheet position:

- `bookmarks/BookmarksScreen` — a left-hand index revealed by swiping right
  from Chapters or tapping its exposed ruby ribbon; saved verses are searchable
  and sectioned by collapsible surah headings, and each result jumps directly
  back into the reader.
- `home/HomeScreen` — surah list with search (surah names / `surah:ayah`
  references, plus Quran-wide word hits sectioned by surah with truncated
  expand-in-place lists), a continue-listening card, and a floating playback
  control (paper-native transport) while a verse is loaded in the session;
  opening a word hit flashes that Arabic (and English gloss) word twice with
  the orange repeat wash (directional wash in, dissolve out) on the reader. The reader's
  embedded `PlayerBar` takes over once that sheet is open.
- `reader/ReaderScreen` — the follow-along view. Composed of
  `SurahHeader` + one `AyahBlock` per ayah in a `LazyColumn`;
  `AyahBlock` renders `WordUnit`s (Arabic mode, RTL flow) or one annotated
  `ResponsiveEnglishAyah` (English mode, LTR prose with word ranges for ink
  and taps); `PlayerBar` sits flat at the
  bottom. Floating Back-to / return-to-ayah ornaments share
  `FloatingPaperControl` (enter/exit + bottom inset) with the cover float. All scrolling and verse-position logic routes through the
  focus engine (`reader/focus/`, see below).
- `settings/SettingsScreen` — reciter, reading mode, text size, display
  toggles, theme, attributions; developer mode unlocks the Timings Lab.

Ink-bleed overlays soak **the sheet they belong to**, not a full-screen
layer above the stack: the repeat question and the
[Root Word Viewer](ROOT_VIEWER.md) (default word long-press) live on the
reader sheet; the [Timings Lab](TIMINGS_LAB.md) (developer mode) stays
stack-level because Settings can open it too. Shared primitive:
`InkRevealOverlay`. Media-session notifications are platform-exempt from
`POST_NOTIFICATIONS`, so playback never gates on a notification-permission
prompt.

On a cold start the whole stack sits behind the **entrance cover**
(`entrance/EntranceCover` on Android; `web/src/ui/entrance/` on web) — the
closed mushaf: gilded ornament concentric with the display's corner radii,
the title, and the isti'adha fading in as text, before the cover swings open
onto chapter selection. It is a one-shot ceremony, not a sheet in the
stack; see the entrance section of [DESIGN.md](DESIGN.md).

ViewModels get their dependencies from `QuranApp` (Application) through
`AppViewModelFactory`. Settings changes propagate reactively: e.g.
`ReaderViewModel` observes `reciterId` and reloads timings / restarts the
current ayah in the new voice when it changes on the settings sheet.

## Code conventions

- Kotlin official style; Compose function-per-component; one file per screen
  plus a components file where a screen has several.
- KDoc on every non-obvious public type/function; inline comments only where
  the code can't say it (e.g. *why* a state read is deferred to the draw
  phase, *why* lintVital is off for release).
- UI state flows down as immutable data classes; events flow up as lambdas.
- No ripple indications anywhere — taps respond with content motion, not
  Material ink, to preserve the paper feel (see docs/DESIGN.md).
- Tests live where logic lives: the pure engine and the segment parser have
  JVM unit tests; UI correctness is kept reviewable by keeping composables
  small and stateless.

## Build & delivery

CI (`.github/workflows/build.yml`) on every push: verify the committed
`data/quran.db` asset → unit tests. On `master` only, it
continues with **assembleRelease** (R8-minified, resource-shrunk; see
docs/PERFORMANCE.md) → upload artifact → publish the APK to the rolling
`latest` GitHub release. Release builds are signed with the repo's debug
keystore so sideloaded installs update in place; swap in a real keystore
before any store release.
