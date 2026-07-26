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

The scanner fetches only the opening 96 KiB of each MP3. Silence must sustain
for 80 ms to register, and only voice onsets of at least 250 ms are recorded.
It requires `ffmpeg`; `build_db.py` does not.
