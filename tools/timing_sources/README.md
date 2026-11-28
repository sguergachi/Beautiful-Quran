# Full-corpus timing sources

These manifests are the narrow admission path for a **new** highlighted voice.
They do not approve edits to any timing row that already ships. The timing
delta gate accepts a corpus only when:

- every delta for that reciter is an added row;
- the row count and the hash of every `(key, payloadHash)` pair match exactly;
- the source archive URL and SHA-256 are pinned; and
- any missing upstream row has durable acoustic evidence here.

This keeps ordinary corrections behind the stricter per-row dual-model verdict
gate while making a reviewed 6,236-row open dataset practical to import.

The five quran-align voices use the release's EveryAyah-matched per-ayah clock.
Yasser Al-Dosari uses Qur'anic Universal Audio over the same whole-surah source,
rebased by `yasser-everyayah-clock.json` onto the first MPEG frame physically
present in each streamed clip. Every source occurrence inside that clip stays
in order, including 163 clips with an additional full or partial pass. The five
clips that cannot byte-match the QUA source are named by that clock artifact
and use the two-model evidence in `yasser-1-1-dual-model.json` and
`yasser-unmatched-dual-model.json`.

Regenerate the clock artifact only from locally downloaded source chapters and
8 KiB EveryAyah prefixes with `tools/build_yasser_clock_map.py`; its two corpus
hashes bind all 114 source files and all 6,236 clip prefixes used by the map.

Tawfeek As-Sawaigh has no per-verse source at all: the app streams each verse as a
byte range of his public surah recording (`tawfeek-verse-index.json`). His word times
are our own, from `tools/reciter_align` (`tawfeek-alignment.json.gz`, pinned to the
SHA-256 of all 114 recordings). Every inserted opening or repeat is supported by two
acoustic models; a word two models place more than half a second apart is decided by a
third, and kept for review when all three differ. Regenerate with `python -m
reciter_align align` then `pack`, then `build_tawfeek_verse_index.py`.
