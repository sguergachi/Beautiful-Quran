# HighlightEngine V2 local validation

Implementation: Android 0.12 (13), web 0.12.0, visible engine generation 2.
Timing data is unchanged (v67). This is implementation evidence, not an
independent acoustic review or a zero-regression guarantee.

| Gate | Result |
|---|---|
| Android JVM | 1,574 passed; one optional external-corpus test skipped in the ordinary suite |
| Web | 868 tests / 93 files passed; production build passed |
| Offline timing pipeline | 116 cases passed |
| V2 delivered-corpus lookup | 81,059 rows, 13 reciters, 12,378,291 boundary/reverse/scrambled queries, 17,748 repeat occurrences passed |
| Arabic/English prefix oracle | 12,384,384 queries passed for each of published prose and word-card text |
| Integrated native capture replay | 158 samples + 390 display frames reproduced exactly; zero dropped records |
| Matched release rendering | Alafasy 24:2, Arabic scroll, same emulator, 15 seconds, recording/tracing disabled: V1/V2 both 0 janky frames and p95 8 ms; p99 19/18 ms |

Shared fixtures cover legitimate delayed polls, overshoot and corrections,
pause/buffering, speed changes, explicit seeks, stale acknowledgments, timing
withdrawal/replacement, opening calibration, and same-word repeat occurrences.
Android adds seeded transport sequences, draw-timeline tests at 60/90/120 Hz,
provisional tap and reciter isolation, repeat/residual policy, and observer
suspension/reopen. Existing English ownership, reordered-prefix, carried-page,
glimmer, silence, and transport suites remain gates.

Review follow-up: dismissing web playback reset controller revisions while V2
retained its suspended session, causing fresh playback to be rejected as stale.
The controller now keeps revisions cumulative across queue clearing. Two tests
connect the real player controller to V2 through stop/restart, covering both the
same verse and a different verse; both failed before the fix and pass after it.

The external corpus test was also exercised explicitly. The nine unavailable
rows remain separately listed, rather than counted as successes. All six
reviewed runtime pack revisions, the DB digest, source hashes, test totals,
reports, and replay/video evidence are recorded outside Git in
`~/.t3/artifacts/Beautiful-Quran/timing-audit/t3-112e60be/v2/validation-manifest.json`
and adjacent files. Restricted timing payloads are not bundled as fixtures.

The native capture identifies its actual migration build: source 43560e82 with
uncommitted changes, app 0.11, engine 2, trace schema 2. It predates the final
version label. Profiling plus screen recording produced much worse frame times;
that run is replay evidence, not the performance comparison. The comparison
uses ordinary release playback without either instrument.

Physical Pixel speaker/Bluetooth timing error, route switching on the headset,
and listening acceptance remain unmeasured. Internal capture and emulator
rendering cannot establish when sound arrives at the listener's ear. The
runtime guard also cannot identify a smoothly advancing but wrong player clock
without additional evidence. See the [runtime contract](../HIGHLIGHT_ENGINE_V2_CONTRACT.md).
