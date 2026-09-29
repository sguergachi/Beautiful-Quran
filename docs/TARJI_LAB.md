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
3. The transport is a single row of 48 dp icon targets: **Rewind**, a larger
   **Play/Pause**, **Loop**, **Speed**, and **Fit**. Play repeats the whole word.
   Loop isolates a selection with large visible drag handles; toggling Loop
   during playback switches the range without requiring a second Play tap.
   Speed cycles 1× → ½ → ¼. Tapping the waveform pauses at that position;
   pinch zoom and two-finger pan preserve precise inspection.
4. The graph stays pinned while controls scroll. A time ruler, playback line,
   and loop range show where the audible note is. The quiet waveform is audio;
   the **gold pulse** is the accepted output used by the word’s glow. There is
   no raw teal trace. The readout says **Pulse here**, **No pulse here**, or
   **Updating**; it never invents an accepted pulse.
5. Adjust **Sensitivity**, **Shortest note**, or **Rhythm tolerance**. Each
   slider has a thin track, current value, direction labels, and short guidance.
   **− / +** make precise nudges: 1 percentage point on the normalized controls,
   10 ms on durations, 0.1 Hz on rate limits, and 0.01 on note-slide tolerance.
   Changes save automatically for this reciter. Graph and glow update while
   dragging; audio continues on the same clock.
6. **Compare** switches the graph, glow, and displayed knob values between
   **Live tuning** and one **Reference**, without changing audio, position, or
   saved settings. The initial reference is the first completed analysis for
   this capture; **Set ref** replaces it with the current completed tuning.
   Reference mode disables knob edits; tap Compare to return. Reference and
   history clear on a new word/import, so captures cannot be compared across
   different timelines.
7. **Undo / Redo** reverse tuning edits without restarting playback. One full
   slider drag is one undo step; each nudge is another. History retains 32 edits
   for the current word. **Reset** restores shipped defaults and is undoable.
   **Fine tuning** reveals frequency limits, note slides, fade-in, and gap
   bridging, with sample import/export and optional notes below.

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
import, or exit; a result can publish only for its original capture.
During a drag, one worker coalesces edits into the latest replay instead of
restarting a 120 ms trailing debounce on every pointer event. Completed intermediate replays update the curve during a drag; it stays
marked Updating until the worker catches the current knobs. A single ordered
worker cannot replace a newer result with an older one. Reference creation is disabled while analysis is pending.
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
range manipulation, grouped undo/redo, non-mutating comparison, capture trimming, and the preview clock (nonzero starts,
seek rebasing, unsigned rollover, looping, and stalled playback).

On device, check cold Settings entry, repeated word changes during capture,
loop/whole-capture playback, paused seeking, all three speeds, knob edits while
playing, Compare/Set ref, undo/redo after a drag and after Reset, import, and
exit/background during capture and preview. Device verification remains
pending where the emulator System UI fails before the app can be inspected.

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

## Interface references

The design borrows waveform/selection proximity from
[Logic Pro’s Quick Sampler](https://www.apple.com/logic-pro/) and precise
parameter feedback from [FabFilter’s knob controls](https://www.fabfilter.com/help/one/using/knobsandswitches).
The adaptation stays within the app’s paper language: flat ink, no floating
panels or shadows, familiar icons with short captions, and brief press motion.
Only the gold result is plotted. The loop and comparison controls serve a
single listen → isolate → tweak → compare cycle; there is no preset browser,
new synthesis engine, or additional framework.
