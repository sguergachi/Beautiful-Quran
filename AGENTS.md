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
tools/build_db.py       Data pipeline that generates quran.db (build-time, not app code)
tools/timing_overrides/ Last-resort per-ayah patches only (NOT default for Lab issues)
tools/timing_patch_cases/ Unit tests for systematic cleaner / span-protect fixes
tools/timing_repairs/   CTC auto-repairs applied before overrides
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
```

- `data/quran.db` is **committed**, so a fresh clone builds
  offline with no extra steps. Only run `python3 tools/build_db.py` if you are
  deliberately changing the data (it downloads sources over HTTPS into
  `tools/.cache/` and regenerates the asset).
- `docs/ornaments.css` and `docs/ornaments/*.svg` are **committed** too: the
  Pages workflow copies `docs/` verbatim, so the product page can't run the
  TypeScript ornament generator itself. `npm run build:ornaments` (from `web/`)
  re-runs it and rewrites both. Only do that to deliberately re-gild the page —
  a different seed grows an entirely different composition.
- To run the app in an emulator on Linux: `scripts/setup_android_emulator.sh`
  once, then `scripts/run_android_app.sh` (see README.md).
- CI runs on every push: verifies the DB asset exists and runs unit tests.
  On `master` only, it also builds the release APK and publishes it to the
  rolling `latest` GitHub release.

## Invariants — do not break these

1. **DB content changes require a version bump.** `quran.db` is extracted from
   assets to internal storage keyed on `QuranDatabase.DB_FILE_NAME`
   (`quran-vN.db`). If you change the database content in any way, bump that
   suffix or existing installs silently keep the stale cached copy.
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
   first (raw qdc vs cleaned vs repairs vs Lab expected), fix the **class** in
   `clean_qdc_artifacts` or the repairs span-protect / generator, and lock it
   with `tools/timing_patch_cases/*.json` + `python3 tools/test_build_db.py`.
   Overrides are last resort for true one-off boundary nudges only — see
   [Landing Timings Lab / GitHub timing patches](#landing-timings-lab--github-timing-patches)
   below. Canonical anti-pattern: the first #570 attempt (one-off override);
   the correct fix is #571 (gap phantoms + span-protect).

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
   | Forward spike, stray, split sliver, non-contiguous phantom, **gap phantom** (`…11,8,9,13…` missing 12) | `clean_qdc_artifacts` in `tools/build_db.py` | `tools/timing_patch_cases/<id>.json` + `python3 tools/test_build_db.py` |
   | Repair flattens a multi-word re-say that cleaned qdc still has | `apply_timing_repairs` span-protect (`erases_span_repeat`) | `pipeline: "erases_span_repeat"` case |
   | CTC repeat-vs-split / restore / drop quality | regenerate `tools/timing_repairs/` (`~/qasr`) | generator tests + rebuild |
   | Single boundary steal, no structural signal | `tools/timing_overrides/` **only** | `notes` must say why no rule applies |
4. **Implement the class fix** + add the patch case (input = broken shape,
   expected = Lab/ear topology). Run `python3 tools/test_build_db.py`.
5. **Rebuild**: `python3 tools/build_db.py`, bump `DB_FILE_NAME`, commit DB +
   cases (and override only if step 3 landed there).
6. **Do not** land a per-ayah override for a defect that already has a cleaner
   class. If an override exists for something the pipeline now handles, delete
   the override.

Full write-ups: [docs/TIMINGS_LAB.md](docs/TIMINGS_LAB.md),
[tools/timing_patch_cases/README.md](tools/timing_patch_cases/README.md),
[tools/timing_overrides/README.md](tools/timing_overrides/README.md),
[tools/timing_repairs/README.md](tools/timing_repairs/README.md).

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
| `docs/COMPLEXITY.md` | Complexity hotspots, subsystem ownership, and safe simplification roadmap |
| `docs/quality-reviews/` | Multi-agent Android quality audits (summary + Grok/Codex; Claude when available) |
| `docs/HIGHLIGHT_ENGINE.md` | The pure word-sync engine — karaoke model, binary search, repeat/high-water logic |
| `docs/OUTPUT_LATENCY.md` | Route-based Bluetooth/output lag presets applied before the highlight clock |
| `docs/DESIGN.md` | Any UI/visual change — the paper metaphor and its hard rules |
| `docs/PERFORMANCE.md` | Anything touching the reader, scrolling, or the highlight loop |
| `docs/REPEAT_HIGHLIGHTING.md` | Repeat-aware timings and the orange second fade |
| `docs/GLIMMER.md` | Nightfall glimmer lifecycle, repeat retriggering, halo rendering, tuning, and visual checks |
| `docs/ANNOTATIONS.md` | Verse annotations (ḥawāshī) — reader's notes now, scholars' glosses later |
| `docs/ROOT_VIEWER.md` | Hold-to-reveal root lexicon — concordance counts, ayah jumps, QAC data |
| `docs/SHARE.md` | Gather mode and verse sharing — text + full-ink image shipped; video proposed |
| `docs/VERSE_ACTIONS.md` | Bookmark · note · share UX — verse-first share plan (designed, not implemented) |
| `docs/TIMINGS_LAB.md` | In-app timing editor + maintainer apply path (systematic first) |
| `tools/timing_patch_cases/README.md` | **Required** unit tests when landing a Lab/GitHub timing patch systematically |
| `tools/timing_overrides/README.md` | Last-resort per-ayah overrides only — stop and classify first |
| This file § Landing Timings Lab… | Agent checklist when asked to fix/close a timings issue |
| `PLAN.md` | Historical product/engineering plan — context, not current spec |
| `docs/WEB.md` | Web port plan — Focus / Highlight / Ink engines + paper reader in the browser |

## Working style

- Branch off `master`; keep commits focused with clear, descriptive messages.
- Run `./gradlew testDebugUnitTest` before pushing — CI blocks on it.
- If a change alters what `tools/build_db.py` produces, regenerate `quran.db`,
  commit the new asset, and bump the DB version (invariant #1).
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

- **Build with JDK 21.** Prefer Temurin/OpenJDK 21 for the Gradle daemon and
  `jvmTarget`. If you invoke Gradle from a non-login shell that didn't source
  `~/.bashrc`, set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` first.
- **`local.properties` is gitignored** and points Gradle at the SDK
  (`sdk.dir=$HOME/Android/Sdk`). The startup update script re-creates it, so a
  fresh checkout still builds. Install `platforms;android-37.0` and
  `build-tools;36.0.0` if they are missing (`compileSdk` uses API 37.0).
- **Emulator / GUI run works here.** Use the repo scripts (KVM is available):
  `scripts/setup_android_emulator.sh` once if needed, then
  `scripts/run_android_app.sh` (windowed by default; host GPU + X11). Still
  verify with `./gradlew testDebugUnitTest` and APK builds
  (`assembleDebug` / `assembleRelease`) before committing. The signature
  word-sync feature lives in the pure-JVM `HighlightEngine` and is fully
  unit-testable without a device. You can also inspect real bundled data with
  `sqlite3 data/quran.db`.
- **`./gradlew lintDebug` fails on purpose here.** It reports ~37 pre-existing
  Media3 `@UnstableApi` opt-in errors. The real lint gate is `lintVitalRelease`,
  which runs inside `assembleRelease` with `checkReleaseBuilds = false` (see
  `app/build.gradle.kts`). Don't treat a `lintDebug` failure as a regression.

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
