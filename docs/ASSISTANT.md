# Android voice and Assistant support

Status snapshot: **2026-07-16**

Beautiful Quran exposes actions to Android so a system assistant can control the
app hands-free. It intentionally has **no microphone button, speech recognizer,
or in-app Assistant UI**. The Android integration landed in PRs
[#409](https://github.com/sguergachi/Beautiful-Quran-/pull/409) and
[#410](https://github.com/sguergachi/Beautiful-Quran-/pull/410).

The important architectural fact is that there are three independent routes:

1. **Media session and catalog** for playback and transport commands.
2. **App Actions** for classic Google Assistant navigation and custom actions.
3. **AppFunctions** for Gemini and other authorized Android agents.

Finishing the code for one route does not register the other two. Android also
does not offer an intent filter with which an app can claim arbitrary speech.
Google selects an app before Beautiful Quran receives a query; if that selection
fails, the request becomes a web search or is routed to another media app.

## Current capability matrix

| User request | Android route implemented | Sideloaded APK | Requirement for system voice |
|---|---|---|---|
| Pause, resume, next, previous while Beautiful Quran is the active session | Media3 `MediaSession` | Works through Android media controls | Assistant must select the active media session |
| Play any chapter or verse | Searchable `MediaLibraryService`, `MEDIA_PLAY_FROM_SEARCH`, App Action, and global AppFunction | Direct Android invocation works; named Assistant routing is not guaranteed | Media-app routing or an App Actions preview/release; Gemini requires AppFunctions access |
| Open any chapter or verse without playing | Deep link, App Action, and foreground AppFunction | Deep link works | App Actions preview/review for classic Assistant; AppFunctions access for Gemini |
| Bookmark an explicit chapter and verse | Global AppFunction | Direct AppFunction invocation works | Gemini AppFunctions access |
| “Bookmark this” in the active reader | App Action and activity-scoped AppFunction | Direct deep link works | Classic Assistant App Action or Gemini activity-scoped AppFunctions access |
| Repeat a range, select a reciter, change speed, or configure the reader | Global AppFunctions | Direct AppFunction invocation works | Gemini AppFunctions access |

“Implemented” in this table means Android can discover or invoke the app-side
hook. It does not mean the consumer version of Assistant or Gemini is currently
allowed to call it.

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

### Classic Google Assistant App Actions

[`shortcuts.xml`](../app/src/main/res/xml/shortcuts.xml) declares:

- `actions.intent.GET_THING` for verse and chapter lookup.
- `actions.intent.OPEN_APP_FEATURE` for continue, bookmarks, and save bookmark.
- `custom.actions.intent.OPEN_CHAPTER` for any chapter and optional verse.
- `custom.actions.intent.PLAY_CHAPTER` for any chapter and optional verse.
- `custom.actions.intent.BOOKMARK_VERSE` for the current or last-read verse.

The custom query patterns live in
[`arrays.xml`](../app/src/main/res/values/arrays.xml). They are generalized over
numeric chapter and verse parameters; they are not hard-coded to chapter 2.
Assistant fulfills a match with an Android deep link parsed by
[`AssistantAction.kt`](../app/src/main/java/com/beautifulquran/assistant/AssistantAction.kt).

App Actions are not activated by installing a GitHub APK. During development,
the Google Assistant plugin creates an account-specific preview. Production
availability requires the app to be published through Google Play and its App
Actions to be reviewed and deployed. Until one of those registrations exists,
phrases such as “open chapter 2 on Beautiful Quran” can legitimately become
search results because the app never receives the request.

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

## Classic Assistant: development preview checklist

Use classic Google Assistant for this path, not Gemini.

1. Upload the exact `com.beautifulquran` package to Play Console. A draft or
   test release is sufficient for preview work; `scripts/build_release_bundle.sh`
   creates and verifies the signed AAB.
2. Accept the App Actions terms under the app's Play Console advanced settings.
3. Use the same Google account in Play Console, Android Studio, and the Google
   app on the test device. Finish Assistant setup and enable device data sync.
4. Install the latest compatible Google Assistant plugin in Android Studio.
5. Install the app on the test device from Android Studio. Its application ID
   must exactly match the package uploaded to Play.
6. Open **Tools > Google Assistant > App Actions test tool**. Create a preview
   using invocation name **Beautiful Quran** and locale **en-US**. The custom
   intents currently support only `en-US`, so the device and Assistant language
   must match.
7. Trigger each capability from the test tool first. Then test the documented
   voice phrases on the same signed-in device.
8. Update the preview whenever `shortcuts.xml` or its inventories change. The
   `OPEN_APP_FEATURE` inline inventory preview expires after six hours.

For broader pre-review testing, use an internal or closed Play track. Testers
who join Google's App Actions Development Program can access unapproved actions
in those tracks without creating individual previews; Google documents up to a
three-hour propagation delay.

Minimum voice acceptance tests:

- “Open chapter 3 on Beautiful Quran.”
- “Open chapter 2 verse 255 on Beautiful Quran.”
- “Play chapter 3 on Beautiful Quran.”
- “Play chapter 2 verse 255 on Beautiful Quran.”
- “Bookmark this on Beautiful Quran” while the reader is foreground.
- “Open bookmarks on Beautiful Quran.”

For production, create a Play release, request App Actions review, and wait for
the App Actions deployment. Publishing the APK/AAB alone is not the same as
having the voice capabilities approved.

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
| Assistant shows web results or opens YouTube | No matching App Actions preview/review, wrong invocation name/locale/account, or media-provider selection failed; Beautiful Quran probably received no intent |
| Direct deep link works, but the same voice phrase does not | Google's Assistant registration/routing, not the app parser |
| App opens, but on the wrong chapter | Inspect the fulfilled intent/deep link and parsed parameters in Logcat |
| Active-session pause works, but named cold-start play does not | Media transport is working; cold-start provider selection is not |
| Gemini says it cannot perform the action | The Gemini build/account is not enabled for the AppFunctions preview, or it did not select the registered function |
| `adb` lists no AppFunctions | Wrong build/device/API, generated metadata missing, or package not reinstalled after the AppFunctions change |
| “Bookmark this” chooses the last-read verse | The request used a cold-start fallback instead of the foreground activity-scoped function |

When recording a test result, include the commit, build type/signing source,
device/API build, Google app and Gemini versions, Assistant mode, Google account,
language, App Actions preview age, exact phrase, and whether Beautiful Quran
received an intent. That separates an app defect from an account-side rollout.

## Primary references

- [Android AppFunctions overview](https://developer.android.com/ai/appfunctions)
- [Android AppFunctions sample and test agent](https://github.com/android/appfunctions)
- [Android AppFunctions development skill](https://github.com/android/skills/tree/main/device-ai%2Fappfunctions)
- [Build App Actions](https://developer.android.com/develop/devices/assistant/get-started)
- [Google Assistant plugin and App Actions test tool](https://developer.android.com/develop/devices/assistant/test-tool)
- [Custom App Actions intents](https://developer.android.com/develop/devices/assistant/custom-intents)
- [App Actions troubleshooting](https://developer.android.com/develop/devices/assistant/troubleshoot)
- [Serve content with Media3 `MediaLibraryService`](https://developer.android.com/media/media3/session/serve-content)
