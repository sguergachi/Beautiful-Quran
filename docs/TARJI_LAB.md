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
- the **playhead**, and the word itself below, wearing the shimmer: its
  white-gold glow is driven per frame by the same
  `InkEngine.glintResonance` mapping the reader renders — crests brighten,
  troughs extinguish.

The caption under the canvas shows capture length, hop duration, and the
fitted rate ("fit 5.0 Hz"); "analyzing…" flashes while a knob edit re-runs
the detector.

## The knobs

The same eight detector knobs as the Ink Lab's Tarjīʿ section — **one source
of truth** (`TarjiLabKnobs` ↔ `InkEngine.Tuning`), so what you perfect here
is what ships and what the live detector runs. Every edit writes the Ink Lab
tuning (persisted, pushed to `VoiceEnergy`), then re-analyzes the capture
with a debounce. Plus the effect's **pulse depth**, which the preview honors.

## Workflow

1. Long-press a word → **Tarjīʿ Lab** (or Settings → Developer → Tarjīʿ Lab;
   the word-stepper ‹ › walks the ayah's words).
2. **Capture word** — the word plays once through the shared player at 1×
   (300 ms lead, 1 s tail of the waqf decay), and the tap records the hop
   stream; capture stops at the word's end automatically and the loop
   starts. While it runs, the action becomes **Cancel capture** and shows a
   percentage rail, so buffering or a long word never looks frozen; a failed
   capture can be retried from the same control.
3. Use **Play loop** / **Pause loop** to audition the captured word. The
   control is a true toggle: pause keeps the loop sample position and Play
   resumes from that same sample. Tap or drag **anywhere on the full
   waveform** to jump to an exact point; there is no separate scrub control.
   The `time / duration` readout makes the target explicit. The glowing word
   preview lives in the top word row so the waveform and controls get the
   remaining space. Capture and loop controls sit directly below the waveform,
   where they stay beside the timing evidence they control.
4. **Detector tuning** stays visible below the waveform so the values are
   always available while listening. **Tools** contains the less-frequent
   Reset, Export, and Import actions, keeping the primary workflow quiet.
5. Watch the gold sine and listen to the loop. Too few pulses? Lower
   **Min depth** / **hold min**, widen **Max rate**. Glimmer flickers into
   the next word? Raise **Release ms**. The sine vs the fitted dashed ideal
   shows drift at a glance.
6. **Reset knobs** restores shipped defaults (also resets the Ink Lab).

## Samples — the exchange format

**Export** writes a JSON sample to
`Android/data/<app>/files/Download/tarji_<reciterId>_<surah>_<ayah>_w<word>.json`:
word metadata, the decimated PCM (Base64, 16-bit LE), the true hop duration,
the first hop's media position, and the knobs used. **Import** loads such a
sample (file picker) and re-analyzes it — knobs included.

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
