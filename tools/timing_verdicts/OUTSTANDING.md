# Drifted rows still without a verdict

The build stopped reproducing the shipped database at `4861e0de` (quran-v41),
which taught `rebase_qdc_clock` to clip a final source fade instead of
abandoning the whole translation — and, in the same commit, added the
fail-closed gate that then withheld every row the new rule improved. Every
database since carried that baseline forward by transplant, so rows sat on their
source's window clock beside siblings on the file clock.

`quran-v49` closes the mechanical part of that gap: 272 rows whose every
boundary moves by one shared offset now ship under the `file_clock_rebase`
evidence kind. The 26 rows below are the remainder. They are **not** defects in
the shipped database — they are candidate changes the build produces that no
mechanical argument can accept, so the shipped baseline is retained until
acoustic evidence exists.

Reproduce the list with a full `python3 tools/build_db.py` into a scratch path,
then `python3 tools/timing_delta.py <shipped> <rebuild>`.

## Re-timings (25 rows)

Every boundary moves on its own, so this is a re-clock against the quran-align
reference rather than one translation. Accepting any of them needs the
two-witness `dual_model_boundary` evidence the ledger already defines; `~/qasr`
is the pipeline that produces it. Twenty-two are Minshawy, whose qdc rows are
re-clocked wholesale. `Abdurrahmaan_As-Sudais_192kbps:6:104` and
`Hani_Rifai_192kbps:88:25` already hold verdicts in `v35-tail-clips.json` for
the transitions that shipped them; those verdicts do not cover this further
change, and the gate correctly refuses it.

| Row | Words | Start shifts |
| --- | --- | --- |
| `Abdurrahmaan_As-Sudais_192kbps:6:104` | 18 | +0 … +80 ms |
| `Alafasy_128kbps:13:25` | 25 | -440 … +40 ms |
| `Hani_Rifai_192kbps:88:25` | 3 | +0 … +240 ms |
| `Minshawy_Murattal_128kbps:21:89` | 11 | -80 … +814 ms |
| `Minshawy_Murattal_128kbps:26:136` | 9 | -60 … +318 ms |
| `Minshawy_Murattal_128kbps:26:17` | 5 | -20 … +191 ms |
| `Minshawy_Murattal_128kbps:26:29` | 8 | -30 … +40 ms |
| `Minshawy_Murattal_128kbps:26:58` | 3 | -160 … +0 ms |
| `Minshawy_Murattal_128kbps:27:13` | 8 | -40 … +375 ms |
| `Minshawy_Murattal_128kbps:27:51` | 9 | -20 … +70 ms |
| `Minshawy_Murattal_128kbps:28:10` | 17 | -90 … +1,316 ms |
| `Minshawy_Murattal_128kbps:28:32` | 25 | -1,330 … +271 ms |
| `Minshawy_Murattal_128kbps:28:79` | 20 | -520 … +1,450 ms |
| `Minshawy_Murattal_128kbps:29:33` | 23 | -1,550 … +1,521 ms |
| `Minshawy_Murattal_128kbps:2:250` | 15 | -210 … +214 ms |
| `Minshawy_Murattal_128kbps:33:45` | 7 | -68 … +312 ms |
| `Minshawy_Murattal_128kbps:36:46` | 11 | -90 … +867 ms |
| `Minshawy_Murattal_128kbps:37:134` | 4 | -60 … +80 ms |
| `Minshawy_Murattal_128kbps:37:43` | 3 | -40 … +0 ms |
| `Minshawy_Murattal_128kbps:37:90` | 3 | -10 … +0 ms |
| `Minshawy_Murattal_128kbps:3:40` | 17 | -833 … +1,595 ms |
| `Minshawy_Murattal_128kbps:40:60` | 13 | -780 … +90 ms |
| `Minshawy_Murattal_128kbps:56:15` | 3 | -84 … +0 ms |
| `Minshawy_Murattal_128kbps:82:4` | 3 | -10 … +0 ms |
| `Minshawy_Murattal_128kbps:84:3` | 3 | -10 … +40 ms |

## Withheld (1 row)

`Saood_ash-Shuraym_128kbps:40:64` — the rebuild ships no row at all for this ayah, leaving
21 words unlit. Withholding is the right answer only when no source can
describe the recording safely; here the shipped row still can, so it stands.
