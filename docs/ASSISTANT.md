# Android voice and Assistant support

Status snapshot: **2026-09-10**

Beautiful Quran exposes actions to Android so a system assistant can control the
app hands-free. It intentionally has **no microphone button, speech recognizer,
or in-app Assistant UI**. The Android integration landed in PRs
[#409](https://github.com/sguergachi/Beautiful-Quran-/pull/409) and
[#410](https://github.com/sguergachi/Beautiful-Quran-/pull/410).

There are two independent routes:

1. **Media session and catalog** for playback and transport commands.
2. **AppFunctions** for Gemini and other authorized Android agents.

Finishing the code for one route does not register the other. Android also does
not offer an intent filter with which an app can claim arbitrary speech. Google
selects an app before Beautiful Quran receives a query; if that selection fails,
the request becomes a web search or is routed to another media app.

There used to be a third route — **App Actions**, the `<capability>` elements in
`shortcuts.xml` that classic Google Assistant fulfilled as deep links. Google
removed Assistant from Android on **2026-09-04**, taking its voice entry point
with it, and published no path that converts those declarations into Gemini tool
calls. The declarations were removed on 2026-09-10 rather than left as metadata
nothing reads; the deep links they fulfilled are untouched and still the
app's spine. See "The removed App Actions route" below.

## Current capability matrix

| User request | Android route implemented | Sideloaded APK | Requirement for system voice |
|---|---|---|---|
| Pause, resume, next, previous while Beautiful Quran is the active session | Media3 `MediaSession` | Works through Android media controls | Gemini must select the active media session |
| Play any chapter or verse | Searchable `MediaLibraryService`, `MEDIA_PLAY_FROM_SEARCH`, and global AppFunction | Direct Android invocation works; named voice routing is not guaranteed | Media-app routing, or Gemini AppFunctions access |
| Open any chapter or verse without playing | Deep link and foreground AppFunction | Deep link works | Gemini AppFunctions access |
| Bookmark an explicit chapter and verse | Global AppFunction | Direct AppFunction invocation works | Gemini AppFunctions access |
| “Bookmark this” in the active reader | Activity-scoped AppFunction | Direct deep link works | Gemini activity-scoped AppFunctions access |
| Repeat a range, select a reciter, change speed, or configure the reader | Global AppFunctions | Direct AppFunction invocation works | Gemini AppFunctions access |

“Implemented” in this table means Android can discover or invoke the app-side
hook. It does not mean a consumer Gemini build is currently allowed to call it.

## What is implemented

### Media playback

[`PlaybackService.kt`](../app/src/main/java/com/beautifulquran/playback/PlaybackService.kt)
is a Media3 `MediaLibraryService` and advertises both the Media3 and platform
`MediaBrowserService` actions. It exposes all 114 surahs as browsable and
searchable media, expands a match into a full ayah queue, and uses the selected
reciter. The media session sets a `sessionActivity` `PendingIntent` so tapping
the notification or lock-screen player opens `MainActivity`. `MainActivity`
also accepts the legacy `android.media.action.MEDIA_PLAY_FROM_SEARCH` action.

This is the correct Android route for playback and transport commands. It does
not implement non-media requests such as “open without playing” or “bookmark
this.” Named cold-start requests can still go to YouTube Music when Assistant
does not identify Beautiful Quran as the intended media provider.

Media3 owns normal audio-focus behavior: spoken-content recitation pauses on a
transient Assistant interruption and resumes when focus returns. If an Assistant
build instead labels its brief speech as a permanent focus loss, Android sends
no later gain callback. `AssistantAudioResume` handles that narrow case by
matching the focus-loss pause to an active platform audio player with
`USAGE_ASSISTANT`, then resuming after that player goes quiet. User and remote
pauses remain paused.

### Deep links and launcher shortcuts

[`shortcuts.xml`](../app/src/main/res/xml/shortcuts.xml) declares two launcher
long-press shortcuts, Continue and Bookmarks, each an ordinary `ACTION_VIEW`
deep link. [`VoiceShortcuts.kt`](../app/src/main/java/com/beautifulquran/assistant/VoiceShortcuts.kt)
publishes the same set dynamically so they can be pinned to the home screen.

Every entry point in the app funnels into one parser,
[`AssistantAction.kt`](../app/src/main/java/com/beautifulquran/assistant/AssistantAction.kt):
custom-scheme deep links, the explicit `com.beautifulquran.action.*` intents,
system `ACTION_SEARCH`, `MEDIA_PLAY_FROM_SEARCH`, and the AppFunctions in
`assistant/`. That is deliberate — one grammar to test, and a new caller costs a
call site rather than a parser. None of it requires registration with Google;
it all works on a sideloaded APK.

### Gemini AppFunctions

[`QuranAppFunctions.kt`](../app/src/main/java/com/beautifulquran/assistant/QuranAppFunctions.kt)
registers global Android AppFunctions for:

- play, pause, resume, stop, next verse, and previous verse;
- any chapter and optional starting verse;
- any valid verse or inclusive repeat range;
- playback speed and reciter selection;
- explicit bookmark add and remove; and
- reader display preferences.

[`ForegroundAppFunctions.kt`](../app/src/main/java/com/beautifulquran/assistant/ForegroundAppFunctions.kt)
registers activity-scoped functions while `MainActivity` is visible. These open
any chapter or verse, continue reading, search, open bookmarks/chapters/settings,
and resolve “bookmark this” against the currently focused verse.

The AppFunctions compiler generates the static XML index and global service;
the manifest registers that service and the activity-scoped metadata. The
functions are visible in Android's on-device registry on Android 17.

This is the intended long-term Gemini integration, but it is not yet a normal
consumer integration. Google's official documentation says that, as of May
2026, Gemini invocation is a private preview for trusted testers. A regular
Gemini installation therefore may not discover or invoke these functions even
though Android's registry and direct test calls work.

## Deterministic developer tests

These tests prove the app-side contract independently from Google's speech and
cloud routing. Run them against the same build that will be previewed or
released.

### Deep links and media search

```bash
adb shell am start \
  -n com.beautifulquran/.MainActivity \
  -a android.intent.action.VIEW \
  -d 'beautifulquran://verse/2/255'

adb shell am start \
  -n com.beautifulquran/.MainActivity \
  -a android.intent.action.VIEW \
  -d 'beautifulquran://verse/3/1?play=true'

adb shell am start \
  -n com.beautifulquran/.MainActivity \
  -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
  --es query 'play chapter 3 from Beautiful Quran'

adb shell am start \
  -n com.beautifulquran/.MainActivity \
  -a android.intent.action.VIEW \
  -d 'beautifulquran://bookmark/save'
```

Deep links also support:

- `beautifulquran://continue` and `beautifulquran://continue?play=true`
  (last *listened* verse — `settings.lastSurah` / `lastAyah` update only when
  audio plays, not on open/scroll)
- `beautifulquran://bookmarks`
- `beautifulquran://verse/2/255` or
  `beautifulquran://verse?surah=2&ayah=255`
- any verse link with `?play=true`

### AppFunctions registry and execution

First inspect the installed functions and read the selected function's
description, parameters, required fields, and response schema. Do not guess its
input shape.

```bash
adb shell cmd app_function help
adb shell cmd app_function list-app-functions \
  --package com.beautifulquran > /tmp/beautiful-quran-appfunctions.json
```

After inspecting the `playChapter` entry, a direct global-function test is:

```bash
adb shell "cmd app_function execute-app-function \
  --package com.beautifulquran \
  --function 'com.beautifulquran.assistant.BaseQuranAppFunctionService#playChapter' \
  --parameters '{\"chapterNumber\":3,\"verseNumber\":1}' \
  --brief-yaml"
```

The expected result begins with
`androidAppfunctionsReturnValue: "Playing Ali 'Imran, chapter 3, from verse 1"`.
The outer quotes are significant: without them, the remote shell can strip the
JSON quotes before `app_function` reads the parameters.

The seven foreground functions use `scope=activity` and require the activity
registration/context supplied to an authorized agent. A context-free shell
execution is not equivalent to Gemini invoking a function for the visible
`MainActivity`.

Run the JVM tests as the final local regression check:

```bash
./gradlew testDebugUnitTest
```

`AssistantActionTest`, `VoiceRoutinesTest`, and `MediaIdTest` cover query parsing,
deep links, shortcuts, and media IDs. They cannot test Google's account-side
Assistant routing.

### Testing AppFunctions without preview access

`adb shell cmd app_function` proves the app answers, but it is not an agent: it
hands the function a hand-written parameter object and never asks whether a
model reading our KDoc would have picked that function at all. Google ships a
separate caller for that — the **AppFunctions Testing Agent**, in the
[`agent/`](https://github.com/android/appfunctions/tree/main/agent) directory of
the `android/appfunctions` samples repo. It is not gated on the Early Access
Program, so it is the only end-to-end check available to us today.

There are three levels of fidelity. Work up them; each one costs more and
catches a class the one below cannot.

| Level | What it proves | Cost |
|---|---|---|
| `adb shell cmd app_function` | The function exists, is indexed, accepts its schema, and returns | none |
| Testing Agent, `standard` flavor | A real caller process reaches us through `AppFunctionManager` with a privileged binding, not the shell | one build |
| Testing Agent, `retail` flavor | An LLM reading only our KDoc **chooses** the right function and fills its parameters from a natural-language phrase | one build + an API key |

The third level is the one worth running after any change to a function's
KDoc, name, or parameters, because those descriptions are the only thing a
model sees. Nothing local can test the step beyond it — whether Gemini routes
the user's phrase to Beautiful Quran at all — which stays behind the EAP.

Build and launch it against a device already carrying our build:

```bash
git clone https://github.com/android/appfunctions.git
cd appfunctions/agent
./run_privileged.sh --build                       # standard flavor, no key
./run_privileged.sh --build --flavor retail \
  --api-key "$GEMINI_API_KEY"                     # LLM flavor
```

The `retail` key is an ordinary Google AI Studio key. It buys the agent its own
model to drive the test; it grants no Gemini AppFunctions access and is not the
EAP gate.

`EXECUTE_APP_FUNCTIONS` is a privileged permission, so the agent cannot simply
request it. `run_privileged.sh` installs the APK and then hands off to
`startAppFunctionTestingAgent`, which launches it through
`am instrument -w …/.ShellIdentityInstrumentation` — the app runs under **shell
identity** via `UiAutomation` and inherits the shell's permissions. That needs
adb, not root, so it works on a physical Pixel exactly as it does on an
emulator. The agent's own `minSdk` is 36, matching the platform API.

On SDK 37 that script also tries to register itself as a caller with
`cmd allowlist add-package-with-metadata-multimap`. The standard
`android-37/google_apis/x86_64` emulator image has no `allowlist` service at
all — the call prints `cmd: Can't find service: allowlist`, the script's guard
skips it, and shell identity carries the run. That message is expected, not a
failed setup.

Both flavors are drivable by hand from the agent's UI, and both list every
package on the device, so use them to check our foreground functions too: put
the reader on a verse, then ask the agent to "bookmark this" and confirm it
resolves the *focused* ayah rather than the last-read one. That distinction is
the whole point of the seven `scope=activity` functions and `adb` cannot
observe it.

#### Emulator pitfall: `Broken pipe (32)` means "still booting"

Installing this APK before the emulator has finished booting fails with
`Failure calling service package: Broken pipe (32)`. On `cmd app_function` the
same message reads like an AppFunctions fault; it is not, and neither is it a
graphics or memory failure. **Always gate on `sys.boot_completed` before
installing**, and note that `adb wait-for-device` does *not* do this — it
returns as soon as adb registers the device, minutes before the framework is
ready:

```bash
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  sleep 5
done
adb install -r -t app/build/outputs/apk/release/app-release.apk
```

The message depends on APK size, which is what makes this hard to see: an
86MB APK gets the honest `Error: device is still booting`, while this app's
~260MB APK gets only the broken pipe. Verified on 2026-09-10 by installing
both into the same half-booted emulator.

Two other faults were seen the same day. Neither explains a *pre-boot* broken
pipe, so check the boot gate first, but both are real and the second one is
what finally defeated this verification:

- Host memory exhaustion. Several emulators plus a Gradle build fill 31GB, and
  the Kotlin daemon dies (`Using fallback strategy: Compile without Kotlin
  daemon`). Check `free -g` and `ps -o etime,rss -p $(pgrep -d, -f qemu-system)`
  for emulators left running by earlier sessions.
- **`surfaceflinger` dies under the weight of the install.** On a *clean* AVD,
  with `sys.boot_completed=1` and the host otherwise idle, installing the
  ~260MB APK still took the framework down: `SurfaceControl.nativeCreate` →
  `DEAD_OBJECT` → SystemUI crash → `system_server` restart, after which
  `cmd package` reports `Can't find service: package`. Snapshot/renderer
  mismatches produce related aborts (`Change of GLES renderer detected`, or
  `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma` under
  `-gpu swiftshader_indirect`).

**Do not verify AppFunctions registration on an emulator.** Six attempts across
four AVD/renderer configurations on 2026-09-10 never got this APK installed on
`android-37.0/google_apis/x86_64`; the graphics stack cannot survive an install
this size. Use a physical Android 17 device, where the same check is one command
and a few seconds — and which is the only place a real Gemini can be tested
anyway.

An AVD that never reaches `sys.boot_completed=1`, or floods the log with
`Failed to find ColorBuffer`, is corrupt — force-killing the emulator does that.
Build a throwaway AVD from the same image rather than debugging it:

```bash
avdmanager create avd -n BQ_API37_verify \
  -k "system-images;android-37.0;google_apis;x86_64" -d pixel_7
```

A fresh AVD had its `package` service up in 45 seconds and logged zero
ColorBuffer errors, where the reused one never came up at all.

Use `scripts/run_android_app.sh` rather than a hand-rolled `emulator` command.
It resolves `DISPLAY` and `XAUTHORITY`, probes the host Vulkan driver, and
falls back to software rendering only when the host GPU is genuinely unusable.

Its one trap is that the display work is skipped under
`ANDROID_EMULATOR_HEADLESS=1` while `-gpu host` is still selected, so on a
machine with a working GPU but no `DISPLAY` the renderer never starts
(`Failed to get EGL display` / `Could not start renderer`) and the emulator
never registers with adb. Run it windowed, or set `DISPLAY` yourself:

```bash
ANDROID_API=37 ANDROID_AVD_NAME=BeautifulQuran_API_37 scripts/run_android_app.sh
```

A killed emulator can also leave `multiinstance.lock` held in the `.avd`
directory, and the next launch dies with `Another emulator instance is
running`. Check `pgrep -af qemu-system.*API_37` for the orphan before blaming
the lock file.

### Verification record

The Android 17 emulator used for the 2026-07-16 snapshot confirmed:

- Android registered 20 Beautiful Quran AppFunctions: 13 global and 7
  activity-scoped.
- Direct execution of global `playChapter` started chapter 3, verse 1.
- Direct `MEDIA_PLAY_FROM_SEARCH` execution selected the requested chapter and
  built its complete ayah queue in Beautiful Quran's active media session.
- Deep-link navigation and bookmark fulfillment reached `MainActivity`.

These checks prove the installed APK and Android OS hooks. They do **not** prove
that a Google account is enrolled for Gemini AppFunctions or has an active App
Actions preview. The observed search/YouTube fallbacks occurred before the app
received an Android intent.

## The removed App Actions route

Kept as a record so the route is not rebuilt by someone reading an older guide.

Until 2026-09-10 `shortcuts.xml` also declared five App Actions capabilities —
`actions.intent.GET_THING`, `actions.intent.OPEN_APP_FEATURE`, and the custom
`OPEN_CHAPTER`, `PLAY_CHAPTER`, and `BOOKMARK_VERSE` intents — with their query
patterns and feature synonyms in `res/values/arrays.xml`. Classic Google
Assistant matched a spoken phrase against those patterns and fulfilled it with a
`beautifulquran://` deep link.

That route never carried a request in production. It required either an
account-scoped preview from the Android Studio Assistant plugin or a reviewed
App Actions deployment on a published Play release, and Beautiful Quran had
neither. Google then began removing Assistant from Android on **2026-09-03**,
completing it on **2026-09-04**, which deleted the voice entry point the
capabilities existed to serve. Google published no mechanism that converts App
Actions declarations into Gemini tool calls; the documented successor is
AppFunctions, which this app already implements independently.

So the declarations were deleted: the `<capability>` elements, the
`<capability-binding>` blocks, the two placeholder shortcuts that existed only
to anchor those bindings, all of `arrays.xml`, and their four strings. Nothing
else moved. The deep links, the explicit intent actions, the launcher
shortcuts, `AssistantAction.kt`, the media session, and all 20 AppFunctions are
unchanged, because none of them ever depended on Assistant.

Recreate this route only if Google ships a system voice surface that consumes
`<capability>` metadata again. `git show <commit>^:app/src/main/res/xml/shortcuts.xml`
has the full prior declaration.

## Gemini: full-support checklist

1. Keep the app targeting Android 17/API 37 and update the experimental
   AppFunctions dependency/compiler together when Google publishes a compatible
   release.
2. Confirm the installed release lists all 20 functions: 13 global functions
   and 7 activity-scoped functions.
3. Obtain Google's trusted-tester/private-preview access for Gemini
   AppFunctions. The public documentation does not currently describe a general
   enrollment flow, and app code cannot bypass this gate.
4. Test discovery and invocation from Gemini itself, not only with `adb` or the
   AppFunctions test agent.
5. Verify global actions from a cold start and activity-scoped actions while the
   reader is visible. In particular, confirm that “this” resolves to the focused
   ayah rather than merely the last-read ayah.
6. Repeat these tests after every Android 17 beta, Gemini, Google app, or
   AppFunctions library update; the API remains experimental.

Full Gemini support is complete only when an enabled Gemini build discovers and
invokes the app's functions. Until Google grants that access or makes the
integration generally available, the repository can be implementation-complete
without consumer Gemini voice support being available.

## Diagnosing failures

| Symptom | Likely boundary |
|---|---|
| Gemini shows web results or opens YouTube | Media-provider selection failed, or Gemini never chose this app; Beautiful Quran probably received no intent |
| Direct deep link works, but the same voice phrase does not | Google's routing and app selection, not the app parser |
| App opens, but on the wrong chapter | Inspect the fulfilled intent/deep link and parsed parameters in Logcat |
| Active-session pause works, but named cold-start play does not | Media transport is working; cold-start provider selection is not |
| Gemini says it cannot perform the action | The Gemini build/account is not enabled for the AppFunctions preview, or it did not select the registered function |
| `adb` lists no AppFunctions | Wrong build/device/API, generated metadata missing, or package not reinstalled after the AppFunctions change |
| “Bookmark this” chooses the last-read verse | The request used a cold-start fallback instead of the foreground activity-scoped function |

When recording a test result, include the commit, build type/signing source,
device/API build, Google app and Gemini versions, Google account, language,
exact phrase, and whether Beautiful Quran received an intent. That separates an
app defect from an account-side rollout.

## Primary references

- [Android AppFunctions overview](https://developer.android.com/ai/appfunctions)
- [Add the AppFunctions API to your app](https://developer.android.com/ai/appfunctions/add-appfunctions)
- [Android AppFunctions sample and test agent](https://github.com/android/appfunctions)
- [Android AppFunctions development skill](https://github.com/android/skills/tree/main/device-ai%2Fappfunctions)
- [AppFunctions Early Access Program form](https://forms.gle/GN5ybjQFhzHRCguM7)
- [Serve content with Media3 `MediaLibraryService`](https://developer.android.com/media/media3/session/serve-content)
