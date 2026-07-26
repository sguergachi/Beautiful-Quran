# audio_onsets/

Generated measurements of leading silence in the exact everyayah MP3 files
streamed by the app. `build_db.py` shifts an ayah's complete segment row to the
measured voice onset, after structural timing repairs and before local Timing
Lab overrides.

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
