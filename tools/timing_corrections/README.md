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

Every entry carries evidence provenance. The build fails if its expected source
shape no longer exists, so a pinned-source refresh cannot silently retain a
stale verdict.
