# Typed timing corrections

This directory contains narrow, evidence-backed verdicts for timing shapes that
cannot be decided from topology alone. A correction names one operation; it may
not replace a complete ayah row.

Systematic classes still belong in `clean_qdc_artifacts`. Add a correction only
after raw qdc, cleaned qdc, quran-align, CTC, and the streamed audio have been
compared.

Supported operations:

- `one_utterance`: collapse one verified `A,B,A,B` aligner loop to a single
  utterance while preserving the first `A` and final `B` boundaries.
- `discard_false_same_position_lead`: remove one audio-proven false duplicate
  and retain the second occurrence's onset; the preceding word owns the
  discarded lead interval. Hani 66:6 لَّا (#721) was this shape: the first
  15 is still voiced شداد, not a second لا.

  **No entry uses this today.** That row is now reached systematically by
  `false_same_position_leads` in `tools/build_db.py`, which asks quran-align
  where the word begins instead of taking a hand-written audio verdict, and
  reproduces the same row byte-for-byte (docs/REPEAT_HIGHLIGHTING.md, "False
  same-position lead"). The operation is kept as the escape hatch for a lead
  the witnesses cannot reach — an ayah with no quran-align row, where the rule
  abstains by design.

- `restore_flattened_resay`: restore one audio-proven re-say that qdc
  labelled as the following word. The mirror of the false lead: a word said
  twice with one qdc label hands its second utterance to the next word, which
  then starts a whole utterance early. The entry names the word, the re-say
  onset, the next word's onset, and the exact flat `sourcePair` it was
  verified on. The onsets are absolute times, so any source or clock change
  that moves that pair fails the build instead of shifting the verdict.
  Hani 21:46 يَٰوَيۡلَنَآ (#779) is this shape: qdc opens إِنَّا at 9800 while
  quran-align, the Lab and both forced aligners put it at 11350–11520. No
  pipeline rule can reach it — qdc flattened the repeat, quran-align cannot
  express one, and CTC fused the rewind into a single token (`وَيلنايا`).

Every entry carries evidence provenance. The build fails if its expected source
shape no longer exists, so a pinned-source refresh cannot silently retain a
stale verdict.
