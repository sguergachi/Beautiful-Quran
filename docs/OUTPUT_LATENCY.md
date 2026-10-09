# Output latency (Bluetooth karaoke sync)

**Status: Android uses Media3’s output-corrected presentation clock; web uses
its media clock. No automatic extra Bluetooth lag is subtracted.**
An explicit Ink Lab lag is an additional wall-time correction, converted to
media time at the current playback speed before `HighlightClock` sees it.
Web ports the pure
`OutputLatency` helpers and feeds `highlightMs(..., leadMs)` into
`HighlightClock` (default lead 0). Browser output-route classification is
still hard, so web lag stays at LOCAL (0 ms) until a monitor exists.

## Why

Media3 1.10.1 derives audio position from AudioTrack presentation timestamps.
When timestamps are unavailable, its playback-head fallback subtracts reported
mixer/hardware latency. This is already a presentation clock, not the decoder
position. Subtracting another 180 ms for A2DP or 80 ms for LE can compensate
Bluetooth twice and make word ink late. The old connected-device heuristic
also delayed ink on the phone speaker while a Bluetooth device remained paired.

Use the player's clock directly. Android does not promise exact physical ear
latency on every headset; the existing manual lab adjustment remains available
for residual error. Do not guess an additional delay from device presence.

Sources: [Media3 1.10.1 position tracker](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java),
[Android AudioTrack timestamps](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)).

## Design rule

| Layer | Owns latency? |
|---|---|
| `HighlightEngine` | **No** — stays pure: segments + time *t* → word |
| Word timing segments / DB | **No** — lag is a device path, not reciter data |
| `OutputLatency` (pure policy) | Manual wall lag → media lag |
| `AudioOutputRoutes` (Android) | Watch the AudioTrack's actual routed device; refresh stale output state |
| `VoiceEnergy` / `TarjiEarClock` | Map source PCM onto the same presentation clock as the words |
| `ReaderViewModel` poll | `heardMs = positionMs − manualLagMs × speed` before `HighlightClock` |

```
ExoPlayer.currentPosition
        │
        ▼
OutputLatency.mediaLagMs      (0 automatically; manual wall lag × speed)
        │
        ├──► OutputLatency.heardMs(...)      media − lag
        │         └──► ayah fade lead + basmalah wash
        │
        └──► OutputLatency.highlightMs(...)  media − lag + word lead
                  └──► HighlightClock.sample(...)
                           └──► PreparedTimings.activeInfo(...)
```

## One heard position, two clocks

`ReaderViewModel.heardPositionMs()` is the latency-corrected position shared by
every follow-along surface. Word ink may additionally run ahead of that position;
the other consumers must not:

| Consumer | Clock |
|---|---|
| Word ink (`activeWord`) | Heard position; optional Ink Lab + reciter calibration lead after word 1 starts |
| Ayah fade lead (`ayahWithFadeLead`) | Heard position + its own `fadeLeadMs` |
| Basmalah calligraphy wash | Heard position |

Only the ink poll additionally arms `HighlightClock.acceptNextSample()` when the
applied manual media lag or word lead steps, so the change is taken as a real
jump instead of held as jitter. The pure heard-position reader cannot consume that latch on the
ink poll's behalf.

**Continue Listening reads neither clock.** It persists the *playing media
item*, because the fade-led ayah names the next verse before a note of it is
heard — persisting that recorded verses the listener never reached.

**Highlight lead** (Ink Lab → Highlight, default 0; persists with other lab numbers) advances the
query time only when explicitly tuned, so each word’s wash can start *before*
its segment `startMs`. It is
the opposite direction of output lag: lag delays ink to match late audio; lead
runs ink ahead of the timing table. That early budget also raises the short-hold
sweep floor (`minSweepMs + highlightLeadMs`) so small words and wasl tails can
breathe longer instead of racing. It does not move the ayah handoff or basmalah
wash. During encoded silence before word 1, the lead is gated off completely;
after the first segment starts it **ramps** from 0 to full lead over the first
`leadMs` of voiced audio so engagement stays continuous with silence. A hard
`+lead` cliff at the gate was larger than `HighlightClock`'s post-handoff settle
step and froze the clock through short word 1 (both words lit when settle ended
on word 2). Neither lag nor lead is baked into `HighlightEngine`.

**Reciter calibration.** `ReciterSync` adds a 200 ms word-only lead for Yasser
Al-Dosari, whose source-aligned wash was ear-verified that far behind the voice.
It uses the same opening ramp as the Ink Lab lead, so short first words remain
visible instead of being skipped. Timing rows, ayah handoff, basmalah wash, and
every other reciter stay on their existing clocks. Android and web share the
same policy.

## PCM on the presentation clock

`VoiceTapAudioProcessor` wraps the sink to retain the first input buffer's
presentation timestamp after each flush and sample `getCurrentPositionUs` on
the audio thread. Both timestamps use Media3's renderer timeline. Subtracting
the source origin gives the content position currently presented;
`TarjiEarClock` matches it to the player's media-item position at the same
wall time. Each display frame reads the per-hop history at that position.
Decoded bursts and AudioTrack buffer capacity never establish the origin.

The sink position already accounts for Bluetooth output and playback-speed
processing. No route preset or second Sonic correction is added. Capacity is
kept only in diagnostics. Pause and buffering hold the clock and close the
light; queued audio keeps its history alive after the last PCM feed. Internal
processor flushes for speed or reusable gapless playback retain that history;
actual sink flushes and format changes start a fresh source session.
The wrapper publishes play/pause state with its timestamps, so an optimistic
controller resume cannot extrapolate an old snapshot through a long pause.

Manual output lag is **additional** on both paths: words, pulse, and graph
cursor subtract `manualLagMs × speed`. Tarjīʿ's **Ear delay ms** similarly
delays its pulse and cursor. Event-start timestamps retain their source time
so adjusting delay cannot hand an acoustic event to another word. The paint
filter's phase compensation advances only its input, not the graph cursor.

## Route detection

`playback/AudioOutputRoutes` (app-lifetime, from `QuranApp`) observes the
service's AudioTrack through `AudioRouting.OnRoutingChangedListener` and
publishes `routedDevice.id`. A connected but inactive headset does not change
the route. Distinct devices are tracked even when they share a device type.
An unknown route and the initial discovery do not restart playback.

`PlaybackService` watches subsequent device-ID changes for its entire lifetime,
including while paused or with the reader closed. A real route change stops
and prepares an already-loaded player. Media3
releases the old AudioTrack and its timestamp/latency state, then buffers the
same media item at the same position with the existing play/pause intent.
Bluetooth disconnect still pauses through Media3's noisy-output handling;
the refresh does not call play or seek. Idle and completed playlists stay idle.
This can briefly rebuffer an actively playing route switch. It adds no word-lag
estimate: highlighting continues to use the fresh presentation clock directly.

The old `AudioManager.getDevices` scan listed connected outputs, which cannot
identify the track's chosen route. The replacement uses Android's actual
[routed-device API](https://developer.android.com/reference/android/media/AudioRouting#getRoutedDevice()).

## Reader wiring

In `ReaderViewModel`:

- Normal word polls: `highlightPositionMs(firstWordStartMs, reciterId)` →
  `OutputLatency.highlightMs(player.positionMs, manualLagMs × speed, highlightLeadMs)`.
  Automatic extra lag is zero.
- **Forced word seeks** (tap-to-play): keep the **media** timeline target so
  ink jumps to the sought word immediately; do not re-delay a deliberate seek.
- On a **manual media-lag change**, call `HighlightClock.acceptNextSample()` so the
  correction jump is not held as sampling jitter.
- Ayah fade and basmalah preface wash use `heardPositionMs()` so they stay with
  the voice on BT without inheriting the word-only lead.

Focus follow rides `activeAyah` / `activeWord` and needs no separate lag
logic.

Timings Lab still uses the raw playhead (developer editor; reaction
compensation is separate — see [TIMINGS_LAB.md](TIMINGS_LAB.md)).

## What this is not

- Not FocusEngine scroll pacing.
- Not tajweed letter pacing ([TAJWEED_PACING.md](TAJWEED_PACING.md)).
- No new user-facing sync slider; residual correction stays in Ink Lab.
- No codec fingerprinting or device-specific delay table; the output clock
  supplies the route correction.

## Files

| File | Role |
|---|---|
| `domain/OutputLatency.kt` | Pure `mediaLagMs`, `heardMs`, `highlightMs` |
| `domain/OutputLatencyTest.kt` | Manual wall/media conversion + heard clamp |
| `domain/ReciterSync.kt` | Pure reciter-specific word-clock calibration |
| `playback/AudioOutputRoutes.kt` | AudioTrack routed device → `StateFlow` device ID |
| `playback/VoiceTapAudioProcessor.kt` | PCM source PTS + sink presentation timestamps |
| `playback/TarjiSyncClock.kt` | Shared frame-time pulse and graph clock |
| `playback/AudioRouteRefresh.kt` | Service-lifetime route changes → fresh output clock at the same place |
| `playback/AudioRouteRefreshTest.kt` | Disconnect/reconnect commands, idle protection, collector lifetime |
| `ui/reader/ReaderViewModel.kt` | Applies heard clock on the poll path |
| `web/src/domain/OutputLatency.ts` | Same pure presets + `highlightMs` (Vitest twin) |
| `web/src/store/appStore.ts` | Applies lead (+ LOCAL lag) on the poll path |

## Tuning

Check speaker and the intended Bluetooth headset. Use the manual wall-time
trim for residual physical output error; do not replace presentation timestamps
with connected-device presets or buffer-capacity guesses. JVM regressions
cover burst startup, manual trims, pause, seek, gapless handoff, actual route
changes, and phase compensation at different speeds. These checks establish
software clock alignment; physical acoustic latency still needs a device check.
