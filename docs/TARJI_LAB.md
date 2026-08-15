# Tarjīʿ Lab

A laboratory for the tarjīʿ (ترجيع) shimmer — the wet-gold glint that
answers a reciter's **held-note vibrato**. That hold is climactic and
subtle. Each reciter has a signature: a different voice, room, and
recording chain. The lab is how you capture those moments and tune the
detector to *this* reciter.

It is not a sine-wave author. You do not pick a frequency and draw the
shimmer you wish you heard. You mark the hold on the real waveform,
optionally sculpt its envelope by hand, and tune the algorithm until it
agrees with your ear.

> **Entry is developer-only**, like the [Timings Lab](TIMINGS_LAB.md): in
> developer mode, long-press a word → **Tarjīʿ Lab** (also in Settings →
> Developer). Without dev mode, a long-press opens the Root Word Viewer.

## What you are tuning

Two jobs, kept apart:

- **The hold** is ground truth. Start and stop of the note, Has vibrato
  or Still, and an optional hand-shaped envelope. This is the signature
  sample.
- **This reciter's knobs** change what the current algorithm believes.
  They persist per reciter and write through to the live detector
  (`InkEngine.tuning`). Switching reciter loads that reciter's profile.

An export is therefore a labeled waveform plus the knobs that analyzed
it — the evidence needed to derive a better per-reciter detector later,
without overfitting one word by eye.

The waveform is the scope (Falcon-style): you watch it while you tinker.
The knobs are live (DialKit-style): every edit re-runs the pure detector
offline over the same capture.

## The canvas

One strip shows the word's span:

- the **waveform** (min/max per pixel of the captured PCM),
- the detector's **80 ms envelope** (quiet primary),
- your **hold window** as a gold band with draggable handles,
- a thinner gold rail where the **detector** currently hears a hold,
- your **hand-shaped envelope**, when you have drawn one,
- the **playhead**.

The lab is the waveform. Gold handles *are* the hold; the range is
printed on the canvas only while a handle moves. **Play** loops that
band; **Play whole word** loops the captured word. Both run at
1× / ½ / ¼. Pinch the waveform to zoom a section (Fit
returns to the whole capture). Speed and the two play controls sit on
the left of the transport; Listen / Hold / Shape sit as icon-only
ink-spot tools flush right, with empty paper between. They change what
the finger does on the canvas. Entering Shape draws the detector
envelope on the graph so there is a line to sculpt. A tap outside
the band does nothing until the finger moves.

The Arabic word is the proof, not a caption. It grows when **Tune** is
put away, and the waveform takes the leftover paper. Chrome below the
scope is reserved so capture progress, notes, and Reset never shove
the page. Reset sits under the graph, above the modes, right-aligned
in Shape, and puts the stroke back to what the current knobs hear.

Each tool owns what the graph shows:

- **Listen** — the voice and the playhead. One finger seeks.
- **Hold** — the gold band is yours. Drag the handles. Tap the band:
  bright gold is vibrato, dim gold is still. A fainter gold band is
  where the detector hears a hold.
- **Shape** — the bright primary stroke *is* the shimmer. High is a
  crest, low is a trough. The gold bead is the playhead on that
  stroke; the Arabic word wears that height. Draw, then Play.

Pinch spreads a section (two fingers apart). Two-finger swipe pans
when zoomed. Fit returns to the whole capture. The graph is excluded
from the system back-edge swipe so a handle on the paper's rim stays
a handle. Play at 1× / ½ / ¼
keeps the playhead on the ear. With Tune closed, a faded key shows
only the marks the current tool uses.

**Tune** unfolds this reciter's knobs, Export, and Import.

## The knobs

Behind **Tune**: the same eight detector knobs as the Ink Lab's Tarjīʿ
section — one source of truth (`TarjiLabKnobs` ↔ `InkEngine.Tuning`) —
stored as a **per-reciter profile**. They change what the *algorithm*
hears (the faint gold band in Hold), not the stroke you draw.
**Glint depth** is the only look knob: how hard the word flashes.
**Reset** restores shipped defaults for this reciter only. Export /
Import live here, not in the header.

The names are the signature, not a frequency author:

- **Hold min** — how long the note must sit before it counts
- **Wobble min / max Hz** — the vibrato band this reciter lives in
- **Min depth** — how deep the wobble must be
- **Regularity** — how periodic vs. noisy
- **Pitch wander** — how much the fundamental may drift
- **Attack / Release** — how the gate opens and dries
- **Glint depth** — how strongly the reader shows a detected hold

## Workflow

1. Long-press a word → **Tarjīʿ Lab** (or Settings → Developer → Tarjīʿ
   Lab; the word-stepper ‹ › walks the ayah). The Lab captures
   automatically and muted at 1× (300 ms lead, 1 s tail).
2. Switch to **Hold** and drag the gold edges around the climactic note.
   Play loops that window; Play whole word loops the capture.
3. Tap the gold hold: wave means vibrato, flat means still. The word
   follows.
4. Optionally switch to **Shape** and draw the envelope you want the
   algorithm to treat as this reciter's signature. The word wears that
   shape.
5. Tune this reciter's knobs until the thin detector rail agrees with
   your gold hold.
6. Add a note when the room or the mic matters. **Export** the sample.

## Samples — the exchange format

**Export** writes JSON to
`Android/data/<app>/files/Download/tarji_<reciterId>_<surah>_<ayah>_w<word>.json`:
word metadata, the decimated PCM (Base64, 16-bit LE), the true hop
duration, the hold window, the optional envelope, this reciter's knobs,
and notes. **Import** loads such a sample and re-analyzes it.

Schema 3 is the signature format. Schema-2 samples (crest/sine
authoring) still import; their start/end become the hold, and crests
are ignored by the UI.

`tools/tarji_samples/` is where samples land (see its README) when a
better detector is derived from real recitations. Build a set per
reciter: several holds, and matched stills from the same recording
chain.

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
  wall-clock estimate. Loop points are the hold window, not the whole
  buffer.
- Hardware loop points were reworked in API 37 (`setLoopPoints(start, end,
  loopCount)` with −1 = infinite); older platforms use the two-argument
  form via reflection, since the 37 stub no longer carries it.

## Files

```
app/src/main/java/com/beautifulquran/playback/TarjiLabCapture.kt   capture data + trim helpers
app/src/main/java/com/beautifulquran/playback/VoiceEnergy.kt       armCapture/disarmCapture sink
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabTrace.kt     offline re-analysis
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabRegion.kt    hold window, envelope, loop frames
app/src/main/java/com/beautifulquran/tarjilab/ReciterTarjiProfiles.kt  per-reciter knob store
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabSample.kt    JSON sample codec
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabViewModel.kt capture/loop/hold orchestration
app/src/main/java/com/beautifulquran/tarjilab/TarjiLabScreen.kt    waveform scope + knobs
app/src/test/java/com/beautifulquran/tarjilab/                     region, codec, gating tests
```

Unit tests cover the hold window, envelope paint, per-reciter profile
book, and JSON round trip — run `./gradlew testDebugUnitTest` before
committing changes here.
