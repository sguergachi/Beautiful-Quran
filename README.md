# Beautiful Quran

A beautiful, simple Quran reader for Android and the web. Its signature feature is a
**word-by-word follow-along view**: as the reciter recites, each Arabic word
illuminates in time with the audio, with its English meaning beneath it.

- 📖 Full Quran, Uthmani script, in the KFGQPC Hafs typeface
- ✨ Word-by-word highlighting synced to reciters with bundled timing data
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

### Send a debug APK to your phone (KDE Connect)

Paired phone + reachable KDE Connect (app open, same LAN or Bluetooth):

```bash
scripts/send_apk_to_phone.sh
```

That builds `app/build/outputs/apk/debug/app-debug.apk` and runs
`kdeconnect-cli --share` to the first reachable device. Accept the file on
the phone.

```bash
scripts/send_apk_to_phone.sh --skip-build --name "Pixel 10"
scripts/send_apk_to_phone.sh --wait          # poll up to 5 minutes
kdeconnect-cli -a                            # list reachable phones
```

If `kdeconnect-cli -l` shows the phone as paired but `-a` is empty, unlock
the phone, open KDE Connect, and join this machine's LAN. This desktop is
on wired `192.168.50.0/24`; the phone must be on that network (or Bluetooth
with KDE Connect's Bluetooth backend). Then re-run the script.

Over Tailscale (any network): phone adds this desktop at `100.85.148.20`
(KDE Connect → Add devices by IP). Desktop pins the phone in
`~/.config/kdeconnect/config`:

```
customDevices=100.99.159.46
```

Then `kdeconnect-cli --refresh`. Reachable looks like
`Pixel 10 … on 100.99.159.46 via LAN`.

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
`.android-emulator-<AVD>.log` (one per AVD). To make the SDK tools available
in your current shell:

```bash
source scripts/android_env.sh
```

**Parallel emulators for agents.** Each agent gets its own lean, headless
emulator with a distinct adb serial, so several can build and test
simultaneously:

```bash
scripts/emulators_up.sh 3        # create + boot BeautifulQuran_API_35_0..2
```

Each agent then targets its own AVD through the normal run script (headless
must match, or the emulator restarts with a window):

```bash
ANDROID_AVD_NAME=BeautifulQuran_API_35_1 ANDROID_EMULATOR_HEADLESS=1 \
  scripts/run_android_app.sh
```

`scripts/emulators_up.sh` prints that command for every AVD. Agent AVDs are
lean by default (2 GB RAM, 2 cores — override with `ANDROID_AVD_RAM` /
`ANDROID_AVD_CORES`); keep an eye on the memory warning it prints, since each
emulator needs ~1-2 GB of host RAM. Stop them all with
`scripts/emulators_down.sh`. A single interactive emulator can instead be
created heavier: `ANDROID_AVD_RAM=4096 ANDROID_AVD_CORES=6
scripts/setup_android_emulator.sh`.

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

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the app is put together: pipeline, sync engine, modules, conventions
- [docs/COMPLEXITY.md](docs/COMPLEXITY.md) — complexity rules, current hotspots, and the invariants a refactor must preserve
- [docs/DESIGN.md](docs/DESIGN.md) — the design language: the sheet, ink, color, type, motion
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md) — every smoothness technique in use and why
- [docs/REPEAT_HIGHLIGHTING.md](docs/REPEAT_HIGHLIGHTING.md) — the orange second fade for words a reciter repeats, and where the repeat-aware timing data comes from
- [docs/GLIMMER.md](docs/GLIMMER.md) — the Nightfall white-gold fresh-ink glimmer, repeat retriggering, halo rendering, tuning, and artifact checks
- [docs/ROOT_VIEWER.md](docs/ROOT_VIEWER.md) — hold-to-reveal root lexicon: counts, ayah concordance, jump-to-chapter
- [docs/TIMINGS_LAB.md](docs/TIMINGS_LAB.md) — in-app timing editor (developer mode)
- [docs/QF_CONTENT_SYNC.md](docs/QF_CONTENT_SYNC.md) — authenticated Quran Foundation Content API migration and offline-sync gate

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
