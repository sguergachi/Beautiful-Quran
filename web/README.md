# Beautiful Quran — Web

Browser port of Beautiful Quran: the same Focus / Highlight / Ink engines,
the same paper metaphor, and word-by-word highlighting synced to
recitation audio.

## Live

https://sguergachi.github.io/Beautiful-Quran/app/

Linked from the project homepage as **Open web reader**.

GitHub Actions stages the marketing content from `docs/` and builds the reader
under `/app/`, then deploys the combined tree as a GitHub Pages artifact. Build
output is never committed back to `master`.

Repository **Settings → Pages → Build and deployment → Source** must be
**GitHub Actions**. Selecting the legacy `master /docs` source deploys only the
marketing files and overwrites `/app/` with a 404.

## Repeat in the book

The playback repeat button opens a paper modal sheet, matching Settings: a
left-page sheet on desktop and a full sheet on phones. It offers Android’s
Off, This ayah, Whole surah, A range of ayahs, and From this ayah modes.
From this ayah counts the opening ayah in the requested number. Done applies
the choice; Cancel or Escape discards edits and returns focus to the button.
An active ayah inside a new range continues uninterrupted; a range selected
while paused or outside its bounds starts at its first ayah. Whole-surah
repeat includes the chapter’s beginning even when playback began later.

In facing Mushaf mode, Chapters faces a gilded resume page with the chapter's
Arabic and English names, kept ayah, and return link. Returning resumes the mounted reader at its previous
folio. Settings, repeat, and word details always overlay the left page;
Settings keeps the spread it opened over and returns to it when dismissed.

## Quick start

```bash
cd web
npm install
npm run dev      # http://localhost:5173
npm test         # engine unit tests (Vitest)
npm run typecheck    # tsc alone
npm run build    # static site → dist/
npm run build:pages  # → ../_site/app (CI does this on master)
```

With Playwright installed, `node scripts/check-book-browser.mjs` checks the
return page, overlay placement, keyboard ownership, and Settings navigation
against a running dev server. It uses canonical English verses on synthetic
folios and does not need the live printed-page service.

`node scripts/check-ui-navigation-browser.mjs` checks live System theme changes,
word-sheet focus across Settings and screen resizing, and overlay dismissal
when navigating through chapter controls or browser history.
It also checks that a Repeat dialog above a word sheet stays usable when
resizing from facing pages to a phone layout.

`BROWSER=firefox node scripts/check-mushaf-browser.mjs` checks Arabic opening
and full leaves, responsive margins, and opening/interrupted page turns.
Set `BROWSER=chromium` for the same check in Chromium, `BROWSER_EXECUTABLE`
for an installed test browser, and `RECORD_VIDEO=1` to save a recording.
It also checks chapter cartouches, preface exceptions, grid alignment and
clearance around the measured controls. Sizes include short desktop windows,
portrait tablets and 320/390 px phones, with a resized transport after switching
between phone and spread layouts.
It uses bundled text and a tab-local placement fixture, including the last leaf.

`node scripts/check-reader-performance-browser.mjs` checks chapter handoff,
interrupted navigation, reduced motion, idle animation layers, static ornament
render isolation, and repeat-range control updates. Its render counters use
the development React build; they are not device frame-rate measurements.

Builds and tests are kept short: the type check is incremental (its cache is
in `node_modules/.cache`) and runs beside the bundle (`scripts/parallel.mjs`);
`sync-data` does nothing when the canonical files are unchanged; tests share
one module graph per worker (`isolate: false` in `vite.config.ts`), so a test
that needs a clean module registry must call `vi.resetModules()` itself.

Requires Node 22+. `npm run dev` and `npm run build` derive web assets from the
canonical `../data/quran.db`: a timing-free startup database plus one lazy JSON
corpus per reciter. Android still packages the complete database, and both
surfaces therefore use the same reviewed timing rows without making the web
download every reciter at startup. The browser automatically fills a separate
IndexedDB word/QCF cache through the authenticated Quran Foundation Content
Sync proxy. QF credentials remain in Cloudflare and are never included in the
web bundle.

## Architecture

```
src/domain/     HighlightEngine, HighlightClock, basmalah and search policy
src/data/       WASM SQLite + separate IndexedDB word/QCF cache
src/playback/   Dual HTMLAudioElement + Cache API prefetch + Media Session + rAF clock
src/render/     WordUnit / HafsWord / AyahBlock (directional ink + paper-cover bloom)
src/ui/         paper stack plus Android-mirrored reader/focus/Ink/Fade policy
                ReaderFocusController keeps the playing ayah on its anchor
src/store/      hand-rolled app store (boundary-only React updates)
```

The ink stack includes Android's gate/cruise/hold tajweed curves (madd,
ghunnah, waqf and wasl carry-in), ordered English residuals and audio-bound
orange repeat washes. Tarjīʿ analyzes decoded 8 kHz PCM in a worker: two-sided
AM/FM cycles, event ownership, per-reciter drama admission and the Hani capture
profile. Glyph light and the three-radius halo use Android's light-smoothing
and gamma math. Missing analysis keeps still ink; it never invents a pulse.
Analysis prepares the current and next clip without delaying playback and
retains at most twelve feature tracks. Letter widths use Android's uniform
v1 approximation; exact shaped-letter measurement remains future work.

Pure engines are tested with Android JVM numeric fixtures. An optional native
browser regression check covers viewport occupancy, pause/seek/residual ink,
concurrent repeats, audio decoding/worker analysis and a two-clip handoff:

```bash
# With the dev server running and Playwright available to Node:
node scripts/check-ink-browser.mjs
# PLAYWRIGHT_MODULE can point to an installed Playwright index.mjs;
# CHROMIUM_EXECUTABLE can select a system Chromium.
```

The browser check uses controlled local audio, so separately audition real
reciters when network access is available. No client credentials are needed.
 See
[`docs/WEB.md`](../docs/WEB.md) for the full plan and quality bar.

## Notes

- Cold start opens on the closed mushaf (the loading screen): title wash and
  load progress on the leather while `quran.db` streams in, then the
  isti'adha fading in as text, then the cover lifts open on its left hinge
  toward the reader. Frame, corner seals, medallion, and type scale from a
  48-unit grid on the live board size (`coverLayout`). Tap or Escape skips
  once ready.
- First load downloads the timing-free `quran.db`; the selected reciter's
  corpus loads when a chapter opens. A service worker caches the DB, timing
  JSON, fonts, and hashed assets (cache-first) **only after a successful boot**,
  then warms the DB/wasm into the Cache API. Navigations are **network-first**: a successful
  fetch stashes HTML in a side cache used only when offline, so a deploy cannot
  leave phones on a stale shell while online. Bump `CACHE` /
  `OFFLINE_SHELL` in `public/sw.js` whenever the core database or cache contract
  changes. Recitation audio stays in the separate AudioPrefetch Cache API
  (cross-origin).
- `sql.js`’s browser build requests `sql-wasm-browser.wasm` (copied into
  `public/` on `npm install`). Shipping only `sql-wasm.wasm` 404s on Pages.
- Audio streams from everyayah.com; upcoming ayahs are prefetched in parallel
  (~8 ahead). Desktop browsers promote a blob-backed standby `<audio>` at verse
  joins. iOS uses one persistent element with cache-warmed HTTPS sources to
  avoid WebKit's multi-element/blob playback stalls. Warm current/next clips
  are decoded for their audible bounds; a short equal-power fade-out/fade-in
  joins the padded edges without a pause or a hard cut. Unsupported analysis
  falls back to the normal media-element ending. The play button shows a
  spinner while buffering.
- **Gapless verse joins (default):** verse handoff uses
  [@regosen/gapless-5](https://github.com/regosen/Gapless-5) (HTML5 + Web Audio,
  hard abut — no crossfade). Word highlight still uses the same per-ayah
  `positionMs` clock. Developer mode can disable it to A/B the legacy
  dual-`<audio>` transport.
- Six QF voices fetch complete reviewed timing views from authenticated Content
  Sync through the Worker, then retain them atomically in IndexedDB. They refresh
  after six days and remain available through provider outages. The other seven
  voices keep their packaged lazy timing corpora.
- The QF word/QCF cache bootstraps once, refreshes from its saved checkpoint
  after six days, and withholds those fields after seven days until it can sync.
- Click a word to play from there; right-click / long-press opens the Root Word Viewer.
- Themes: Paper / Nightfall / Royal green (Settings). System follows live OS
  appearance changes, including the browser's theme color.
- Form controls use [Base UI](https://base-ui.com) primitives (`Select`,
  `Switch`, `Slider`, `Input`) styled to the paper kit in `ui/kit/`.
- Escape returns through Settings → Reader → Chapters.
