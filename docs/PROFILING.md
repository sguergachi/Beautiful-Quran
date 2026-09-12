# Profiling and precompilation

Beautiful Quran uses two complementary systems:

- **Baseline and Startup Profiles** optimize release installs. ART precompiles
  critical methods, while R8 uses the startup subset to improve DEX layout.
- **Android 17 `ProfilingManager`** captures local Perfetto traces in debug
  builds. It is development instrumentation, not analytics, and no trace is
  uploaded.

## What ships

`app/src/main/baseline-prof.txt` is the committed Baseline Profile seed. It
covers the application and first sheet, reader/focus/ink hot paths, database
load, and playback startup. `app/src/main/generated/baselineProfiles/` holds the
narrow startup-only DEX layout profile.

`androidx.profileinstaller` installs the packaged profile for the rolling GitHub
APK as well as distribution paths where the store does not compile it during
installation. A release APK must contain:

```text
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
```

Verify this with:

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | rg dexopt
```

For a local release-like precompiled install, force ProfileInstaller to copy
the embedded profile and ask ART to merge and compile it:

```bash
adb shell am broadcast \
  -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n com.beautifulquran/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile --force-merge-profile -f -m speed-profile \
  com.beautifulquran
adb shell dumpsys package com.beautifulquran | rg -A5 "Dexopt state"
```

The final command should report `status=speed-profile`. This applies to the
release APK: the normal debuggable APK intentionally has no packaged Baseline
Profile and runs with JIT so development traces retain debug fidelity. Use the
Macrobenchmark module for comparative precompiled measurements.

## Regenerate on stable hardware

The `:baselineprofile` module has two deliberately separate profile-generation
journeys:

- `startup` contributes to both Baseline and Startup Profiles;
- `readerAndPaperNavigation` contributes only to the broader Baseline Profile.
  It chooses **Mushaf + Arabic + Mishary Rashid Alafasy** explicitly, waits for the
  mushaf reader (`Chapters` plus `Mushaf page N, …`), and plays Al-Fatihah
  from a **pre-cached Alafasy fixture with network off**. It does not stream.

This keeps reader and scrolling code from bloating the primary DEX. Connect a
stable API 33+ device, preferably the Android 17 physical device used for
release performance checks.

### Offline Alafasy fixture (required for recitation)

Recitation in both profile generation and `longAyahRecitation` is offline.
The harness does **not** download chapters. Prepare the listen/keep cache once
while online, then freeze the network:

1. On the physical device, Settings → **Mishary Rashid Alafasy**.
2. Play **Al-Fatihah** through (profile generation).
3. Open **2:282** in Scroll and play that verse through
   (`longAyahRecitation`). Do **not** tap Download all / Download chapter.
4. Turn Wi-Fi and mobile data off before running. The harness leaves device
   connectivity settings alone; the argument below acknowledges this preparation.
5. Pass the acknowledgement argument — without it recitation tests fail closed:

```bash
-Pandroid.testInstrumentationRunnerArguments.offlineRecitationFixture=true
```

Physical runs of these journeys are **still pending**. Do not invent frame
times, and do not regenerate `baseline-prof.txt` from an emulator or from a
run that mixed network buffering into recitation.

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.offlineRecitationFixture=true
```

The app plugin uses `mergeIntoMain` and `saveInSrc`, so successful generated
rules land under `app/src/main/generated/baselineProfiles/` and should be
reviewed and committed. Replace the conservative seed only after comparing the
generated profile against it on the same **physical** device.

### Reader interaction Macrobenchmarks

`ReaderBenchmark` measures frame timing. Every iteration `killProcess`s, then
sets **layout + Arabic view + Mishary Rashid Alafasy**. Scroll is ready on `Back`
(there is no `Chapters` control). Mushaf is ready on `Chapters` plus
`Mushaf page N, …`. Settings is ready on **Reciter**, not the Customize title.

| Test | Layout | Setup ready | Measured |
|---|---|---|---|
| `longAyahRecitation` | Scroll | `2:282` playing (`Pause` = `isPlaying`), network off | ~2 s karaoke frames |
| `mushafPageTurn` | Mushaf | page description present | swipe; assert page N changes |
| `distantDialJump` | Mushaf | page description present | dial swipe; assert the visible page moves at least 10 pages |
| `coldSearch` | Scroll | cover already open, process-cold index | focused field, type `peace` → `In the Quran`; auxiliary latency log |

`StartupTimingMetric` is **not** used for search — it would measure process
start, not query-to-results. The duration is an auxiliary
`BeautifulQuranBenchmark` log line (`SystemClock.elapsedRealtime`), not a
shipped metric and not a phone claim until a physical run exists. It includes
UI Automator input/detection overhead. Focusing the field happens in setup and
warms the compact concept asset, but the full word index remains cold.

Page turns and dial jumps size the swipe from the display (no 400 px
constants). They never click a guessed pixel for the return roundel.

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.offlineRecitationFixture=true
```

Limitations, on purpose:

- Physical hardware only. Emulator timings measure the host.
- Recitation fails without the fixture argument and without Alafasy 1:1–1:7
  and 2:282 already on disk.
- These tests do **not** rewrite `baseline-prof.txt`.
- Debug `DevProfiling` spans are debug-only; release benchmarks cannot use
  them as `TraceSectionMetric`. Search latency is the log line above.

Cold-start-only (no recitation fixture):

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.class=com.beautifulquran.baselineprofile.StartupBenchmark
```

Benchmark a release-like build on physical hardware. Emulator timings measure
the host and are not release evidence.

### Comparing two revisions with `gfxinfo`

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dumpsys gfxinfo com.beautifulquran reset
# Perform repeated cover ↔ reader and reader ↔ settings turns.
adb shell dumpsys gfxinfo com.beautifulquran > gfxinfo.txt
```

Hold everything constant across both revisions: same device, refresh rate,
thermal state, release APK, navigation path, and gesture duration. `gfxinfo`
confirms frame timing but cannot attribute Compose work — capture a Perfetto
system trace with frame timeline (and Compose tracing where available) when you
need to know *which* work missed the budget.

Never attach a numerical frame claim collected from this repository's headless
emulator. Its renderer terminates mid-gesture (both `swiftshader_indirect` and
`swiftshader` have exited with status 139 after rendering, and the host backend
cannot create an EGL display in a headless session), which yields `No process
found` rather than a measurement. Emulator-renderer instability is not an app
regression, and a fabricated before/after must not enter the performance record.

## Debug ProfilingManager workflow

`DevProfiling` has source-set-specific implementations:

- `src/debug` uses Jetpack `SystemTraceRequestBuilder` +
  `androidx.core.os.requestProfiling` (API 35+) for manual traces, and
  registers Android 17 cold-start / fully-drawn triggers (API 37+);
- `src/release` is a no-op, so release builds register nothing and collect
  nothing.

On Android 17 debug builds, `TRIGGER_TYPE_COLD_START` starts a system trace as
early as the process allows and keeps it until `Activity.reportFullyDrawn()`
(the entrance ceremony calls this when the cover finishes opening) or a
5-second default timeout. `TRIGGER_TYPE_APP_FULLY_DRAWN` captures the moment
fully-drawn is reported. Trigger capture depends on the system background
trace being active — enable the testing helpers below for local work.

Ceremony milestones also emit `BeautifulQuranProfile` log lines and atrace
sections (`coverChrome`, `coverOrnament`, `coverReady`, `warmStack`, …) so a
Perfetto UI search finds the handoff points quickly. Debug-only synchronous
`DevProfiling.trace` spans can attribute cold start, search, and English
pagination on a debug APK. They must not wrap suspend points
(`beginSection` is thread-local). Release `DevProfiling` stays a no-op, so
macrobenchmarks cannot read those names as `TraceSectionMetric`. Use a debug
trace to decide whether the measure-first items in
[PERFORMANCE.md](PERFORMANCE.md) are real; do not change ink rendering or
defer `QuranApp` initialization without that evidence.

For an explicit local trace request:

1. Install and run the debug APK on API 35+ (API 37 for cold-start triggers).
2. Open Settings and tap the app mark three times to enable developer mode.
3. In Developer, tap **Record 10-second system trace**.
4. Exercise the interaction of interest during those ten seconds.
5. Read the result path from logcat:

```bash
adb logcat -s BeautifulQuranProfile
```

Pull the returned `resultFilePath` with `adb pull` and open it in
[Perfetto](https://ui.perfetto.dev/). For repeated local requests, disable
rate limiting and keep temporary results:

```bash
adb shell device_config put profiling_testing rate_limiter.disabled true
adb shell device_config put profiling_testing delete_temporary_results.disabled true
# API 37 cold-start triggers also need a running background trace:
adb shell device_config put profiling_testing \
  system_triggered_profiling.testing_package_name com.beautifulquran
```

Restore after the session:

```bash
adb shell device_config delete profiling_testing rate_limiter.disabled
adb shell device_config delete profiling_testing delete_temporary_results.disabled
adb shell device_config delete profiling_testing system_triggered_profiling.testing_package_name
```

Profiles remain on the test device until removed. They must never be committed
because they can contain detailed execution data.
