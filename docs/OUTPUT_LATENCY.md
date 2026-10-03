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
| `OutputLatency` (pure policy) | **Yes** — manual wall lag → media lag; route presets for PCM |
| `AudioOutputLatency` (Android) | Watch devices for the raw PCM tarjīʿ delay only |
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

## Raw PCM presets

| Route | When | Offset |
|---|---|---|
| Local | Phone speaker, wired, USB | **0 ms** |
| Bluetooth LE | BLE headset / speaker / broadcast among outputs | **80 ms** |
| Bluetooth A2DP | Classic A2DP or hearing-aid among outputs | **180 ms** |

If several outputs are listed at once (common: built-in speaker **and** A2DP
headset connected), **higher-latency wins** so a connected headset is not
ignored.

These presets only delay the PCM-tapped tarjīʿ signal, which has not passed
through Media3's presentation clock. Its sink-buffer correction remains separate.
They never delay word selection, ayah fade, or basmalah wash automatically.

Manual output lag is **additional** on both paths: the word clock subtracts
`manualLagMs × speed`, while the PCM tap uses `routePresetMs + manualLagMs`
in wall time before its existing content-hop conversion. Manual 0 is identical
to Auto; a manual adjustment shifts the words and shimmer by the same amount.

## Route detection

`playback/AudioOutputLatency` (app-lifetime, from `QuranApp`):

1. Reads `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`.
2. Maps each `AudioDeviceInfo.type` to an `OutputLatency.OutputKind`
   (A2DP / LE / local; unknown types ignored).
3. `OutputLatency.classify` → preset ms.
4. `AudioDeviceCallback` refreshes on add/remove so mid-surah connect /
   disconnect updates the raw PCM offset.

Classification is “BT device present among outputs,” not a full active-route
graph. That matches the usual “headphones connected → media goes there” case
and stays thin.

## Reader wiring

In `ReaderViewModel`:

- Normal word polls: `highlightPositionMs(firstWordStartMs, reciterId)` →
  `OutputLatency.highlightMs(player.positionMs, manualLagMs × speed, highlightLeadMs)`.
  Automatic lag is zero; the raw PCM route preset is not fed to this clock.
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
- Not codec fingerprinting (SBC/aptX/LDAC) — high complexity, weak gain over
  the A2DP/LE split.

## Files

| File | Role |
|---|---|
| `domain/OutputLatency.kt` | Pure kinds, PCM presets, `mediaLagMs`, `heardMs` |
| `domain/OutputLatencyTest.kt` | Spec for classify + heard clamp |
| `domain/ReciterSync.kt` | Pure reciter-specific word-clock calibration |
| `playback/AudioOutputLatency.kt` | Android device watch → `StateFlow` latency |
| `ui/reader/ReaderViewModel.kt` | Applies heard clock on the poll path |
| `web/src/domain/OutputLatency.ts` | Same pure presets + `highlightMs` (Vitest twin) |
| `web/src/store/appStore.ts` | Applies lead (+ LOCAL lag) on the poll path |

## Tuning

Change the raw PCM presets in `OutputLatency` only after ear-checking speaker **and**
at least one classic A2DP pair. Prefer small integer presets; do not push
device-specific tables into the engine.
