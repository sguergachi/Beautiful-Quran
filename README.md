# Beautiful Quran

A beautiful, simple Quran reader for Android and the web. Its signature feature is a
**word-by-word follow-along view**: as the reciter recites, each Arabic word
illuminates in time with the audio, with its English meaning beneath it.

- 📖 Full Quran, Uthmani script, in the KFGQPC Hafs typeface
- ✨ Word-by-word highlighting synced to reciters with bundled timing data — **sync fidelity is the product core** ([docs/SYNC_FIDELITY.md](docs/SYNC_FIDELITY.md))
- 🎙️ 7 reciters, with ayah audio streamed and cached
- 🈯 Word-by-word English gloss + Saheeh International translation
- 🔁 Repeat one ayah, the whole surah, or any ayah range you choose
- 🔍 Search the English translation and word glosses within a surah
- 🌙 Warm paper light theme and near-black charcoal dark theme
- 📴 All text and timing data bundled offline; audio streams and caches
- 🚫 No ads, no accounts, no analytics

## Install on your phone

Grab **BeautifulQuran.apk** from the
[latest release](../../releases/latest), open it on your Android phone
(Android 8.0+), and allow the install when prompted.

### Voice commands

Beautiful Quran has no in-app microphone or listening controls. It exposes
media, App Actions, and Android 17 AppFunctions to the operating system instead.
Direct Android hooks work in a sideloaded build, but classic Assistant needs an
App Actions development preview or reviewed Play release, and Gemini invocation
of AppFunctions is currently a Google trusted-tester preview.

See [Android voice and Assistant support](docs/ASSISTANT.md) for the capability
matrix, testing commands, current platform limits, and full-support checklist.

## Building

```bash
./gradlew assembleDebug       # Android; copies data/quran.db into generated assets
npm --prefix web ci
npm --prefix web run build    # Web; copies the same database into dist
```

`data/quran.db` is committed, so normal builds stay offline. Run
`python3 tools/build_db.py` only when deliberately changing Quran data.

To create the Play Store app bundle, place the uncommitted signing key at
`release.keystore`, then run:

```bash
scripts/build_release_bundle.sh
```

The script builds `BeautifulQuran-<versionName>.aab` in the repository root and
verifies that it is signed with the upload certificate expected by Google Play.
In a linked Git worktree it also checks the primary checkout for
`release.keystore`; set `RELEASE_KEYSTORE_FILE` to use a key stored elsewhere.

`tools/build_db.py` downloads the Quran text, word-by-word data, and word-level
audio timings, validates them against each other, and packs them into a single
SQLite asset. CI (GitHub Actions) runs unit tests on every push; on `master`
it also assembles the release APK and publishes it to the rolling latest release.

## Run in an Android emulator on Linux

From a fresh Arch/CachyOS-style Linux install:

```bash
scripts/setup_android_emulator.sh
scripts/run_android_app.sh
```

The setup script installs/verifies JDK 21, using
`~/.local/share/android-dev/jdk-21` if no system Java is available, downloads
Android command-line tools to `~/Android/Sdk`, installs API 35 emulator
packages, writes `local.properties`, builds `data/quran.db` if
needed, and creates an AVD named `BeautifulQuran_API_35`.

For future runs, use:

```bash
scripts/run_android_app.sh
```

**Default is a visible emulator window** (not headless). When a shell was
started outside the desktop environment, the script reconnects to its local
Xwayland display. It only reuses an already-running emulator if that instance
is the requested AVD (`BeautifulQuran_API_35` by default); a headless instance
of that AVD is restarted with a window. Headless is opt-in only:

```bash
ANDROID_EMULATOR_HEADLESS=1 scripts/run_android_app.sh
```

If the emulator window does not appear or booting times out, check
`.android-emulator.log`. To make the SDK tools available in your current shell:

```bash
source scripts/android_env.sh
```

**Host Vulkan.** The run script points the emulator at your GPU’s Vulkan ICD
(NVIDIA / AMD / Intel) and the system `libvulkan`, and re-enables `Vulkan = on`
in `~/.android/advancedFeatures.ini` when a previous workaround left it off.
That avoids falling through to the emulator’s bundled Lavapipe, which can
SIGSEGV under guest HWUI load. Confirm in the log:

```text
Selecting Vulkan device: NVIDIA GeForce …
```

If host Vulkan is broken on your machine, you can still fall back with
`Vulkan = off` in `~/.android/advancedFeatures.ini` (OpenGL-only guest path).

## Documentation

- [docs/SYNC_FIDELITY.md](docs/SYNC_FIDELITY.md) — **product core:** word↔recitation sync fidelity and high-precision timing roadmap
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the app is put together: pipeline, sync engine, modules, conventions
- [docs/COMPLEXITY.md](docs/COMPLEXITY.md) — complexity rules, current hotspots, and the invariants a refactor must preserve
- [docs/DESIGN.md](docs/DESIGN.md) — the design language: the sheet, ink, color, type, motion
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md) — every smoothness technique in use and why
- [docs/REPEAT_HIGHLIGHTING.md](docs/REPEAT_HIGHLIGHTING.md) — the orange second fade for words a reciter repeats, and where the repeat-aware timing data comes from
- [docs/GLIMMER.md](docs/GLIMMER.md) — the Nightfall white-gold fresh-ink glimmer, repeat retriggering, halo rendering, tuning, and artifact checks
- [docs/ROOT_VIEWER.md](docs/ROOT_VIEWER.md) — hold-to-reveal root lexicon: counts, ayah concordance, jump-to-chapter
- [docs/TIMINGS_LAB.md](docs/TIMINGS_LAB.md) — in-app timing editor (developer mode)

## Data & attribution

| Content | Source | License |
|---|---|---|
| Uthmani text + Saheeh Intl. translation | [quran-json](https://github.com/risan/quran-json) (Tanzil / Al Quran Cloud) | free with attribution |
| Word-by-word gloss + transliteration | Quran.com dataset via npm | free with attribution |
| Root / lemma / morphology | [Quranic Arabic Corpus](http://corpus.quran.com) v0.4 | free with attribution + link |
| Word timing segments | [cpfair/quran-align](https://github.com/cpfair/quran-align) | CC-BY 4.0 |
| Repeat-aware timing segments | [quran.com](https://quran.com) `qdc` audio API | free with attribution |
| Recitation audio | [everyayah.com](https://everyayah.com) | free; rights remain with reciters |
| Arabic typeface | KFGQPC HAFS Uthmanic Script, King Fahd Complex | free redistribution |
