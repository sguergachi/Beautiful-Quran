# reciter_align: word timings from a reciter's own audio

Aligns whole-surah recordings of any reciter to the canonical Quran text in
`data/quran.db`, with no third-party timing data. It exists so a reciter who
has no published word timings can still get karaoke-style highlighting, and so
that the result is ours to ship.

```
whole-surah audio ──► CTC emissions (MMS) ──► route resolution ──► word times
                                              (openings, repeats)      │
                      second model (XLSR) ◄── audit every verse ◄──────┤
                      energy edges ───────────────────────────────────►┘
                                              ▼
                                  one hash-pinned artifact (json.gz)
```

## Using it

Needs `torch`, `transformers`, `uroman`, `numpy`, `ffmpeg`, and
`ctc-forced-aligner` from GitHub (see `requirements.txt`). A GPU makes the
models ~100x faster; the alignment itself is CPU.

```sh
cd tools
python -m reciter_align align  --audio DIR_OF_SURAH_AUDIO --work WORKDIR          # resumable
python -m reciter_align report --work WORKDIR
python -m reciter_align pack   --audio DIR --work WORKDIR \
       --reciter Some_Reciter_128kbps --out timing_sources/some-reciter-alignment.json.gz
```

`--pattern` names the per-surah file (default `{surah:03d}.mp3`). Re-running
`align` skips surahs already in `WORKDIR`; `--force` redoes them.
`python3 tools/test_reciter_align.py` runs the offline tests (synthetic
audio, no models).

## What it does, and why

* **Text, not transcription.** Forced alignment of the *known* verse text keeps
  words in canonical order and names every word. `text.py` reduces words to
  bare letters (marks removed, hamza folded), which is what both models know.
* **Windows.** Hours of audio don't fit one CTC trellis. `windowed.py` aligns
  overlapping 4-minute windows and commits only words far from a window's end.
  Each window's text is sized for a deliberately slow reciter so it always
  fits. A wildcard ("star") absorbs audio outside the text at each window
  edge. Stars *between* words are off: they let a word slide to a look-alike
  sound far away at no cost.
* **What was actually said.** Recordings contain speech the text lacks: an
  istiadha/basmala at the start *and* at section breaks inside long surahs,
  and phrases or whole verses said twice. `route.py` finds audio that the
  aligned words leave unexplained (or words squeezed into the wrong place)
  and scores competing explanations against the same audio by CTC
  likelihood: nothing extra, an opening (3 variants), or "the previous N
  words again", tried at every nearby insertion point. The best wins only if
  it beats "nothing extra" by a clear margin. Re-aligns and repeats until
  stable. Whatever stays unexplained is reported, never forced.
* **Edges.** CTC marks the surest frame of a token, a little inside the sound.
  `acoustics.py` finds where sound physically starts and stops from 10 ms
  energy, for cutting audio at verse seams.
* **Audit.** `crosscheck.py` re-aligns each verse with a different model
  (XLSR-53 Arabic) inside that verse's acoustic window and lists every word
  whose start differs by more than 200 ms. Disagreements are listed for review,
  never averaged.

## The record

Per surah (`artifact.py`): `words` `[ayah, pos, startMs, endMs, meanLogProb]`
in spoken order (repeats included, so positions can go backwards); `runs`
(one per consecutive stretch of one ayah, with acoustic `onsetMs`/`offsetMs`);
`openings` (istiadha/basmala heard, with where they fall); and `review`
(insertions, unexplained speech, weak words) plus `check` (audit summary).
Word `endMs` is the model's last token, not the end of the sound; use the
run `offsetMs` for audio edges.

## Known limits

* A short repeat of one tiny word can score below the acceptance margin and is
  left in `review.unexplainedSpeech`.
* Accuracy on word starts is a CTC spike, typically 20-30 ms from a second model; ~1-2 % of words
  disagree by over 200 ms, mostly where one word ends in the letter the next
  begins with.
* Measured against quran.com's segments for Hani Ar-Rifai, Alafasy and Husary
  (10 surahs, ~4,000 words, their own whole-surah files): word starts inside a
  verse agree to a median of 30-40 ms, ~80 % within 100 ms, with no constant
  bias. Verse starts sit on the acoustic onset; quran.com's begin in the pause
  before. Most larger differences are quran.com tiling a pause into the next word.
* Idgham into a following word is said as one merged sound. The letter models
  give it one consonant, so `text.py` passes the shadda on a word's first letter
  as a doubled letter and drops the bare nun before it. Without that the word
  started after its ghunna, up to 0.8 s late. The check and arbiter models hear
  it late too, so the arbiter never moves such a word's start. Idgham into ي/و
  (no shadda written) is unchanged.
* Only Arabic through `ara` uroman (MMS) and Arabic letters (XLSR).
