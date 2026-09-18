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
Yasser Al-Dosari uses Quranic Universal Audio's canonical occurrence from the
same full-surah recording used by EveryAyah; non-canonical whole-verse repeats
are deliberately excluded because they are not present in the streamed ayah
file. Phrase repeats inside the canonical occurrence remain in order.

