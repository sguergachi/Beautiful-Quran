# Architecture

How Beautiful Quran is put together, and why each piece is the way it is.

## The one-sentence version

A **prepackaged SQLite database** of independently sourced Quran content and
open timing fallbacks, plus a **separate seven-day runtime content cache**, feed a **single-module
Compose app** whose signature feature — words lighting up in time with the
reciter — is driven by a **pure-function sync engine** polling a **Media3
player** 30 times a second.

```
tools/build_db.py  (offline generation; committed asset verified in CI)
   quran-json (npm) ─┐
   quran-align zip  ─┼─► validate, align, pack ─► data/quran.db
   QAC morphology   ─┘   (roots / lemma / POS — see ROOT_VIEWER.md)
                                                        │
app (runtime)                                           ▼
   QuranDatabase ── copies asset once, opens read-only SQLite
   QfContentCacheDatabase ── atomic runtime timing + mushaf rows and sync tokens
   QuranRepository ── merges fresh runtime fields with independent bundled rows
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

1. **Offline-first reader.** The released reader still has no accounts,
   analytics, or client API keys and works in airplane mode using the verified
   bundled timing baseline and cached audio. Recitation audio uses a 1 GB
   listening cache; explicit downloads keep chapters on the phone. A narrow
   backend is the timing-content boundary: legacy QDC is its transitional
   provider; after QF approval, authenticated QF content replaces only that
   provider.
2. **The data pipeline is a build step, not app code.** Everything fragile
   about data (different word segmentations, a diagnostic prefix in an upstream
   release, basmalah offsets) is resolved by the canonical Python pipeline with
   validation and logged diagnostics. It runs at build time for quran-align and
   on the backend for runtime timing snapshots; repair logic never enters a
   client.
3. **Purity where correctness matters.** The sync engine (`HighlightEngine`)
   is a pure function over immutable data — trivially unit-testable, no
   Android dependencies.
4. **Small over clever.** No Hilt (a hand-rolled ViewModel factory over
   Application-scoped singletons), no Room (a 100-line raw-SQLite wrapper),
   no navigation library at all (the four sheets are a hand-rolled paper
   stack in `MainActivity`). Every dependency earns its place.

> **Quran Foundation Content API migration.** The cache architecture is in
> place, but the provider is not yet an authenticated QF integration. QF
> credentials remain a backend-only post-approval step. The remaining approval
> and deployment gates live in [QF_CONTENT_SYNC.md](QF_CONTENT_SYNC.md).

### Transitional provider boundaries

```text
word/QCF today: Android / web ── direct fixed-corpus GETs ── api.quran.com
                                      │
                                      └─ atomic 6-day/7-day device cache

repeat timing: Android / web ── Content Sync facade ── Python normalizer
                                      │
                                      └─ legacy QDC today / authenticated QF later
```

The unauthenticated legacy word/QCF adapter fetches the same fixed 114-chapter
corpus for every user; it sends no account, secret, reading position, search,
bookmark, or note data. It validates all 77,429 records and each QCF page-font
codepoint run before one atomic client-cache replacement. The future QF-shaped
Content Sync transport remains available behind the same cache interface.

Repeat timing never consumes raw QDC in a client: the service runs the canonical
cleaner, clock rebase, corrections, repairs, and physical finalizer first. It
accepts no arbitrary upstream URL, stores no client identifier, and keeps the
future QF ID map and OAuth flow server-side. See
[`backend/README.md`](../backend/README.md). This is a transitional engineering
control, not evidence of QF permission for the legacy endpoint.

### Runtime timing read path

For each selected reciter and chapter, both clients use the same order:

1. Read a locally stored normalized snapshot when its source age is no more
   than seven days. The facade sends the backend snapshot's actual age and the
   device subtracts it from its checkpoint; the two cache layers cannot create
   a hidden 14-day window.
2. Otherwise read the bundled quran-align row immediately. First install,
   airplane mode, backend outage, revocation, and an expired cache therefore do
   not block the reader or change its controls. Because quran-align is one-pass,
   the orange repeat overlay is the one capability unavailable until a fresh
   runtime snapshot exists; ordinary word-by-word wash remains available.
3. Bootstrap/incremental sync in the background on launch and resource open.
   Revalidate after six days so a normal retry window remains before day seven;
   launch and network-restored hooks first inspect the local checkpoint, so a
   current cache makes zero API calls. If a due refresh fails offline, both
   clients retry automatically when connectivity returns.
   A missing or expired word/QCF cache keeps the cold-start mushaf cover up
   until that first refresh succeeds or fails; a fresh/still-readable cache
   never delays opening, and offline failure releases the independent reader.
4. Validate full-corpus coverage, then commit snapshot rows, source age, and the
   new opaque token atomically. A failed page, partial snapshot, parse, or write
   preserves the prior token and rows.
5. If playback is active when a snapshot arrives, retain the current timing
   object for that session. The new rows take effect only while quiet or on the
   next load, preventing an in-flight word from jumping.

The word/QCF cache uses the same freshness and atomicity contract. Today the
clients normalize direct legacy `by_chapter` responses into `mushafs:1`; after
approval the replaceable transport can consume QF Content Sync without changing
the repositories or cache schema. Android stores rows in `qf-content-cache.db`;
the browser uses IndexedDB. Neither cache is part of Git, the APK, or the Pages
artifact.
Developer Mode shows the selected resource's state, next refresh, seven-day
limit, last failure, and the exact number of API requests made in that process
or browser session. The cold-start cover reports requests as they start, then
switches to save status after the complete 77,429-row snapshot is validated and
while it is committed atomically.

## The data pipeline (`tools/build_db.py`)

Sources (all fetched over HTTPS, cached in `tools/.cache/`):

| Source | Provides | Why this one |
|---|---|---|
| `quran-json` (npm) | Uthmani Unicode text, Saheeh International translation, surah metadata | Tanzil-derived, verse-keyed, no auth |
| Quran.com chapter API (runtime only) | Per-word English gloss, transliteration, QCF V2 layout, page | Normalized and validated directly into the client `mushafs:1` cache; never committed to `quran.db` |
| `cpfair/quran-align` release zip | Word-level timestamps per reciter, CC-BY 4.0 | The canonical open word-alignment dataset, matched to everyayah.com audio |
| quran.com `qdc` audio API (runtime only) | **Repeat-aware** word timestamps for reciters in `QDC_REPEAT_RECITERS` | Normalized behind `recitations:*`; never committed to `quran.db`. See [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md) |
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
- The pinned quran-align Sudais file contains a build diagnostic before its
  otherwise complete JSON array. `parse_alignment_payload` accepts only that
  known narrow shape; coverage and the locked archive digest still gate it.
- **TimingEngine V1.5** gives each timing source one job: qdc supplies repeat
  topology at runtime, quran-align supplies the bundled streamed-file clock and
  monotonic fallback, and measured audio supplies physical limits.
  `normalize_runtime_timings.py` runs `rebase_qdc_clock` and every canonical
  correction/repair/finalizer on the backend before a snapshot can reach a
  client. Full detail:
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

Bundled output schema (read-only at runtime):

```sql
surahs   (id, name_arabic, name_transliteration, name_translation, revelation_place, ayah_count)
ayahs    (surah_id, ayah_number, text_uthmani, translation_en)
words    (surah_id, ayah_number, position, arabic, translation_en, transliteration)
reciters (id, slug, name, style, has_timings)
timings  (reciter_id, surah_id, ayah_number, segments)   -- segments = "[[pos,startMs,endMs],…]"
data_provenance (key, value) -- explicitly records the timing baseline and replacement path
word_morphology (surah_id, ayah_number, position, root, lemma, pos, features)
roots (root, occurrence_count)
root_occurrences (root, surah_id, ayah_number, position)
```

Morphology tables power the [Root Word Viewer](ROOT_VIEWER.md) (Quranic Arabic
Corpus). Concordance counts and jump lists come from `roots` /
`root_occurrences`.

Timing segments are stored as one compact JSON array per (reciter, ayah)
rather than one row per word: 43,641 verified compatibility rows instead of
hundreds of thousands of word rows. Runtime snapshots use the same segment
shape in a separate Android SQLite / browser IndexedDB cache.

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
- `HighlightClock` never guesses that a large backward position correction is
  a seek. `PlayerController` publishes every authoritative Media3 discontinuity
  to the clock but identifies only seeks/repeats/playlist replacement as new
  ink performances. That Media3-owned ink generation is the word activation,
  so a seek produces one bloom while later position adjustments rebase the
  clock silently.
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
  viewport bounds as it grows; `imeAnimationTarget` supplies the keyboard's
  completed geometry before the first focus movement,
  `keyboardOverlapPx` removes any bottom chrome already outside the list before
  `annotationFieldDeltaPx` anchors its bottom on the keyboard-safe line, and
  `ReaderFocusController.keepAnnotationInView` continuously remeasures through
  one slow, direction-locked glide serialized with every other reader scroll.
  Playback continues while writing, but lyric auto-follow yields until the
  editor closes so it cannot pull the field back under the IME.

## Playback

`PlaybackService` is a `MediaLibraryService`: lock-screen and notification
controls, audio focus, becoming-noisy handling, wake mode for streaming, plus a
browsable/searchable catalog of all 114 surahs for Assistant, Android Auto, and
other media clients. Search requests are expanded into the same complete ayah
queue used by the reader. Audio flows through a `CacheDataSource` backed by a
1 GB LRU `SimpleCache` for listening (`cacheDir`) plus a non-evicting keep
tree (`filesDir`) for chapters the reader asked to download. Playback reads
keep first, then listen, and writes only listen. A downloaded ayah is
dropped from listen so the one storage total does not count it twice.
Leftover `filesDir/audio` from the old listen LRU moves onto `cacheDir` once,
before either `SimpleCache` opens; if both trees already contain audio, the
current listening cache wins and the legacy evictable copy is discarded. The
completion marker is written only after relocation succeeds, preventing later
explicit downloads from ever being reclassified as cache. Delete all empties
the live caches in place so playback keeps the same instances. Settings →
Download manager shows total audio storage plus the listening-cache share, but
its chapter catalog reads permanent keep storage only—ordinary listening never
appears as a download. Chapter and reciter Delete preserve the evictable
listening cache; Delete all intentionally clears both trees. Every reciter
starts collapsed; only an explicit tap opens
one. Loading and loaded facts reserve the same row heights, so applying the
initial cache scan changes the ink without moving the page. Reciters sit
24 dp apart. The chevron is the only trailing
control on the name. Download all, Pause, Resume, and Delete sit
16 dp after the subtitle facts, never under the chevron. Open catalog is flush with the reciter spine,
gold hairlines below chapters. While a chapter downloads, its hairline becomes
a dark page-ink progress fill based only on ayahs already complete in permanent
storage; the whole completed/total verses and percentage line uses that exact
same dark ink as one active progress state. Completed chapters keep the full
divider and their downloaded facts in that dark ink, so completion remains active;
pausing freezes the same partial divider at its last fully stored ayah and shows
only the matching completed/total verse fraction in dark ink.
A chapter row is bodyLarge name, labelSmall verses · size · status, with
trailing verbs in a reserved 128 dp slot (Download, Pause, Resume,
Delete). A paused chapter shows Delete then Resume in that slot, Resume
on the right edge. Fetch verbs are green;
delete is quiet ink. Pause keeps ayahs
already fetched. Chapter Pause parks only that chapter and the worker continues
with the next waiting chapter. Reciter Pause parks only that reciter’s active
and waiting chapters; queued work for other reciters continues. Each paused
chapter keeps its own progress clock. A Resume that races the cancelled writer
prepends a full retry of that chapter, so the interrupted ayah cannot be skipped
or stranded behind the queue. Disk scans use one serialized refresh path, so
an older concurrent scan can never restore stale controls. Explicit chapter
ownership keeps the shared basmalah while any downloaded chapter still needs it
and removes it after the last owner is deleted.
Download all stays on an open reciter. Collapsed Resume
continues a pause or unfinished partials, not empty chapters; the Resume verb
already communicates pause, so the progress line does not repeat “Paused.”
Collapsing a reciter does not reopen it because a download is running.
An ayah counts as complete only when Media3 records its content length and the
cache holds that full length; partial spans stay resumable. A chapter download
also keeps the shared basmalah clip when its playback queue begins with one. Playback
strips Media3's `FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN` so streamed ayahs stay
on disk the same way Download all / Wi‑Fi prefetch already did. Storage
changes and the catalog scan form one visible transition: the affected action
shows an ellipsis until the post-operation scan is applied. Completion tokens
are revisioned, so an older scan cannot uncover a stale Download / Resume /
Delete state or acknowledge a newer disk change. Delete also cancels and waits
for the affected blocking `CacheWriter` before removing its spans. Pause and
Resume are already authoritative live state, so they remain visible while the
catalog scan catches up; a parked chapter also stays visible through the
resume-to-worker handoff instead of flashing its older catalog action.
An in-flight ayah therefore cannot put bytes back after the refreshed state is
shown.
The first catalog scan likewise shows reciter names with an ellipsis instead
of a false empty state. Chapter rows are lazy, so opening an expanded reciter
does not compose its full 114-row catalog in one frame.

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

Continue Listening (`settings.lastSurah` / `lastAyah`) is keyed on the
**playing media item**, never on the reader's fade-led focus target: that target
names the next verse up to `InkEngine.fadeLeadMs` before a note of it is heard.
Writes go through `SettingsRepository.updateListeningPosition`, which touches
only the two position keys and no-ops when the position is unchanged. Those
same keys are the green ribbon's sole source: Home's Continue row, green chapter
mark, chapter-row return, Reader ribbon, and green rail tick share one target.

A reader visit snapshots the stored last ayah: the full green ribbon and green
rail tick remain parked there instead of following bare scroll or focus. Once a
real pause holds past Media3's brief transition dip, that media verse becomes
the stored last ayah and the thinner passive ribbon unfurls onto it. Resuming
leaves the newly placed marker there. The parked state retains both chapter and
ayah, so an in-place mushaf or continuous-reader chapter handoff cannot inherit
the old chapter's same-numbered verse. Green owns the screen-edge
lane while the bookmark cloth or outline stays fixed in a reserved inner lane.
That reservation is Reader-only; Home bookmark ribbons retain their original
chapter-document position. Reader green is inset 4 dp farther toward the edge.
Chapters observes the new stored place after the reader sheet covers it, and a
tap on that marked chapter returns to its parked ayah. This makes silent
reading leave the last-heard marker untouched.
Once a return to Chapters commits and its outer ribbon lane nears the screen
edge, that parked cloth unfurls into the row; an abandoned back swipe does not
spend the reveal.

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
- `reader/` — verse sheet (scroll) or 604-page mushaf pager (`ReadingLayout`).
  Mushaf pages keep the 604 `qcf_page` boundaries, then balance each page's
  words from `qcf_line` over one additional visual line for the larger hand;
  chapter openings remain hard boundaries. They use the same Hafs +
  `InkEngine` wash as Arabic-only scroll. The pager virtualizes
  to the settled page ±1; ink clocks run on the settled page and, only during
  an automatic turn, the page that owns the voice. Only the current leaf
  accepts a word tap; the pager then holds that leaf against follow until
  the seek lands. Scroll and mushaf hand one another their visible ayah/leaf
  when the reading layout changes.
- `home/HomeScreen` — surah list with search (surah names / `surah:ayah`
  references, plus Quran-wide word hits sectioned by surah with truncated
  expand-in-place lists), a continue-listening card, and a floating playback
  control (paper-native transport) while a verse is loaded in the session;
  opening a word hit flashes that Arabic (and English gloss) word twice with
  the orange repeat wash (directional wash in, dissolve out) on the reader. The reader's
  embedded `PlayerBar` takes over once that sheet is open.
- `reader/ReaderScreen` — the follow-along view. Scroll layout is
  `SurahHeader` + one `AyahBlock` per ayah in a `LazyColumn`. Mushaf layout
  is `MushafPager` (604 Madinah pages, same ink). `AyahBlock` renders
  `WordUnit`s (Arabic mode, RTL flow) or one annotated
  `ResponsiveEnglishAyah` (English mode, LTR prose with word ranges for ink
  and taps; repeated source labels for one multi-word English phrase are
  coalesced there, while genuine repeated Arabic words remain repeated);
  `PlayerBar` sits flat at the
  bottom. Floating Back-to / return-to-ayah ornaments share
  `FloatingPaperControl` (enter/exit + bottom inset) with the cover float. All scrolling and verse-position logic routes through the
  focus engine (`reader/focus/`, see below).
- `settings/SettingsScreen` — reciter plus two detail leaves in the app's
  physical paper stack, Customize and Download manager, reached by tap or
  horizontal page turn.
  Customize owns text size, translation visibility, view, layout, verse and
  page numbers, theme, annotations, ayah-selector side, word-by-word gloss,
  with a pinned faded-leaf preview, a full-bleed paper dissolve under it,
  and the collapsed ayah rail on the chosen edge;
  mushaf hides view, annotations, the rail
  side, word-by-word, and verse-number script — the preview is 21:91–92
  as three printed QCF lines scaled to the measure). The main Settings page
  keeps only navigation rows and attributions;
  developer mode unlocks the Timings Lab and
  the [Tarjīʿ Lab](TARJI_LAB.md).

Ink-bleed overlays soak **the sheet they belong to**, not a full-screen
layer above the stack: the repeat question and the
[Root Word Viewer](ROOT_VIEWER.md) (default word long-press) live on the
reader sheet; the [Timings Lab](TIMINGS_LAB.md) and the
[Tarjīʿ Lab](TARJI_LAB.md) (developer mode) stay
stack-level because Settings can open them too. Shared primitive:
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
