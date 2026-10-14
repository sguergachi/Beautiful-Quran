// Frame-stepped capture of the web reader (docs/KEY_ART.md).
//
//   node capture.mjs --hash 55:1 --theme light --out DIR --seconds 68 \
//     [--until-segment 10 --tail 2] [--lead 1.0] [--url http://localhost:4317/]
//
// Serve a built reader first (`npm run build && npx vite preview --port 4317`
// in web/). vclock.js virtualises time so each PNG lands exactly 1/fps apart
// and the ink wash is the engine's own output, never a dropped frame. Every
// audio segment played is logged to DIR/audio-log.json (mix_audio.py).
import { chromium } from 'playwright'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const here = path.dirname(fileURLToPath(import.meta.url))

const args = Object.fromEntries(process.argv.slice(2).join(' ').split('--').filter(Boolean).map((s) => { const [k, ...v] = s.trim().split(' '); return [k, v.join(' ')] }))
const hash = args.hash ?? '55:1'
const theme = args.theme ?? 'light'
const out = args.out ?? path.join(process.env.HOME, '.cache', 'bq-key-art', 'frames')
const fps = +(args.fps ?? 30)
const maxSeconds = +(args.seconds ?? 60)
const lead = +(args.lead ?? 1.0)
const tail = +(args.tail ?? 1.5)
// Segments are prefetched blob URLs, so count them: 0 is the basmalah, k is verse k.
const untilSegment = args['until-segment'] != null ? +args['until-segment'] : null
const width = +(args.width ?? 412), height = +(args.height ?? 915), dpr = +(args.dpr ?? 2.625)
// A persistent profile keeps the downloaded Quran data between runs.
const profile = args.profile ?? path.join(process.env.HOME, '.cache', 'bq-key-art', `profile-${theme}`)
fs.rmSync(out, { recursive: true, force: true }); fs.mkdirSync(out, { recursive: true })

const settings = {
  themeMode: theme, readingMode: 'arabic_english', readingLayout: args.layout ?? 'scroll', showWordGloss: true,
  educationGuidesEnabled: false, gapless5Playback: false, reciterId: +(args.reciter ?? 1), playbackSpeed: 1,
  fontScale: +(args.font ?? 1),
}
const ctx = await chromium.launchPersistentContext(profile, {
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome-stable', headless: true,
  args: ['--autoplay-policy=no-user-gesture-required', '--disable-web-security', '--hide-scrollbars'],
  viewport: { width, height }, deviceScaleFactor: dpr, isMobile: true, hasTouch: true,
  colorScheme: theme === 'light' ? 'light' : 'dark',
})
await ctx.addInitScript({ path: path.join(here, 'vclock.js') })
await ctx.addInitScript((s) => {
  const k = 'beautiful-quran-settings'
  const cur = JSON.parse(localStorage.getItem(k) || '{}')
  localStorage.setItem(k, JSON.stringify({ ...cur, ...s }))
  for (const m of ['word-hold-v1', 'bookmark-saved-v2', 'ayah-rail-v1']) localStorage.setItem('beautiful-quran-education-' + m, '1')
}, settings)
const page = ctx.pages()[0] ?? (await ctx.newPage())
page.on('console', (m) => { if (m.type() === 'error') console.log('[page]', m.text().slice(0, 200)) })
await page.goto(`${args.url ?? 'http://localhost:4317/'}#${hash}`, { waitUntil: 'load' })
const playBtn = page.locator('button[aria-label="Play"]').last()
await playBtn.waitFor({ state: 'visible', timeout: 120000 })
await page.waitForTimeout(+(args.settle ?? 6000))
const cdp = await ctx.newCDPSession(page)
const shoot = async (i) => {
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', fromSurface: true, captureBeyondViewport: false, clip: { x: 0, y: 0, width, height, scale: dpr } })
  fs.writeFileSync(path.join(out, `f${String(i).padStart(5, '0')}.png`), Buffer.from(data, 'base64'))
}
await page.evaluate(() => window.__vc.enable())
const dt = 1000 / fps
let i = 0
const t0 = Date.now()
for (; i < Math.round(lead * fps); i++) { await page.evaluate((d) => window.__vc.step(d), dt); await shoot(i) }
await playBtn.click({ noWaitAfter: true, force: true })
let endAt = null
const total = Math.round(maxSeconds * fps)
for (; i < total; i++) {
  await page.evaluate((d) => window.__vc.step(d), dt)
  await shoot(i)
  if (untilSegment != null && endAt == null) {
    const closed = await page.evaluate(() => window.__vc.log.filter((e) => e.kind === 'stop' || e.kind === 'end').length)
    if (closed > untilSegment) endAt = i + Math.round(tail * fps)
  }
  if (endAt != null && i >= endAt) { i++; break }
  if (i % (fps * 5) === 0) console.log(`frame ${i} (${(i / fps).toFixed(1)}s) wall ${((Date.now() - t0) / 1000).toFixed(0)}s`)
}
const log = await page.evaluate(() => ({ log: window.__vc.log, t: window.__vc.t }))
const playFrame = Math.round(lead * fps)
fs.writeFileSync(path.join(out, 'audio-log.json'), JSON.stringify({ fps, frames: i, playFrame, ...log }, null, 1))
console.log(`done: ${i} frames, wall ${((Date.now() - t0) / 1000).toFixed(0)}s`)
await ctx.close()
