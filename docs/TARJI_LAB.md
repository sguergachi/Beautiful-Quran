# Tarjīʿ Lab

A laboratory for the tarjīʿ (ترجيع) shimmer — the wet-gold glint that pulses
with the reciter's held-note reverberation. The detector
(`Tarji` in `playback/`) is live DSP on the tapped PCM with a dozen gates;
when a word's shimmer is wrong (too early, too late, wrong rate, missing,
flashing), this is where you make it right: **capture the word once, then
loop the *captured* audio while watching the waveform, the detector's
measured tarjīʿ sine, and the fitted ideal sine — every knob edit re-runs
the pure detector offline over the same capture, so each change is judged
instantly against the same sound, on the loop, with the ear.**

The Lab separates two jobs that used to be conflated:

- **Detector tuning** changes what the current algorithm believes.
- **Ear truth** records what the shimmer *should* do, independent of the
  algorithm: no shimmer, or the audible onset, every desired brightness
  crest, and the end. It is also an **auditionable target renderer**: the
  word and a target sine play the authored pulse against the captured voice.
  Crest spacing gives the target frequency while individual marks preserve
  changing cadence and visual phase.

An exported sample therefore contains both the detector settings and human
ground truth. That is the evidence needed to improve the algorithm without
overfitting one word by eye.

> **Entry is developer-only**, like the [Timings Lab](TIMINGS_LAB.md): in
> developer mode, long-press a word → **Tarjīʿ Lab** (also in Settings →
> Developer). Without dev mode, a long-press opens the Root Word Viewer.

## Why a loop of the capture?

The live detector hears the tap *before* the listener does and must be
delayed onto the ear clock; the Lab sidesteps all of that. It records the
exact decimated hop stream the detector eats (via
`VoiceEnergy.armCapture()`, the same tap, no microphone), trims it to the
word's span, and plays it on a **hardware-looped AudioTrack** in static mode.
The playhead, the plotted trace, and the preview shimmer are then in
lockstep *by construction* — the pulse you see is the pulse you hear, at
the true hop rate (44.1 kHz sources decimate to 7.35 kHz, not 8 kHz, and the
loop plays at the true rate so a 5 Hz swell is really 5 Hz).

## The canvas

One strip shows, over the word's span (bracketed in gold):

- the **waveform** (min/max per pixel column of the captured PCM),
- the **80 ms envelope** the detector's scan actually sees (dim primary),
- the **measured tarjīʿ sine** — `tremolo` per hop, gold, alpha riding the
  detection gain so the edges dry out instead of popping,
- the **fitted ideal sine** (dashed white): least-squares amplitude/phase at
  the detector's own mean rate over the reverberating span, so a glance
  tells you whether the pulse is clean and in phase,
- the **reverberating span** as a translucent gold band,
- the listener's **Ear truth** in primary ink: onset/end lines, a quiet span,
  a continuous target sine, and a short tick for every desired brightness
  crest,
- the **playhead**, and the word itself below, wearing the shimmer: its
  white-gold glow is driven per frame by the same
  `InkEngine.glintResonance` mapping the reader renders — crests brighten,
  troughs extinguish.

The compact readout under the canvas keeps time, fitted rate, and hop duration
on one line. Two aligned evidence lanes report **loudness (AM)** and **pitch
(FM)** rate, depth, and coherence; the lane currently supplying the visible
pulse takes primary ink. This makes a wrong frequency choice explainable
without squeezing the measurements into an ellipsized sentence.

**Preview my target** switches the glowing word from detector output to Ear
truth on the same hardware-loop playhead. Its target controls author the
experience rather than the detector: **Target rate** creates a regular cadence
around the last manually tapped crest (the phase anchor), while more crest taps
can describe a reciter who accelerates or slows; **Target depth**, **Trough
light**, **Build**, and **Dry** shape how strongly that pulse appears and how it
arrives and settles. **Preview detector** is the immediate A/B comparison.

## The knobs

The same eight detector knobs as the Ink Lab's Tarjīʿ section — **one source
of truth** (`TarjiLabKnobs` ↔ `InkEngine.Tuning`), so what you perfect here
is what ships and what the live detector runs. Every edit writes the Ink Lab
tuning (persisted, pushed to `VoiceEnergy`), then re-analyzes the capture
with a debounce. Plus the effect's **pulse depth**, which the preview honors.

## Workflow

1. Long-press a word → **Tarjīʿ Lab** (or Settings → Developer → Tarjīʿ Lab;
   the word-stepper ‹ › walks the ayah's words).
2. The Lab **captures automatically and muted** at 1× (300 ms lead, 1 s tail
   of the waqf decay). The percentage rail shows progress; capture always
   pauses and restores player gain at completion, failure, navigation, or
   exit, so a failed probe cannot leave the ayah playing. A failed automatic
   capture exposes a quiet **Retry muted** action.
3. Use **Play loop** / **Pause loop** to audition the captured word. The
   control is a true toggle: pause keeps the loop sample position and Play
    resumes from that same sample. Tap or drag **anywhere on the full
    waveform** to choose an exact point; the loop stays paused there until
    **Play loop** is pressed. There is no separate scrub control.
    The `time / duration` readout makes the target explicit. The glowing word
    preview lives in the top word row so the waveform and transport get the
    remaining space.
4. Under **Ear truth**, leave a negative word as **No shimmer**. For a real
   tarjīʿ, mark **start**, one audible **bright crest**, and **end**, then move
   **Target rate** until the target sine and glowing word ride the voice. The
   marked crest anchors phase. Tap further crests instead when cadence changes
   through the hold. Marks can be made while the loop plays; **Remove latest
   crest** and **Clear labels** repair mistakes.
5. Tap **Preview my target** and tune **depth**, **trough light**, **build**,
   and **dry** until the word shows the experience you want. Toggle back to
   **Preview detector** for A/B. Add a note when the distinction matters (for
   example, “follow pitch, not the room echo”).
6. Read the comparison line: positive onset/end errors mean the detector is
   late; the crest error measures phase alignment; the detector and Ear truth
   rates expose a frequency mismatch directly.
7. **Detector tuning** stays visible below the waveform so the values are
   always available while listening. Import, Export, and Reset are quiet
   header utilities rather than a transport-level “Tools” disclosure.
8. Watch the gold sine and listen to the loop. Too few pulses? Lower
   **Min depth** / **hold min**, widen **Max rate**. Glimmer flickers into
   the next word? Raise **Release ms**. The sine vs the fitted dashed ideal
   shows drift at a glance.
9. **Reset knobs** restores shipped defaults (also resets the Ink Lab).

## Samples — the exchange format

**Export** writes a JSON sample to
`Android/data/<app>/files/Download/tarji_<reciterId>_<surah>_<ayah>_w<word>.json`:
word metadata, the decimated PCM (Base64, 16-bit LE), the true hop duration,
the first hop's media position, the knobs used, the complete auditioned Ear
truth signal (phase anchor plus visual style), and listening notes. **Import**
loads such a sample (file picker) and re-analyzes it — knobs, labels, target
preview, and style included. Unlabeled schema-1 samples remain importable.

That file *is* the repro for any tarjīʿ issue: the waveform, sine, and fit
reproduce identically off-device. `tools/tarji_samples/` is where samples
land (see its README) when a better detector is derived from real
recitations.

## How the capture works (and its edges)

- `VoiceEnergy.armCapture()` starts recording on the first hop analyzed
  after the *next* tap-session reset, so a seek's flush cannot leak stale
  PCM of the old position; `disarmCapture()` returns
  `TarjiLabCapture` (hops + content timestamps, pure data).
- `TarjiLabTrim` locates the word's span on the media clock: first mark's
  start → last mark's end (repeats included), or the gap between neighbours
  for words without marks.
- Re-analysis (`analyzeTarjiCapture`) replays the captured hops through a
  fresh `Tarji` with `delayHops = 0` — the offline report is the live
  signal, with no ear-delay term.
- The loop is capped at 1 MB static buffer; a longer capture refuses to
  loop (a word never approaches it).
- The waveform cursor reads the static `AudioTrack` playback head, not a
  wall-clock estimate, so output scheduling and loop seams cannot make the
  displayed time drift away from the sound.
- Hardware loop points were reworked in API 37 (`setLoopPoints(start, end,
  loopCount)` with −1 = infinite); older platforms use the two-argument
  form via reflection, since the 37 stub no longer carries it.

## Files

```
app/src/main/java/com/beautifulquran/playback/TarjiLabCapture.kt   capture data + trim helpers
app/src/main/java/com/beautifulquran/playback/VoiceEnergy.kt       armCapture/disarmCapture sink
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabTrace.kt     offline re-analysis + sine fit
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabSample.kt    JSON sample codec
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabViewModel.kt capture/loop/knobs orchestration
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabScreen.kt    waveform + sine + preview UI
app/src/test/java/com/beautifulquran/tarjilab/                     trace, gating, codec tests
```

Unit tests synthesize held notes with known AM and assert the span, rate,
phase lock (tremolo leads the 80 ms envelope by two hops), knob gating, the
sine fit, the trims, and the JSON round trip — run
`./gradlew testDebugUnitTest` before committing changes here.
