# Tarjīʿ Lab

The developer workbench for **live algorithm tuning**: loop a real captured
word, change this reciter's detector parameters, and hear and see the result.
The word and scope always show the detector's measured output. There is no
manual envelope, vibrato label, or look control that can override it.

## Workflow

1. In developer mode, long-press a word → **Tarjīʿ Lab**, or open it from
   Settings → Developer. The ‹ › arrows choose another word in the ayah.
2. The lab captures the word muted at 1×, including 300 ms lead and 1 s tail.
   Capture progress and failures appear on the page; **Retry** repeats capture.
3. Press **Play word** to repeat the capture. **Choose loop** reveals the
   range controls: drag the two ends, then press **Play loop**. **Whole word**
   returns to the full capture. Tapping the waveform pauses at that position.
   **Rewind** returns to the current playback range start and preserves pause/play.
   Pinch to zoom, two-finger pan to move, and **Fit** to restore the whole view.
4. Listen at 1×, ½, or ¼. The quiet waveform is the audio; the **gold pulse**
   overlaid on it is the actual output used by the word’s glow. The vertical
   line shows playback position. There is no raw teal candidate trace or
   diagnostic dot in the user-facing graph. The readout says **Pulse here** or
   **No pulse here** at the current position, and **Updating pulse** during analysis.
5. Use the three main controls. If audible wavering is missed, increase
   **Sensitivity** or lower **Shortest note**. If normal speech pulses, reverse
   those adjustments. **Rhythm tolerance** accepts less evenly repeating
   wavering as it increases. Each slider has a direction guide and short help.
   Changes save automatically for the selected reciter; **Reset** restores defaults.
6. **More controls** reveals pulse frequency limits, tolerated note slides,
   fade-in, and brief-gap bridging, plus sample import/export and optional notes.
   These remain collapsed until requested.

The gold curve uses the recorded audio’s time axis and a fixed −1..1 scale.
It shows accepted modulation after attack/release gain. Rejected regions have
no gold curve; no synthetic oscillator or hand-drawn curve invents a pulse.
The pre-gate candidate remains available to analyzer tests and corpus audits,
but is not a tuning target in this interface.

## Detector controls

Profiles persist per reciter and write through to `InkEngine.tuning`:

- **Sensitivity** reverses the modulation-depth threshold: 0% = depth 0.25,
  100% = depth 0.01. More sensitivity accepts subtler wavering.
- **Shortest note** is the minimum stable-note duration, 100–1,200 ms.
- **Rhythm tolerance** reverses periodicity: 0% = threshold 0.85,
  100% = threshold 0.15. More tolerance accepts uneven wavering.
- Under **More controls**: **Slowest / Fastest pulse** set the frequency band;
  **Allow note slides** sets pitch drift; **Fade in** sets attack;
  **Bridge gaps** sets release. Frequency endpoints stay ordered.

The percentages are normalized slider positions, not confidence scores.
Re-analysis updates the graph and word without restarting audio or moving the
chosen loop. Release bridges brief detection gaps, while the real end of a
hold still uses its own fast decay.

Each edit replays the same PCM through the same pure `Tarji` implementation
used by the live audio tap. Background analysis is canceled on a new target,
import, or exit; a result can publish only for its original capture and knobs.
A loop replays the captured detector history, including its lead-in, so every
pass compares the same acoustic evidence rather than warming up at the loop
boundary. This is tuning feedback, not synthesis or waveform authoring.

## Playback and capture contracts

- Cold entry prepares the player before arming the probe. Capture waits for
  the target seek, clamps the tail to the actual clip duration, and restores
  playback speed and repeat mode when finished or canceled.
- `PlayerController.pause()` clears play intent even while buffering.
- Capture arm, append, and snapshot share a lock; ordinary reader playback
  skips that lock. A fresh arm cannot return PCM from the preceding capture.
- Trimming advances `firstHopMediaMs` to the first retained hop. It denotes
  that hop's **end**, matching `VoiceEnergy`'s content timestamps.
- Preview uses a static `AudioTrack`, with the effective PCM rate derived
  from hop timestamps. `setLoopPoints(start, end, -1)` is available since API 3.
- `TarjiPreviewClock` rebases the unsigned frames-played counter onto the
  selected starting frame and wraps it inside the loop. All speeds use this
  clock; no wall-clock estimate drives slow playback. Paused scrubbing owns
  its cursor and cannot be overwritten by an old hardware counter.
- Unsupported playback speeds report failure rather than silently changing
  pitch. Exiting or backgrounding the lab cancels pending work and stops audio.
- Captures remain capped at 12 seconds; static preview buffers at 1 MB.

The hardware playback head does not measure physical Bluetooth/acoustic
latency. Device-route latency can still affect perceived alignment; verify
fine timing on the intended listening device.

## Samples and compatibility

Export writes JSON under the app's external `files/Download/` directory,
with reciter, ayah, word, decimated PCM (16-bit LE Base64), hop duration,
media origin, loop range, detector parameters, and notes.

Schema 2 and 3 samples still import. Their hold window becomes the loop range;
legacy labels, crests, and drawn envelopes never drive the preview. Imported
samples disable word stepping because their neighboring audio is not loaded.
Keep reproducible captures in `tools/tarji_samples/`.

## Verification

`./gradlew testDebugUnitTest` covers detector replay, sample round trips,
range manipulation, capture trimming, and the preview clock (nonzero starts,
seek rebasing, unsigned rollover, looping, and stalled playback).

On device, check cold Settings entry, repeated word changes during capture,
loop/whole-capture playback, paused seeking, all three speeds, knob edits while
playing, import, and exit/background during capture and preview.

## Real-reciter pulse audit (2026-09-29)

`tools/tarji_samples/pulse_audit_2026-09-29.csv` records a check of all 13
catalog reciters, using the last word of 1:7 and 44:59 (26 actual EveryAyah
recordings). Each clip was cut from the committed timing span with the lab's
300 ms lead and 1 s tail, decoded to 8 kHz mono PCM, and replayed through
`analyzeTarjiCapture`. No audio fixtures are shipped with the app.

All 26 had nonzero measured modulation. Changing from shipped defaults to
hold=100 ms, depth=0.01, regularity=0.15, pitch drift=0.30, attack=50 ms changed
the accepted output in 25 clips, covering every reciter. The 0.5-second
AbdulBaset Mujawwad 44:59 clip exposed a candidate but no accepted output with
either setting. This checks graph visibility and tuning response, not detection
accuracy for every word or device-level audio latency.
