# Output latency (Bluetooth karaoke sync)

**Status: implemented on Android.** The reader subtracts a small,
route-based delay from the media playhead before the highlight clock and
`HighlightEngine` see it. Web does not apply this yet (browser output
latency is harder to classify).

## Why

`MediaController.currentPosition` / ExoPlayer’s playhead advance with the
decoder. Bluetooth A2DP (and similar wireless paths) deliver sound to the
ear **after** that playhead — often ~150–300 ms later. Without compensation
the ink lights early relative to the voice.

Android does **not** expose a reliable end-to-end “ms until the ear” for
media over A2DP. So we use **coarse route presets**, not a measured delay.

## Design rule

| Layer | Owns latency? |
|---|---|
| `HighlightEngine` | **No** — stays pure: segments + time *t* → word |
| Word timing segments / DB | **No** — lag is a device path, not reciter data |
| `OutputLatency` (pure presets) | **Yes** — classify route → ms |
| `AudioOutputLatency` (Android) | **Yes** — watch devices, expose current ms |
| `ReaderViewModel` poll | **Yes** — `heardMs = positionMs − latencyMs` before `HighlightClock` |

```
ExoPlayer.currentPosition
        │
        ▼
AudioOutputLatency.latencyMs   (0 / 80 / 180 from route)
        │
        ▼
OutputLatency.highlightMs(...)  media − lag + lead  (clamp ≥ 0)
        │                         lead = InkEngine.highlightLeadMs (lab; default 0)
        ▼
HighlightClock.sample(...)
        │
        ▼
PreparedTimings.activeInfo(...)   ← unchanged engine
```

## One ear clock, every follow-along surface

`ReaderViewModel.adjustedPositionMs()` is the single pure reader of that
adjusted position. **Anything that decides where the recitation is must go
through it, never `player.positionMs`.** Three consumers do:

| Consumer | Why it must be the ear clock |
|---|---|
| Word ink (`activeWord`) | Lights the word the listener is hearing |
| Ayah fade lead (`ayahWithFadeLead`) | Compared against a *segment* end time; on raw playhead the effective lead became `fadeLeadMs + latency` (680 ms instead of 500 ms on A2DP) and shifted whenever the audio route changed |
| Basmalah calligraphy wash | Shares the lead-in clip's clock with the ink |

Only the ink poll additionally arms `HighlightClock.acceptNextSample()` when the
route or lab value steps, so a latency change is taken as a real jump instead of
held as jitter. That latch is *not* part of `adjustedPositionMs`: a second
consumer must never be able to consume it on the ink poll's behalf.

**Continue Listening reads neither clock.** It persists the *playing media
item*, because the fade-led ayah names the next verse before a note of it is
heard — persisting that recorded verses the listener never reached.

**Highlight lead** (Ink Lab → Highlight, default 0; persists with other lab numbers) advances the
query time so each word’s wash can start *before* its segment `startMs`. It is
the opposite direction of output lag: lag delays ink to match late audio; lead
runs ink ahead of the timing table. Neither is baked into `HighlightEngine`.

## Presets

| Route | When | Offset |
|---|---|---|
| Local | Phone speaker, wired, USB | **0 ms** |
| Bluetooth LE | BLE headset / speaker / broadcast among outputs | **80 ms** |
| Bluetooth A2DP | Classic A2DP or hearing-aid among outputs | **180 ms** |

If several outputs are listed at once (common: built-in speaker **and** A2DP
headset connected), **higher-latency wins** so a connected headset is not
ignored.

These numbers are product defaults, not per-device science. They are meant to
land inside the same ~150 ms “feels in sync” window as the existing ±73 ms
timing data noise. Exact ear sync on every headset is not achievable without
a user nudge (not shipped).

## Route detection

`playback/AudioOutputLatency` (app-lifetime, from `QuranApp`):

1. Reads `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`.
2. Maps each `AudioDeviceInfo.type` to an `OutputLatency.OutputKind`
   (A2DP / LE / local; unknown types ignored).
3. `OutputLatency.classify` → preset ms.
4. `AudioDeviceCallback` refreshes on add/remove so mid-surah connect /
   disconnect updates the offset.

Classification is “BT device present among outputs,” not a full active-route
graph. That matches the usual “headphones connected → media goes there” case
and stays thin.

## Reader wiring

In `ReaderViewModel`:

- Normal polls: `highlightPositionMs(null)` → `OutputLatency.heardMs(player.positionMs, latency)`.
- **Forced word seeks** (tap-to-play): keep the **media** timeline target so
  ink jumps to the sought word immediately; do not re-delay a deliberate seek.
- On a **latency change**, call `HighlightClock.acceptNextSample()` so the
  ~preset jump is not held as sampling jitter.
- Basmalah preface wash uses the same heard clock so calligraphy stays with
  the lead-in voice on BT.

Focus follow rides `activeAyah` / `activeWord` and needs no separate lag
logic.

Timings Lab still uses the raw playhead (developer editor; reaction
compensation is separate — see [TIMINGS_LAB.md](TIMINGS_LAB.md)).

## What this is not

- Not FocusEngine scroll pacing.
- Not tajweed letter pacing ([TAJWEED_PACING.md](TAJWEED_PACING.md)).
- Not a user-facing “sync” slider (possible later if presets miss stubborn pairs).
- Not codec fingerprinting (SBC/aptX/LDAC) — high complexity, weak gain over
  the A2DP/LE split.

## Files

| File | Role |
|---|---|
| `domain/OutputLatency.kt` | Pure kinds, classify, presets, `heardMs` |
| `domain/OutputLatencyTest.kt` | Spec for classify + heard clamp |
| `playback/AudioOutputLatency.kt` | Android device watch → `StateFlow` latency |
| `ui/reader/ReaderViewModel.kt` | Applies heard clock on the poll path |

## Tuning

Change the constants in `OutputLatency` only after ear-checking speaker **and**
at least one classic A2DP pair. Prefer small integer presets; do not push
device-specific tables into the engine.
