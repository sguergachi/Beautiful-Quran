# Beautiful Quran — Web

Browser port of Beautiful Quran: the same Focus / Highlight / Ink engines,
the same paper metaphor, and word-by-word highlighting synced to
recitation audio.

## Live

https://sguergachi.github.io/Beautiful-Quran-/app/

Linked from the project homepage as **Open web reader**.

GitHub Actions stages the marketing content from `docs/` and builds the reader
under `/app/`, then deploys the combined tree as a GitHub Pages artifact. Build
output is never committed back to `master`.

## Quick start

```bash
cd web
npm install
npm run dev      # http://localhost:5173
npm test         # engine unit tests (Vitest)
npm run build    # static site → dist/
npm run build:pages  # → ../_site/app (CI does this on master)
```

Requires Node 20+. `npm run dev` and `npm run build` copy the canonical
`../data/quran.db` into the generated web assets. The database is committed
once and shared with Android; there is no second data pipeline.

## Architecture

```
src/domain/     HighlightEngine, HighlightClock, basmalah and search policy
src/data/       WASM SQLite (sql.js) over quran.db + settings/bookmarks
src/playback/   Dual HTMLAudioElement + Cache API prefetch + Media Session + rAF clock
src/render/     WordUnit / HafsWord / AyahBlock (directional ink + paper-cover bloom)
src/ui/         paper stack plus Android-mirrored reader/focus/Ink/Fade policy
                ReaderFocusController keeps the playing ayah on its anchor
src/store/      hand-rolled app store (boundary-only React updates)
```

Engines are DOM-free and unit-tested against the Android JVM suites. See
[`docs/WEB.md`](../docs/WEB.md) for the full plan and quality bar.

## Notes

- Cold start opens on the closed mushaf (the loading screen): title wash and
  load progress on the leather while `quran.db` streams in, then the
  isti'adha fading in as text, then the cover lifts open on its left hinge
  toward the reader. Frame, corner seals, monogram, and type scale from a
  48-unit grid on the live board size (`coverLayout`). Tap or Escape skips
  once ready.
- First load downloads `quran.db`; a service worker caches the DB, fonts, and
  hashed assets (cache-first) **only after a successful boot**, then warms the
  DB/wasm into the Cache API. Navigations are **network-first**: a successful
  fetch stashes HTML in a side cache used only when offline, so a deploy cannot
  leave phones on a stale shell while online. Bump `CACHE` /
  `OFFLINE_SHELL` in `public/sw.js` when changing that contract. Recitation
  audio stays in the separate AudioPrefetch Cache API (cross-origin).
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
- Click a word to play from there; right-click / long-press opens the Root Word Viewer.
- Themes: Paper / Nightfall / Royal green (Settings).
- Form controls use [Base UI](https://base-ui.com) primitives (`Select`,
  `Switch`, `Slider`, `Input`) styled to the paper kit in `ui/kit/`.
- Escape returns through Settings → Reader → Chapters.
