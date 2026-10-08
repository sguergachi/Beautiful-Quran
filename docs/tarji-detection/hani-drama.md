# Hani Ar-Rifai: what "dramatic" means in his voice

The Recording method lights only dramatic reverberations (`TarjiDrama`, Tarjīʿ
Lab **Drama**): the moments a reciter holds a note and lifts his voice into it.
The generic ranges were set from 26 recordings by 13 reciters. This is the
proof of concept for fitting them to one reciter, from all of his verses.

## Method

All 6,236 verses of Hani's recitation (everyayah `Hani_Rifai_192kbps`) were
decoded as the tap does and run through the Recording method with his profile
(`HANI_TUNING`) and no drama threshold. Every event with peak gain ≥ 0.05 was
logged: its length, the held run it rode, its loudness and pitch against the
verse's own median, its strength, and the word under its centre. That gave
31,019 events in 5,797 verses (median 4 per verse).

A second reviewer (a Claude subagent with no access to these conclusions)
recomputed loudness and pitch from the audio for every event, checked the
dataset, and proposed the parameters below; its loudness matched to
Spearman 0.996.

## Findings

- **The generic score was far too loose for him:** it called 44% of his pulses
  dramatic. His "hold" (a same-pitch voiced run) has a median of 3 s and in 80%
  of events began more than a second before the word; it measures his
  reciting tone, not a held vowel, and is dropped.
- **His verse endings fall.** Closing words are quieter (median −0.2 dB vs
  +0.9 dB elsewhere) and lower, and loudness falls over the last fifth of a
  verse. "End of verse" is not a proxy for a lifted voice.
- **Pitch is unreliable on its own.** 25% of readings are octave errors, and
  inside ±6 st the reviewer's tracker disagreed by more than 2 st in 27% of
  events. Verse-final cadences read as +4 to +6 st scored 0.93–0.99 under the
  generic score while the audio fell 3–6 st (17:16, 3:163, 2:208).
- **Strength duplicates length** (Spearman 0.71 with duration): a gate, not a
  term.
- **Verse-relative lift is right:** a ±3 s local window agrees on 82% of kept
  events, and a whole climactic phrase stays lifted against the verse.

## Hani's weights (`TarjiDramaWeights.HANI`)

| term | value |
|---|---|
| sustain | pulse length 400 → 1,000 ms |
| loudness lift | 1 → 5 dB above the verse's median |
| pitch lift | 1 → 3 st, only within ±4 st and with the voice ≥ 0.5 dB louder, worth half |
| score | √(sustain × lift): held **and** lifted |
| gate | peak gain ≥ 0.3 |
| cap | the two most dramatic events of a verse |
| threshold | the profile's Drama, 0.5 |

## Result, through the real detector

| | events kept | verses with ≥ 1 | max per verse | verses that glow* |
|---|---|---|---|---|
| generic | 43.8% | — | 13 | — |
| Hani | 2,793 (9.0%) | 35.0% | 2 | 14.2% |

\* on a word that may pulse (`InkEngine.tarjiEligible`): only 35% of kept
events fall on one.

The most dramatic by this score include 33:4, 30:28, 32:26, 45:4, 8:9 and
16:32 — a second or more of pulse 5–10 dB above the verse.

## Open

- **Nobody has listened yet.** The listening pack (most dramatic, a random
  sample kept, strong pulses rejected) is the validation; if loud-but-short
  moments such as 72:20 sound dramatic, shorten the sustain ramp to
  300 → 800 ms.
- **2:14's closing word no longer lights** under his weights: it is a falling
  cadence (−1.1 dB). Lower Drama to see it again.
- **The Recording method finds no event on 1:7's closing word** at all — a
  detection gap before any drama score.
- Only a third of dramatic events fall on an eligible word; snapping them to
  the nearest one would be a separate change.
