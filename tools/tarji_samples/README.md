# Tarjīʿ samples

Drop exported Tarjīʿ Lab samples here (see `docs/TARJI_LAB.md`): JSON files
produced by **Export** in the in-app lab, named
`tarji_<reciterId>_<surah>_<ayah>_w<word>.json`.

Each sample reproduces, off-device, one word's captured PCM and the detector
knobs it was analyzed under — the waveform, tarjīʿ sine, and fitted sine
re-render identically. When the user reports what a reciter's tarjīʿ *should*
look like for a word (onset, rate, span, shape), the sample plus that
expectation is the regression input for deriving a better `Tarji` algorithm.

## How to extract samples from a device

```bash
adb pull /sdcard/Android/data/com.beautifulquran.debug/files/Download/tarji_*.json .
# or the release variant:  com.beautifulquran/files/...
```

## Schema

```jsonc
{
  "schema": 1,
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
  "notes": ""
}
```

Decode with the in-app lab's **Import**, or with any JSON tool (the PCM is
plain Base64). The app-side codec is `TarjiLabCodec` in
`app/src/main/java/com/beautifulquran/tarjilab/`.
