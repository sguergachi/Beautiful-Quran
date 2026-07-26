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

The `:baselineprofile` module has two deliberately separate journeys:

- `startup` contributes to both Baseline and Startup Profiles;
- `readerAndPaperNavigation` contributes only to the broader Baseline Profile.

This keeps reader and scrolling code from bloating the primary DEX. Connect a
stable API 33+ device, preferably the Android 17 physical device used for
release performance checks, then run:

```bash
./gradlew :app:generateBaselineProfile
```

The app plugin uses `mergeIntoMain` and `saveInSrc`, so successful generated
rules land under `app/src/main/generated/baselineProfiles/` and should be
reviewed and committed. Replace the conservative seed only after comparing the
generated profile against it on the same device.

Run the included cold-start Macrobenchmark with:

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroidx.benchmark.enabledRules=Macrobenchmark
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

- `src/debug` registers Android 17 triggers and can request a manual system
  trace;
- `src/release` is a no-op, so release builds register nothing and collect
  nothing.

On Android 17 debug builds, cold start, fully drawn, ANR, OOM, and excessive-CPU
kill triggers are registered with a one-hour per-trigger limit. Trigger capture
depends on the system background trace being active and is not guaranteed.

For an explicit local trace request:

1. Install and run the debug APK on Android 17.
2. Open Settings and tap the app mark three times to enable developer mode.
3. In Developer, tap **Record 10-second system trace**.
4. Exercise the interaction of interest during those ten seconds.
5. Read the result path from logcat:

```bash
adb logcat -s BeautifulQuranProfile
```

Pull the returned `resultFilePath` with `adb pull` and open it in
[Perfetto](https://ui.perfetto.dev/). For repeated local requests, Android's
profiling rate limiter can be disabled temporarily:

```bash
adb shell device_config put profiling_testing rate_limiter.disabled true
```

Restore it after the session:

```bash
adb shell device_config delete profiling_testing rate_limiter.disabled
```

Profiles remain on the test device until removed. They must never be committed
because they can contain detailed execution data.
