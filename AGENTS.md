## Memory

Your memory is OptMem:
- The tool is `~/.optmem/memo`
- Your memories are in `~/Dev/Beautiful-Quran/.optmem/memory`

OptMem outlives every session, compaction, model and vendor change.
Without it you do not know who you are, or what was decided and tried.

### At startup: activating OptMem (mandatory)

Run `~/.optmem/memo wake` before any other tool call, in every session, and
then do exactly what it prints, to the end of its output.

### While working: register memories (mandatory)

Call `~/.optmem/memo note "<1 line, max 280 chars>"` whenever you learn
something new, or something worth keeping happens. That covers a task
worth real effort, a fact or insight the user teaches you, anything you
learn about their life (even indirectly), any event of lasting effect.

Do not register redundant memories.

If `~/.optmem/memo note` asks a compression: do it before your next action.

Never edit or delete anything under `~/Dev/Beautiful-Quran/.optmem/memory`: the tool manages it.

### When you need an old memory: search, or navigate

`~/.optmem/memo recall <regex>` searches every memory, word for word.

Your memories also form a binary tree: #0-1, #2-3 ... exist as one-line
summaries, pairs of those as #0-3, and so on -- every `#a-b` line wake
prints is one node of it. `~/.optmem/memo zoom <a-b>` opens a node into its
two halves, down to the raw memories.

### If you're a subagent: skip everything above

Parallel sessions on this machine are all you, and may all write memories.
A subagent is not: it must never run `memo`, because it cannot judge what
is already known, and its notes would arrive duplicated and incorrectly.
When you spawn one, write: `You are a subagent. Don't run memo.`

# AGENTS.md — working in this repo

Guidance for AI coding agents (and new humans) working on **Beautiful Quran**,
a single-module Android app written in Kotlin with Jetpack Compose. Its
signature feature is a lyric-style follow-along view: each Arabic word lights
up in sync with the reciter's audio, karaoke-style.

Read this file first. For depth, the real documentation lives in `docs/` —
this file tells you what exists, how to build and test, and the invariants
you must not break.

## Repo map

```
app/                    The entire Android app (single Gradle module)
  src/main/java/com/beautifulquran/
    data/               SQLite wrapper, repositories, models (no Room)
    domain/             HighlightEngine — the pure word-sync engine
    playback/           Media3: PlaybackService, PlayerController, prefetch
    ui/entrance/        Cold-start ceremony: the closed mushaf cover
    ui/home|reader|settings|theme/   Compose screens + design system
    timingslab/         In-app editor for word-timing corrections
  src/test/             JVM unit tests (JUnit 4)
data/quran.db           Canonical committed SQLite database consumed by both apps
data/lexicon.db         Lane's Lexicon, keyed by QAC root — the Root Viewer's
                        classical entry. Committed, loaded lazily, separate from
                        quran.db so timing rebuilds don't rewrite 20 MB of it
data/dictionary.db      English Wiktionary Arabic (kaikki extract), keyed by QAC
                        lemma — Root Viewer Dictionary section. Lazy, ~1 MB
tools/build_db.py       Data pipeline that generates quran.db (build-time, not app code)
tools/build_lexicon_db.py  Renders Perseus' TEI edition of Lane into lexicon.db
tools/build_dictionary_db.py  Filters kaikki Arabic JSONL onto QAC lemmas → dictionary.db
tools/timing_overrides/ Local timing-report scratch; CI rejects committed JSON
tools/timing_patch_cases/ Unit tests for systematic cleaner / span-protect fixes
tools/timing_repairs/   CTC auto-repairs rebased onto current source timing
tools/audio_onsets/     Generated leading-silence evidence from everyayah audio
tools/detect_audio_onsets.py  Opening-range scanner that regenerates that evidence
scripts/                Linux emulator setup / run helpers
docs/                   Architecture, design language, performance, timings docs
                        …and the GitHub Pages product page (index.html + styles.css)
docs/ornaments.css      Generated: the product page's ornaments (see below); do not hand-edit
web/                    Browser port (Vite + React): Focus / Highlight / Ink + paper reader
.github/workflows/build.yml   CI: tests on all branches; assembleRelease + publish APK on master only
.github/workflows/web.yml     CI: Vitest + web build; deploys a Pages artifact on master
```

## Build, test, run

Requires **JDK 21**. No Android device/emulator is needed for tests.

```bash
./gradlew testDebugUnitTest     # unit tests — run these before committing
./gradlew assembleDebug         # debug APK
./gradlew assembleRelease       # what CI ships (R8-minified; falls back to debug keystore)
python3 tools/test_build_db.py  # timing pipeline regressions (~1s, no Gradle)
```

- **Toolchain, if Gradle can't find it.** A non-login shell inherits neither,
  so export both: `JAVA_HOME` (`/usr/lib/jvm/java-21-openjdk` on Arch/CachyOS,
  `/usr/lib/jvm/java-21-openjdk-amd64` on Debian/Ubuntu images) and
  `ANDROID_HOME=$HOME/Android/Sdk`. `local.properties` is gitignored, so a
  fresh clone or worktree has no `sdk.dir` and relies on `ANDROID_HOME`.
- **`./gradlew lintDebug` fails on purpose.** ~37 pre-existing Media3
  `@UnstableApi` opt-in errors. The real gate is `lintVitalRelease` inside
  `assembleRelease` (`checkReleaseBuilds = false`). Not a regression.
- `data/quran.db` is **committed**, so a fresh clone builds
  offline with no extra steps. Only run `python3 tools/build_db.py` if you are
  deliberately changing the data (it downloads sources over HTTPS into
  `tools/.cache/` and regenerates the asset).
- `data/lexicon.db` is committed too, and is rebuilt only to deliberately
  change the lexicon: `python3 tools/build_lexicon_db.py` (downloads the pinned
  Perseus TEI XML into `tools/.cache/`, ~32 MB). It needs `quran.db` to exist,
  since it keys entries by the roots that database already carries.
- `data/dictionary.db` is committed the same way: rebuild with
  `python3 tools/build_dictionary_db.py` (caches ~485 MB kaikki Arabic JSONL
  under `tools/.cache/`, emits a ~1 MB QAC-lemma subset). Bump
  `DictionaryDatabase.DB_FILE_NAME` when its content changes.
- `docs/ornaments.css` and `docs/ornaments/*.svg` are **committed** too: the
  Pages workflow copies `docs/` verbatim, so the product page can't run the
  TypeScript ornament generator itself. `npm run build:ornaments` (from `web/`)
  re-runs it and rewrites both. Only do that to deliberately re-gild the page —
  a different seed grows an entirely different composition.
- To run the app in an emulator on Linux: `scripts/setup_android_emulator.sh`
  once, then `scripts/run_android_app.sh` (see README.md).
- **Parallel emulators.** Several lean headless AVDs can run at once, one per
  agent, each with its own adb serial. If your `scripts/run_android_app.sh`
  run says the AVD is already in use (or you just don't want to disturb an
  emulator another agent is using), boot your own: `scripts/emulators_up.sh N`
  creates/starts `BeautifulQuran_API_35_0..N-1` and prints each one's agent
  command — pick a free index and run it. `scripts/emulators_down.sh` stops
  them all. Before stealing or killing an emulator, assume another agent may
  be mid-test on it: prefer your own AVD index. Headless AVDs have no window;
  drop `ANDROID_EMULATOR_HEADLESS=1` to restart yours windowed for a visual
  check (that restarts only your own AVD).
- CI runs on every push: verifies the DB asset exists and runs unit tests.
  On `master` only, it also builds the release APK and publishes it to the
  rolling `latest` GitHub release.

## Invariants — do not break these

1. **DB content changes require a version bump.** This holds for every packaged
   asset: `QuranDatabase.DB_FILE_NAME` ↔ `quran.db`,
   `LexiconDatabase.DB_FILE_NAME` ↔ `lexicon.db`,
   `DictionaryDatabase.DB_FILE_NAME` ↔ `dictionary.db`. Each is extracted from
   assets to internal storage keyed on that version name. If you change the
   database content in any way, bump that suffix or existing installs silently
   keep the stale cached copy. `DatabaseFingerprintTest` enforces this:
   `data/<asset>.sha256` pins each digest to the version it was bumped for, so
   regenerating a database means updating **both** lines in that file and its
   `DB_FILE_NAME` to match. A red fingerprint test is the bump you forgot, not
   a flake.
2. **The data pipeline is a build step, not app code.** All data messiness
   (source mismatches, basmalah offsets, truncated upstream files) is resolved
   in `tools/build_db.py` with validation. The app assumes a clean, consistent,
   read-only database — never add data-repair logic to the app.
3. **`HighlightEngine` stays pure.** It is a pure function over immutable data
   with no Android dependencies, and it is where sync correctness lives. Keep
   it that way, and keep its unit tests passing and extended.
4. **The paper metaphor is law in UI code.** No dialogs, no snackbars, no FABs,
   no cards, no borders, no elevation/shadows, no Material ripple anywhere.
   Hierarchy comes from spacing, size, and ink strength (text alpha). Anything
   that would traditionally float becomes a line in the page, its own sheet,
   or an ink bleed. Read `docs/DESIGN.md` before touching any UI.
5. **Minimal dependencies, by design.** No Hilt (hand-rolled ViewModel factory
   over `QuranApp` singletons), no Room (raw SQLite wrapper in
   `QuranDatabase`), no navigation library (the three sheets are a hand-rolled
   paper stack in `MainActivity`). Do not introduce a framework to solve a
   problem the existing hand-rolled piece already solves.
6. **Offline-first, no backend.** No accounts, no analytics, no API keys. Only
   recitation audio touches the network at runtime (streamed and cached,
   1 GB LRU).
7. **Ink / karaoke fidelity is non-negotiable (Android + web).** The signature
   product moment is the soft directional ink wash: each word reveals with a
   **visible faded leading edge** (smootherstep `letterFadeIn` /
   `shapedWordBloom` / web `washMaskImage` + `paperCoverMaskImage`). Never
   replace that with whole-word opacity pops, hard `scaleX` cuts, or any
   cheaper approximation that loses the soft fade — not for performance, not
   for simplicity. Optimize *around* the wash (quantize masks, cache strings,
   one active word, recess via ayah veil) but do not degrade the wash itself.
   Arabic glyphs stay full opaque ink under a paper cover; never dim Hafs via
   glyph alpha. Web and Android must feel like the same product.
8. **Timing Lab / GitHub timing patches are fixed systematically.** Never
   default to dropping the issue JSON into `tools/timing_overrides/`. Classify
   first (raw qdc vs cleaned vs corrections vs repairs vs Lab expected), fix
   the **class** in `clean_qdc_artifacts`, use a narrow typed operation only
   when topology is irreducibly ambiguous, and lock it with
   `tools/timing_patch_cases/*.json` + `python3 tools/test_build_db.py`.
   Override JSON is local reproduction scratch only and must not be committed.
   Canonical anti-pattern: the first #570 attempt (one-off override); the
   correct fix is #571 (gap phantoms + span-protect).

9. **Chesterton's fence: find out why before you take it down.** Guards, gates
   and "obviously too broad" assertions here are usually load-bearing, and the
   reason is rarely written next to them. Before weakening, narrowing or
   deleting one, run `git log -S "<the line>"` and read the commit that added
   it. If you cannot say what breaks without it, you may not change it.
   Reporting "this looks too aggressive, here is what it turned out to be for"
   is a good outcome; quietly relaxing it is not. Worked example — a check that
   looks wrong and isn't: [docs/CHESTERTON.md](docs/CHESTERTON.md).

## Landing Timings Lab / GitHub timing patches

When the user asks to "fix the timing issue", "apply the timings patch", or
close a `Timings patch — …` GitHub issue, **do this checklist in order**:

1. **Extract** the Lab/issue expected segments (and positions).
2. **Compare** against the pipeline, not only the shipped DB row:
   - raw qdc: `tools/.cache/qdc_<id>.json` key `"surah:ayah"` (Alafasy = 7)
   - after `clean_qdc_artifacts` / `adjust_qdc_segments`
   - after `tools/timing_repairs/` (may *erase* a real span — check kind `drop`)
3. **Classify** the topology difference:
   | Symptom | Fix where | Test |
   |---|---|---|
| Forward spike, stray, split sliver, non-contiguous / **gap phantom** (`…11,8,9,13…` missing 12 or `…1,3,3…` missing 2) | `clean_qdc_artifacts` in `tools/build_db.py` | `tools/timing_patch_cases/<id>.json` + `python3 tools/test_build_db.py` |
| Topology cannot distinguish a false loop from a real repeat | narrow typed operation in `tools/timing_corrections/` | `pipeline: "timing_correction"` case |
   | Repair flattens a multi-word re-say that cleaned qdc still has | `apply_timing_repairs` span-protect (`erases_span_repeat`) | `pipeline: "erases_span_repeat"` case |
| Repair erases a peer same-word re-say while fixing elsewhere | per-position `preserve_peer_repeats` | `pipeline: "preserve_peer_repeats"` case |
| CTC repeat-vs-split / restore / drop quality | regenerate `tools/timing_repairs/` (`~/qasr`) | generator tests + rebuild |
| Single boundary steal, no structural signal | weighted source-conflict validation + surgical `kind: "boundary"` repair | `pipeline: "boundary_repair"` case |
| Whole ayah starts early because its MP3 has encoded silence | regenerate the reciter with `tools/detect_audio_onsets.py` | `pipeline: "leading_silence_offset"` case |
| Missing positions, unsafe clock, or marks outside the MP3 | fix the source/class; `finalize_timing_rows` completes, falls back, or withholds | completion/physics checks in `tools/test_build_db.py` |
4. **Implement the class fix** + add the patch case (input = broken shape,
   expected = Lab/ear topology). Run `python3 tools/test_build_db.py`.
5. **Rebuild**: `python3 tools/build_db.py`, bump `DB_FILE_NAME`, commit DB +
   cases.
6. **Do not** land per-ayah overrides. Delete any local reproduction JSON
   before committing.

Full write-ups: [docs/TIMINGS_LAB.md](docs/TIMINGS_LAB.md),
[tools/timing_patch_cases/README.md](tools/timing_patch_cases/README.md),
[tools/timing_overrides/README.md](tools/timing_overrides/README.md),
[tools/timing_repairs/README.md](tools/timing_repairs/README.md).

## Reading this codebase without burning context

Four files are large enough that opening one whole costs more than the rest of
this document combined: `ReaderComponents.kt` (~36k tokens),
`ReaderScreen.kt` (~31k), `build_db.py` (~23k), `SettingsScreen.kt` (~14k).

- **Grep for the symbol, then read a window around the hit.** `grep -n` for the
  function or property, then read with an offset and a limit. Do not open these
  files whole "to get oriented" — you will spend a third of your context before
  the first edit.
- **Prefer the extracted policy file over the composable.** Most reader
  decisions already live in small pure files next to their tests —
  `InkEngine.kt`, `OrderedWashGate.kt`, `FastForwardPolicy.kt`,
  `ReaderSessionGate.kt`, `SearchHitFlash.kt`, `RootReturnTarget.kt`. If a
  behavior has a name, it usually has its own file; find that first.
- **`docs/DESIGN.md` is ~12k tokens across 11 sections.** Read the section for
  the surface you are touching, not the whole file. The hard rules are
  invariant #4 above.
- **New logic goes in a pure function, not in the composable.** Every ink bug
  fixed in the last 120 commits (#485, #573, #575, #580, #587) was fixed by
  extracting the decision out of Compose and testing it. `InkEngine.kt` changed
  20 times in that window and never regressed; the untested composables around
  it account for most of the `Fix …` commits.

## Code conventions

- Kotlin official style; Compose function-per-component; one file per screen
  plus a components file where a screen has several.
- KDoc on every non-obvious public type/function; inline comments only where
  the code cannot say it (the *why*, never the *what*).
- UI state flows down as immutable data classes; events flow up as lambdas.
- Tests live where logic lives: pure logic (engine, parsers, mappers) gets JVM
  unit tests in `app/src/test/`; composables are kept small and stateless so
  UI stays reviewable instead of UI-tested.
- Performance is a feature: read `docs/PERFORMANCE.md` before changing
  anything in the reader's hot path (polling loop, recomposition scope,
  `derivedStateOf` usage).

## Where the real documentation is

| Doc | Read it when |
|---|---|
| `docs/ARCHITECTURE.md` | First stop for any change — pipeline, sync engine, modules, conventions |
| `docs/ASSISTANT.md` | Android voice work — media hooks, App Actions, Gemini AppFunctions, testing, and release gates |
| `docs/COMPLEXITY.md` | Before any refactor — complexity rules, current hotspots, open decompositions, and the invariants a refactor must preserve |
| `docs/quality-reviews/` | Multi-agent Android quality audits (summary + Grok/Codex; Claude when available) |
| `docs/quality-reviews/AGENT_REVIEWS.md` | **How to run real Codex (`gpt-5.6-sol`) and Claude Opus reviews** — CLI flags, gotchas; do not fake them with Grok |
| `docs/HIGHLIGHT_ENGINE.md` | The pure word-sync engine — karaoke model, binary search, repeat/high-water logic |
| `docs/INK_ENGINE.md` | **The wash itself** — `InkEngine.kt`, the most-changed subsystem in the repo |
| `docs/TAJWEED_PACING.md` | Letter-level ink pacing, wasl handoff, QPC orthography pitfalls |
| `docs/CHESTERTON.md` | Worked examples for invariant #9 — guards that look wrong and aren't |
| `docs/OUTPUT_LATENCY.md` | Route-based Bluetooth/output lag presets applied before the highlight clock |
| `docs/DESIGN.md` | Any UI/visual change — the paper metaphor and its hard rules |
| `docs/CONTEXTUAL_GUIDES.md` | Adding or changing feature education — live-target contract, wiring, and device verification |
| `docs/PERFORMANCE.md` | Anything touching the reader, scrolling, or the highlight loop |
| `docs/REPEAT_HIGHLIGHTING.md` | Repeat-aware timings and the orange second fade |
| `docs/GLIMMER.md` | Nightfall glimmer lifecycle, repeat retriggering, halo rendering, tuning, and visual checks |
| `docs/ANNOTATIONS.md` | Verse annotations (ḥawāshī) — reader's notes now, scholars' glosses later |
| `docs/ROOT_VIEWER.md` | Hold-to-reveal root lexicon — concordance counts, ayah jumps, QAC data |
| `docs/SHARE.md` | Gather mode and verse sharing — text + full-ink image shipped; video proposed |
| `docs/VERSE_ACTIONS.md` | Bookmark · note · share UX — verse-first share plan (designed, not implemented) |
| `docs/TIMINGS_LAB.md` | In-app timing editor + maintainer apply path (systematic first) |
| `tools/timing_patch_cases/README.md` | **Required** unit tests when landing a Lab/GitHub timing patch systematically |
| `tools/timing_overrides/README.md` | Local patch reproduction; committed JSON is rejected |
| `docs/WEB.md` | Web port plan — Focus / Highlight / Ink engines + paper reader in the browser |

## Working style

- Branch off `master`; keep commits focused with clear, descriptive messages.
- Update the relevant doc in `docs/` when you change behavior it describes —
  the docs are load-bearing and kept accurate.

## PR workflow (agents)

Open the PR, push, and move on — do not babysit CI, reviews, or mergeability
after it is open. **Exception that overrides "don't check PR status":** before
any follow-up commit on an existing branch/PR, check whether that PR is already
**merged** (`gh pr view <n> --json state` or equivalent).

- **Still open** → keep committing and pushing on the same branch/PR.
- **Already merged** → that PR is finished. Do **not** push more commits onto
  its branch expecting them to land in it, and do **not** reopen or reuse it.
  Branch fresh from the latest default branch (`master`), re-apply the
  outstanding work, and open a **new** PR. The opaque-background follow-up that
  was pushed onto merged #162 is the canonical example of what not to do.

## Cursor Cloud specific instructions

The startup snapshot already has the toolchain installed (JDK 21 at
`/usr/lib/jvm/java-21-openjdk-amd64`, Android SDK at `~/Android/Sdk` with
platform 35 + build-tools 35.0.0; builds also need platform 37.0 +
build-tools 36.0.0 for `compileSdk`). `JAVA_HOME`/`ANDROID_HOME`/`PATH` are
exported from `~/.bashrc`, so **login shells are already set up** — standard
build/test commands from the "Build, test, run" section above work as-is.

- The startup update script re-creates `local.properties`, so a fresh checkout
  builds. Install `platforms;android-37.0` and `build-tools;36.0.0` if missing
  (`compileSdk` uses API 37.0).
- **Emulator / GUI run works here** (KVM available): `scripts/run_android_app.sh`
  after a one-time `scripts/setup_android_emulator.sh`. Still verify with
  `./gradlew testDebugUnitTest` before committing — the signature word-sync
  feature is pure-JVM `HighlightEngine` and needs no device. Inspect bundled
  data with `sqlite3 data/quran.db`.

# GPT/Codex specific instructions

Write fewer lines of code. Elegant simple code is praised. Over engineering and
adding complexity is frowned upon. To make me happy, find the solution that
requires fewer lines and is elegant and simple to read and understand.

If you find a problem that seems complicated or will take us down a rabbit
hole, let me know! Don't just try to walk through walls. I may not care about
what I asked if I realize it's complicated and will hurt the maintainability
and agility of codebase. I want a fit codebase. Nice and thin.

You can push back if something I ask for is unreasonable or goes against the
goal of the project. If what I ask makes no sense then confirm it's what I
really want before starting work.
