# audio_onsets/

Generated measurements of the exact everyayah MP3 files streamed by the app:
where the voice starts (`offsets`) and how long each recording runs
(`durations`). `build_db.py` holds an ayah's opening wash until the measured
voice onset, after structural timing repairs and before local Timing Lab
overrides.

`durations` is the physical ceiling for a timing row. Marks past the last
sample can never be reached, so any clock translation that would cross it is
refused and the untranslated source row is kept — quran-align occasionally
stretches a word across a long pause, and without this guard one bad reference
row could shift a whole ayah seconds past the end of its own audio.

This is intentionally separate from `timing_overrides/`: the correction is
audio-derived and repeatable across a reciter pack, not a hand-tuned ayah
clock.

Generate a complete reciter file:

```bash
python3 tools/detect_audio_onsets.py --reciter Alafasy_128kbps
```

The scanner analyzes up to the opening eight seconds of each MP3. It starts
with a 96 KiB range and retries ambiguous prefixes with 256 KiB; a silence end
at the decoded byte-range boundary is rejected as ffmpeg's end-of-input flush,
not mistaken for voice. Silence must sustain for 80 ms to register, and only
voice onsets of at least 250 ms are recorded. It requires `ffmpeg`;
`build_db.py` does not.

Durations come from the same request: everyayah publishes one constant bitrate
per reciter (named in the slug), so the byte length is the clock. ID3 tags
round it ~130 ms long, which only ever widens the ceiling. Re-measure them
without decoding any audio:

```bash
python3 tools/detect_audio_onsets.py --reciter Alafasy_128kbps --durations-only
```
