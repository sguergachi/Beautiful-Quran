# Beautiful Quran

A beautiful, simple Quran reader for Android and the web. Its signature feature is a
**word-by-word follow-along view**: as the reciter recites, each Arabic word
illuminates in time with the audio, with its English meaning beneath it.

- 📖 Full Quran, Uthmani script, in the KFGQPC Hafs typeface
- ✨ Word-by-word highlighting with bundled offline timing fallback
- 🎙️ 7 reciters, with ayah audio streamed and cached
- 🈯 Word-by-word English gloss + Saheeh International translation
- 🔁 Repeat one ayah, the whole surah, or any ayah range you choose
- 🔍 Search the English translation and word glosses within a surah
- 🌙 Warm paper light theme and near-black charcoal dark theme
- 📴 All text + quran-align timings work offline; repeat timings sync and cache
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
npm --prefix backend test     # Timing facade + transitional provider
```

Local debug and release APKs use the uncommitted `release.keystore` when it is
available, including from linked worktrees; otherwise contributor builds use
the ordinary debug key. The GitHub release never falls back: CI restores the
same keystore from repository secrets and verifies its certificate before
publishing. `RELEASE_KEYSTORE_FILE` can point at a key stored elsewhere.

Google Play's Internal App Sharing `.der` file is a public certificate, not a
private signing key. Play re-signs every uploaded APK with that certificate, so
it is neither committed nor used by Gradle. An APK installed from a Play
internal-sharing link must be uninstalled before switching to a directly
shared local/GitHub APK, because their Google-owned and developer-owned signing
keys intentionally differ.

### Send an APK to your phone (KDE Connect)

Paired phone + reachable KDE Connect (app open, same LAN or Bluetooth):

```bash
scripts/send_apk_to_phone.sh --label "what this build is"
```

That builds the APK, stages it under a name made from your label and the
commit — `Beautiful-Quran-what-this-build-is-a1b2c3d4-debug.apk` — shares it
to the first reachable device, and then **deletes the older staged builds**.
Accept the file on the phone.

Both halves matter. The phone never gets a generic `app-debug.apk`, because a
phone full of identically-named builds is one you cannot test from and KDE
Connect drops repeat sends of the same filename; and the previous builds go,
because at a quarter of a gigabyte each they fill `/tmp` and a full `/tmp`
truncates the next copy mid-send.

```bash
scripts/send_apk_to_phone.sh --release --label "chapter panel"
scripts/send_apk_to_phone.sh --skip-build --name "Pixel 10" --label "wash fix"
scripts/send_apk_to_phone.sh --wait          # poll up to 5 minutes
scripts/send_apk_to_phone.sh --keep-old      # keep the previous builds
kdeconnect-cli -a                            # list reachable phones
```

Staged builds live in `$TMPDIR/beautiful-quran-apks` (override with
`BQ_APK_STAGE`); cleanup only ever touches that directory.

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

`tools/build_db.py` downloads independently sourced Quran text, morphology,
and open quran-align timings and packs them into SQLite. The committed asset
contains no Quran.com-derived word, QCF, page-layout, or QDC timing values.
Android and web fetch word/QCF fields from the unauthenticated Quran.com API
and normalize them into a separate seven-day device cache. Repeat timings use
the normalized timing service only when configured. CI (GitHub Actions) runs unit
tests on every push; on `master`
it also assembles the release APK and publishes it to the rolling latest release.

Word/QCF download needs no build variable: released clients call
`https://api.quran.com` automatically, refresh after six days, and withhold the
cache after seven. A missing/expired first fill completes on the closed-mushaf
loading screen, with offline failure falling through to the independent reader.
`TIMING_CONTENT_BASE_URL` (Android) / its Vite equivalent is
only for the normalized repeat-timing service; leave it unset until that HTTPS
host passes the deployment and parity gates. quran-align remains the timing
fallback without a blocking network path.

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
- [backend/README.md](backend/README.md) — stable timing facade, deployment controls, and authenticated provider seam

## Data & attribution

| Content | Source | License |
|---|---|---|
| Uthmani text + Saheeh Intl. translation | [quran-json](https://github.com/risan/quran-json) (Tanzil / Al Quran Cloud) | free with attribution |
| Word-by-word gloss + transliteration (runtime cache only) | Quran.com API | governed by provider terms/approval |
| Root / lemma / morphology | [Quranic Arabic Corpus](http://corpus.quran.com) v0.4 | free with attribution + link |
| Word timing segments | [cpfair/quran-align](https://github.com/cpfair/quran-align) | CC-BY 4.0 |
| Repeat-aware timing segments (runtime cache only) | [quran.com](https://quran.com) legacy `qdc` audio API | transitional permission and QF migration pending |
| Recitation audio | [everyayah.com](https://everyayah.com) | free; rights remain with reciters |
| Arabic typeface | KFGQPC HAFS Uthmanic Script, King Fahd Complex | free redistribution |
