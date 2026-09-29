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
3. Play the whole capture, or choose **Loop range**, drag the gold handles,
   and play that range. **Seek** lets a tap or drag pause at a precise sample.
   **Rewind** returns to the active loop start (or the whole capture start),
   keeping playback running if it was running, or paused if it was paused.
   Pinch to zoom, two-finger pan to move, and **Fit** to restore the whole view.
4. Listen at 1×, ½, or ¼ while adjusting the always-visible detector controls.
   Re-analysis updates the scope and word without restarting audio or moving
   the chosen loop. The readout says when analysis is updating.
5. **Reset** restores shipped detector defaults for this reciter. **Export**
   saves the audio, loop, knobs, and optional note; **Import** replays a sample.

The scope has two labeled lanes: **Recorded voice** above and **Tuned pulse**
below. The pulse is signed detector modulation multiplied by its attack/release
gain, on a fixed −1..1 scale with zero at the center line. Its scale never
auto-expands weak results, so changes from the knobs stay visible. A flat pulse
means no modulation passed the detector; tuning does not change the recording. The gold rail marks each detected hold, including gaps between
holds. The cursor, detector readout, and word all share one content position.
The readout shows hold state, modulation rate, and gain.

## Detector controls

Profiles persist per reciter and write through to `InkEngine.tuning`:

- **Hold min**: minimum stable-note duration.
- **Wobble min / max Hz**: permitted modulation band. Editing either endpoint
  keeps the band ordered.
- **Min depth**: minimum modulation depth.
- **Regularity**: periodicity threshold.
- **Pitch wander**: tolerated fundamental drift.
- **Attack / Release**: detector gate response.

Every knob includes a persistent explanation of what lowering or raising it
does. Start by lowering Min depth or Regularity if an audible hold is missed;
raise them or Hold min if ordinary speech triggers the detector. Then tune
Attack for the entrance and Release for brief detection gaps. Release does
not prolong the true end of a hold, which has its own fast decay.

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
