# Tarjīʿ samples

Drop exported Tarjīʿ Lab samples here (see `docs/TARJI_LAB.md`): JSON files
produced by **Export** in the in-app lab, named
`tarji_<reciterId>_<surah>_<ayah>_w<word>.json`.

Each schema-3 sample carries captured PCM, the selected loop window, detector
knobs, and a listening note. Legacy envelopes and labels still decode but never
drive the preview. Schema-2 samples import with their start/end as the loop.

## Building a useful reciter set

Do not tune the detector from one beautiful example. For the same reciter,
keep both positive holds and matched stills:

- slow and fast held notes, quiet and loud
- waqf, madd, and ghunnah at different rooms/mics
- still vowels, consonant flutter, breath, and room echo that must **not**
  fire

Listen to the same loop while changing knobs. The gold pulse overlay shows
the accepted response used by the word’s glow. Raw candidate modulation is
kept for analyzer audits, and is not drawn in the lab.
Every detector change should improve held-out examples as well as the
examples used to derive it.

## How to extract samples from a device

Tap **Export sample** in the lab and save the JSON to **Downloads** using
Android’s file picker. The lab confirms the saved filename. To retrieve it:

```bash
adb shell ls /sdcard/Download/tarji_*.json
adb pull /sdcard/Download/<saved-filename>.json .
```

You can also share the saved JSON directly from the phone’s Files app.

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

## Pulse visibility audit

`pulse_audit_2026-09-29.csv` checks two recorded word captures for every catalog
reciter. Clip keys are `reciterId-surah-ayah`; hop counts use 20 ms hops.
`candidate_hops` counts |measured pulse| > 0.01 at shipped defaults,
`accepted_hops` counts detected holds, and `changed_output_hops` counts output
changes > 0.001 under the tuning described in `docs/TARJI_LAB.md`.
