# Tarjīʿ samples

Drop exported Tarjīʿ Lab samples here (see `docs/TARJI_LAB.md`): JSON files
produced by **Export** in the in-app lab, named
`tarji_<reciterId>_<surah>_<ayah>_w<word>.json`.

Each schema-2 sample reproduces, off-device, one word's captured PCM, detector
knobs, and **Ear truth** — no shimmer, or the manually marked onset, brightness
crests, end, and visual style auditioned in lockstep with the audio. The crest
intervals preserve local frequency and phase; the phase anchor supports a
regular-rate tuning pass; depth/trough/build/dry preserve the intended visible
experience. Together they are the regression target for deriving a better
`Tarji` algorithm and one reciter-wide render profile. Schema-1 captures still
import as unlabeled samples.

## Building a useful reciter set

Do not tune the detector from one beautiful example. For the same reciter,
keep both positive and matched negative words:

- slow, fast, steady, and changing-cadence tarjīʿ;
- amplitude-led, pitch-led, and mixed modulation;
- waqf, madd, and ghunnah holds at different loudnesses;
- steady vowels, consonant flutter, breath, and room echo that must **not**
  shimmer.

Tune Ear truth until each exported target looks right before changing detector
knobs. The shared style values reveal the reciter-wide visual profile; the
per-clip onset, end, and crests remain ground truth for fitting and regression.
Every detector change should improve the held-out set as well as the examples
used to derive it.

## How to extract samples from a device

```bash
adb pull /sdcard/Android/data/com.beautifulquran.debug/files/Download/tarji_*.json .
# or the release variant:  com.beautifulquran/files/...
```

## Schema

```jsonc
{
  "schema": 2,
  "label": "Mishary Rashid Alafasy 1:7 w1",
  "reciterId": 7,
  "reciterName": "Mishary Rashid Alafasy",
  "surahId": 1,
  "ayah": 7,
  "wordPosition": 1,
  "wordArabic": "نَعْبُدُ",
  "sampleRate": 8000,          // decimated stream rate (≈8 kHz)
  "hopSamples": 147,           // samples per analysis hop
  "hopContentDurationMs": 20,  // true content ms per hop (44.1k → 20 ms)
  "firstHopMediaMs": 12345.6,  // media-clock position of the first hop
  "pcmB64": "...",             // decimated mono PCM, 16-bit LE, Base64
  "knobs": {                   // detector knobs at capture time
    "maxTremoloHz": 10, "minTremoloHz": 1.5, "holdMinMs": 300,
    "minTremoloDepth": 0.035, "minPeriodicity": 0.4, "maxPitchDrift": 0.12,
    "attackMs": 250, "releaseMs": 800
  },
  "expectation": {             // listener-authored ground truth
    "kind": "PULSES",         // UNLABELED, NO_SHIMMER, or PULSES
    "startMs": 1240,
    "endMs": 3860,
    "crestMs": [1370, 1580, 1795, 2010],
    "phaseAnchorMs": 1580,
    "style": {
      "depth": 1.0,
      "troughFloor": 0.0,
      "buildMs": 1000,
      "dryMs": 50
    }
  },
  "notes": "Follow the pitch pulses; ignore the room echo."
}
```

Decode with the in-app lab's **Import**, or with any JSON tool (the PCM is
plain Base64). The app-side codec is `TarjiLabCodec` in
`app/src/main/java/com/beautifulquran/tarjilab/`.
