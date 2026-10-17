# Web performance and Android UX review — 2026-10-16

Reviewed the scrolling reader's chapter handoff, static ornaments, compact
chapter title, repeat controls, and the existing book/navigation/ink contracts.
Android reference: `ReaderScreen.kt`, `ReaderComponents.kt`, `RepeatSheet.kt`,
`ReaderViewModel.kt` and `PlayerBar.kt`. This was a source comparison and browser
review, not an Android device benchmark.

## Findings and fixes

| Finding | Reproduction / consequence | Resolution |
| --- | --- | --- |
| High: stale chapter animation overwrote newer navigation | Start 112 → 113, open 114 during flight; the old continuation reopened 113. | A persistent `ReaderChapterHost` owns an abortable animation. Reader revision, sheet, word-viewer, layout and resize changes cancel it, including same-chapter verse navigation. |
| Medium: incoming verse reveal was lost at chapter remount | The first verse of 113 appeared at opacity 1, while the outgoing component kept updating its own obsolete state. | Transition metadata survives the keyed reader. The arriving verses start parked and fade/rise into place. Frame updates write styles instead of React state. Reduced motion advances directly. |
| Medium: static ornament work followed reader updates | Eight boundary-equivalent reader updates rebuilt 16 chapter rosettes. | Memoized header, footer and compact title retain their geometry; the footer action stays stable. The same probe now rebuilds zero rosettes. |
| Medium: editing repeat bounds could leave stale controls | Change `range` 1–3 to 2–2 without changing mode; the repeat icon stayed unchanged. | Store publication includes both range bounds and speed while continuing to ignore position-only ticks. Unit and browser regressions cover the seam. |
| Low: compact chapter title used an older visual treatment | Web displayed flanking flourishes; Android uses the chapter's miniature medallion above its bilingual name. | Reuse the same chapter seed and generated medallion, scaled to Android's 18 dp treatment with matching thin strokes. Checked on desktop and phone. |
| Performance: every idle verse retained a transform and `will-change` | Settled mounted verses kept requesting animation promotion. | Verse transform/promotion is scoped to the chapter ceremony; idle verses have neither. |

The chapter key is intentionally retained. Its introducing commit,
`a05eacfc`, explains that fresh chapter focus/rail geometry prevents a first-frame
jump. Moving animation ownership above that boundary fixes the lifecycle
without carrying old focus state into a new chapter.

“This ayah” was checked against `ReaderViewModel.setRepeatMode`: Android
converts it to a one-verse range. Web already matches; that behavior is retained.

## Evidence

Headless Chromium, Vite development build, 1440 × 900, canonical chapters
112 → 113. Development React commit hooks observe actual render clocks, including
memo bailouts. The eight-update ornament probe changes the reader's ink
activation; it does not certify a live reciter or device frame rate.

- Whole-transition React commits: 36 before, 20 in the completed browser check.
  These are individual local runs, not a statistical timing benchmark.
- Static rosette rebuilds for eight reader updates: 16 → 0.
- Incoming verse's first observed opacity: 1 → 0; finishes at 1.
- Interrupted navigation: final chapter 113 → intended chapter 114.
- No page errors in the completed browser checks.

Validation passed: 716 tests across 92 files; production type check/build;
`check-reader-performance-browser.mjs`, `check-book-browser.mjs`,
`check-ui-navigation-browser.mjs`, and `check-ink-browser.mjs`. The ink check
includes concurrent repeat washes and a deterministic local two-verse audio
handoff. An initial full-suite run timed out in the existing exhaustive
ornament-geometry test while browser profiling competed for CPU; the isolated
full rerun passed in 3.69 seconds without changing the test or timeout.

## Remaining scope

Android also couples compact-title lift/scale to continuous header scroll
progress; web retains its threshold-triggered fade. Repeat bounds use native
number fields on web and wheels on Android. The review does not establish
physical-device frame rates or external-reciter network reliability. Existing
soft masks, tajweed pacing, tarjīʿ and ordered repeat washes remain covered by
their tests; their rendering algorithms were not changed in this pass.
