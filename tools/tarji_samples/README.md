# Tarjīʿ samples

Drop exported Tarjīʿ Lab samples here (see `docs/TARJI_LAB.md`): JSON files
produced by **Export** in the in-app lab, named
`tarji_<reciterId>_<surah>_<ayah>_w<word>.json`.

Each schema-3 sample is one reciter-signature waveform: the captured PCM, the
hold window you marked, an optional hand-shaped envelope, that reciter's
detector knobs, and a listening note. Schema-2 crest/sine samples still
import; their start/end become the hold.

## Building a useful reciter set

Do not tune the detector from one beautiful example. For the same reciter,
keep both positive holds and matched stills:

- slow and fast held notes, quiet and loud
- waqf, madd, and ghunnah at different rooms/mics
- still vowels, consonant flutter, breath, and room echo that must **not**
  fire

Mark the hold (and shape the envelope when the raw wave is messy) before
changing knobs. Every detector change should improve the held-out set as
well as the examples used to derive it.

## How to extract samples from a device

```bash
adb pull /sdcard/Android/data/com.beautifulquran.debug/files/Download/tarji_*.json .
# or the release variant:  com.beautifulquran/files/...
```

## Schema

```jsonc
{
  "schema": 3,
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
  "knobs": {                   // this reciter's detector knobs
    "maxTremoloHz": 10, "minTremoloHz": 1.5, "holdMinMs": 300,
    "minTremoloDepth": 0.035, "minPeriodicity": 0.4, "maxPitchDrift": 0.12,
    "attackMs": 250, "releaseMs": 800
  },
  "expectation": {
    "kind": "PULSES",         // UNLABELED, NO_SHIMMER (still), or PULSES (hold)
    "startMs": 1240,
    "endMs": 3860,
    "envelope": [0.12, 0.40, 0.88]  // optional hop-aligned 0..1 shape
  },
  "notes": "Alafasy studio waqf; ignore the room tail after 3.6s."
}
```

Decode with the in-app lab's **Import**, or with any JSON tool (the PCM is
plain Base64). The app-side codec is `TarjiLabCodec` in
`app/src/main/java/com/beautifulquran/tarjilab/`.
