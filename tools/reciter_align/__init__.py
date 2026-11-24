"""Clean-room forced alignment of whole-surah recitations, for any reciter.

Given a reciter's surah audio and the canonical Quran text in data/quran.db,
this finds when every word is spoken, without any third-party timing data:

  text.py       canonical words, letter normalisation, spoken openings
  viterbi.py    CTC forced alignment (C++ extension when present, numpy otherwise)
  backends.py   acoustic models that turn audio into CTC emissions + word tokens
  windowed.py   aligns a route of words against hours of audio in overlapping windows
  route.py      decides what was actually said: opening, re-recited phrases
  acoustics.py  energy-based word/verse onset and offset refinement
  crosscheck.py a second, independent model that audits the first
  artifact.py   the committed, hash-pinned alignment file
  cli.py        `python -m reciter_align ...`

See tools/reciter_align/README.md.
"""
