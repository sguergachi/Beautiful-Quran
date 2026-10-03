# Tarjīʿ Lab

The developer workbench for **live algorithm tuning**: loop a real captured
word, change this reciter's detector parameters, and hear and see the result.
The word and scope always show the detector's measured output. There is no
manual envelope or synthesized pulse. Glint brightness scales the visual effect
without changing the measured pulse or detector acceptance.
The preview text and glow turn toward full white on accepted pulse crests,
then return to white-gold as the pulse falls, matching the reader's crest hue.

## Workflow

1. In developer mode, long-press a word → **Tarjīʿ Lab**, or open it from
   Settings → Developer. The ‹ › arrows choose another word in the ayah.
2. The lab captures the word muted at 1×, including 300 ms lead and 1 s tail.
   Capture progress and failures appear on the page; **Retry** repeats capture.
3. The transport is a single row of 48 dp icon targets: **Rewind**, a larger
   **Play/Pause**, **Loop**, **Speed**, **Fit**, and **Help**. Play repeats the whole word.
   Loop isolates a selection with large visible drag handles; toggling Loop
   during playback switches the range without requiring a second Play tap.
   Speed cycles 1× → ½ → ¼. Tapping the waveform pauses at that position;
   pinch zoom and two-finger pan preserve precise inspection.
4. The graph stays pinned while controls scroll. The playback line, loop range,
   and compact elapsed/total clock show where the audible note is. The quiet waveform is audio;
   the **green pulse** is the detector’s accepted output used by the preview glow. There is
   no raw teal trace. The readout shows the accepted
   **Volume / Pitch** channel and its rate in Hz, **Pulse fading**, **No pulse
   here**, or **Updating**; it never invents an accepted pulse.
5. Adjust **Glint brightness**, **Sensitivity**, **Shortest note**,
   **Rhythm tolerance**, or **Pulse speed**. Each
   slider has a thin track, current value, and precise nudge buttons. The **?**
   button beside Fit reveals knob explanations and gesture help;
   guidance is hidden by default to leave room for tuning. Help stays pinned
   beside transport when the explanations expand.
   **− / +** make precise nudges: 1 percentage point on the normalized controls,
   10 ms on durations, 0.1 Hz on rate limits, and 0.01 on note-slide tolerance.
   The wand beside **Pulse speed** is **Match this section**: it measures the
   selected loop, or the whole word when Loop is off, and suggests a speed
   range. It changes only Pulse speed and is one undo step; playback continues.
   If the section is too short or unclear, select a longer, steady portion.
   **Glint brightness** responds immediately without re-analyzing audio;
   its nudges move by 2 percentage points.
   Changes save automatically for this reciter. Graph and glow update while
   dragging; audio continues on the same clock.
6. **Compare** switches the graph, glow, and displayed knob values between
   live tuning and one **Reference**, without changing audio, position, or
   saved settings. The initial reference is the first completed analysis for
   this capture; the ribbon-shaped **Set ref** icon replaces it with the current
   completed tuning and confirms **Reference saved**. It glows gold when the
   live settings match the saved reference. This is a comparison checkpoint
   for the current capture, not a reader bookmark.
   Reference mode disables knob edits; tap Compare to return. Reference and
   history clear on a new word/import, so captures cannot be compared across
   different timelines.
7. **Undo / Redo** reverse tuning edits without restarting playback. One full
   slider drag is one undo step; each nudge is another. History retains 32 edits
   for the current word. **Reset** restores shipped defaults and is undoable.
   **Fine tuning** reveals note slides, fade-in, and gap
   bridging, with sample import/export and optional notes below.

The normal layout has two 48 dp rows below the graph: transport, then clock /
pulse status alongside Compare, Set reference, Undo, and Redo. Familiar controls
use icons with accessible names instead of stacked captions. There is no
permanent legend, gesture caption, time-ruler row, or duplicate Fit action.
Capture errors and sample messages take a row only when present and wrap
instead of disappearing past the edge. Fine tuning and file actions retain
48 dp hit targets; directional action icons mirror with the layout direction. Main controls
show only their name, value, slider, and − / + until help is requested.

The green curve uses the recorded audio’s time axis and a fixed −1..1 scale.
It shows accepted modulation after attack/release gain. Rejected regions have
no green curve; no synthetic oscillator or hand-drawn curve invents a pulse.
The pre-gate candidate remains available to analyzer tests and corpus audits,
but is not a tuning target in this interface.

## Tuning controls

Profiles persist per reciter and write through to `InkEngine.tuning`:

Applying a profile enables the same full-depth pulse shown in the lab, while
preserving the wash settings. Live acoustic eligibility remains independent
of the visual tajweed-pacing toggle, including in English reading modes.

The supplied `tarji_7_1_7_w9.json` tuning is installed once for **Hani Ar-Rifai**
(app reciter id 7): 200% brightness, 1.6–8 Hz, 656.6863 ms minimum hold,
0.13819668 minimum depth, 0.38481894 periodicity, 0.3 pitch drift, 50 ms
attack, and 1095.3691 ms release. This replaces any older Hani profile once;
later lab edits persist normally. Other stored reciter profiles are preserved.

- **Glint brightness** scales the tint and halo together from 0–200%.
  0% removes the sheen, 100% preserves the shipped look, and 200% brightens it
  up to the renderer’s opacity limit and scales the crest's blend toward white.
  It affects the lab glow and reader glint
  (including the repeat glimmer). It leaves the green trace, detection, pulse
  timing, wash, and halo size unchanged. Compare, undo/redo, reset, and sample
  export include brightness. Old samples/profiles default to 100%.

- **Sensitivity** reverses the modulation-depth threshold: 0% = depth 0.25,
  100% = depth 0.01. More sensitivity accepts subtler wavering.
- **Shortest note** is the minimum stable-note duration, 100–1,200 ms.
- **Rhythm tolerance** reverses periodicity: 0% = threshold 0.85,
  100% = threshold 0.15. More tolerance accepts uneven wavering.
- **Pulse speed** selects the accepted wavering band, 1.5–10 Hz, using two
  handles on one scale in 0.1 Hz steps. Left is slower, right faster; a wider
  band includes more rates. This tunes pulse cycles per second, not the
  reciter’s fundamental pitch or a room-echo frequency. The existing detector
  measures both loudness tremolo and pitch vibrato; the readout identifies
  whichever drives the accepted event. This replaces separate advanced rate
  limits and persists through the same reciter profile, comparison, and history.
- Under **More controls**: **Allow note slides** sets pitch drift; **Fade in**
  sets attack; **Bridge gaps** sets release.

Detector percentages are normalized slider positions, not confidence scores.
Brightness is a multiplier of the visual glint.
Re-analysis updates the graph and word without restarting audio or moving the
chosen loop. Release bridges brief detection gaps, while the real end of a
hold still uses its own fast decay.

Each detector edit replays the same PCM through the same pure `Tarji` implementation
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

## Matching a selected section

Matching slices only complete PCM hops inside the current loop. It replays the
existing detector over its full 1.5–10 Hz band with a temporary sensitivity
floor and shipped regularity gate; pitch/voicing checks remain intact. This
probe is never drawn, saved as a profile, or used for the reader glow.

The matcher counts positive crossings of the coherent, measured modulation
wave with hysteresis. This avoids using the broad-band autocorrelation's
sometimes slower multiple of the true cycle. At least two agreeing cycles,
300 ms of support, and agreement over 75% of measured cycle duration are
required. Silence, brief sections, and conflicting rhythms produce no suggestion.
The median cycle rate gets a ±15% or ±0.5 Hz margin, rounded outward to 0.1 Hz
and bounded by the supported band. Sensitivity and the other knobs remain yours
to tune; matching estimates speed rather than guaranteeing acceptance.

Matching runs off the UI thread. Changing target, capture, knobs, loop,
comparison mode, or leaving the lab cancels the request. A successful match
uses the usual undo and re-analysis path without changing playback position.

## Samples and compatibility

Export opens Android’s save picker with a suggested JSON filename. Choose
Downloads or another document location; canceling writes nothing. The
displayed settings and capture are frozen at the Export tap so a later target or knob change cannot replace
it while the picker is open. File writing runs off the UI thread, and success
or failure appears on the lab’s message line. Import also reads off the UI
thread and reports unreadable documents on the page. No storage permission
is needed.
Leaving the lab, changing words, or starting another import cancels the pending
read; a late result or failure cannot change tuning, the target, or playback.
The JSON includes reciter, ayah, word, decimated PCM (16-bit LE Base64), hop
duration, media origin, loop range, displayed detector parameters, and notes.
Export in Compare mode saves the reference settings you are viewing without changing the live profile.
Imported captures retain their source reciter metadata when exported again.

Schema 2 and 3 samples still import. Their hold window becomes the loop range;
legacy labels, crests, and drawn envelopes never drive the preview. Imported
samples disable word stepping because their neighboring audio is not loaded.
Keep reproducible captures in `tools/tarji_samples/`.

## Verification

`./gradlew testDebugUnitTest` covers detector replay, sample round trips,
range manipulation, selected-section matching (volume/pitch, fast cycles,
conflicting rhythms, silence, and invalid selections), grouped undo/redo, non-mutating comparison, capture trimming, and the preview clock (nonzero starts,
seek rebasing, unsigned rollover, looping, and stalled playback).

On device, check cold Settings entry, repeated word changes during capture,
loop/whole-capture playback, paused seeking, all three speeds, knob edits while
playing, Match on a loop and on the whole word, undo after Match, Compare/Set ref, undo/redo after a drag and after Reset, import, and
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

## Match audit (2026-09-30)

`tools/tarji_samples/pulse_match_audit_2026-09-30.csv` replays the same 26
cached clips through the matcher with shipped pitch-drift settings. It tests
the whole word and every complete 2-second selection at 250 ms offsets.
Whole-word matching succeeds in 2 clips; 18 of 339 selected sections match,
covering 8 reciters. Other selections report unclear rather than installing a
potentially misleading range. This is a conservative suggestion tool: choose
a steady portion and adjust the speed range manually when it cannot match.
These counts verify execution and refusal behavior, not the perceptual accuracy
of the estimated rates. The short 0.5-second clip has no 2-second selection.

## Reader admission audit (2026-10-01)

`tools/tarji_samples/reader_fatiha_audit_2026-10-01.csv` decodes the cached
whole 1:7 recordings for all 13 reciters at 8 kHz and feeds the shipped detector
without resetting it at the final word. The canonical final-word span and
`TarjiWordGate` then count admitted hops. This checks the reader’s additional
event-ownership and one-event rules, which the lab’s detector trace does not
apply. It is a zero-delay offline check, not a device render or acoustic-sync
measurement, and does not use a phone’s customized tuning.

All 13 detect modulation during the final word; 12 admit at least one event.
Yasser’s detected event starts before the final-word boundary and is withheld;
several other voices have later events withheld after the first one settles.
Those guards remain in place: they prevent a preceding word’s pulse and room
or consonant tails from relighting the next word. A green lab trace alone does
not prove that the reader will admit every event. Theme eligibility, a strong
hold, the event gate, and the wash mask also affect what appears in the reader.

The shaped-reader wash mismatch found in this investigation is fixed separately:
tint and halo travel now use the paper-cover geometry, while the full blur area
remains masked. Regression coverage checks a mid-word feather in both directions
and several halo sizes. Device visual verification remains blocked by System UI.

## Interface references

The design borrows waveform/selection proximity from
[Logic Pro’s Quick Sampler](https://www.apple.com/logic-pro/) and precise
parameter feedback from [FabFilter’s knob controls](https://www.fabfilter.com/help/one/using/knobsandswitches).
The adaptation stays within the app’s paper language: flat ink, no floating
panels or shadows, familiar icons with accessible names, and brief press motion.
Only the green result is plotted. The loop and comparison controls serve a
single listen → isolate → tweak → compare cycle; there is no preset browser,
new synthesis engine, or additional framework.
