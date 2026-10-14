// Render a key-art Reel: step train_window.html over a captured reader run.
//
//   node render_scene.mjs --variant nightfall_line --frames /tmp/cap/rahman-dark \
//     --start 0.5 --end 66.9 --audio mix.wav --out reel.mp4 \
//     [--break 25.1 --stars 31.1 --open 58.73] [--still 28.0 --still-out cover.jpg]
//
// Times are seconds on the capture's timeline (frame / fps), so cue times can
// be read straight from capture.mjs's audio-log.json.
import { chromium } from 'playwright'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const args = Object.fromEntries(process.argv.slice(2).join(' ').split('--').filter(Boolean).map((s) => { const [k, ...v] = s.trim().split(' '); return [k, v.join(' ')] }))
const fps = +(args.fps ?? 30)
const here = path.dirname(fileURLToPath(import.meta.url))
const cue = { breakAt: +(args.break ?? 1e9), starsAt: +(args.stars ?? 1e9), openAt: +(args.open ?? 1e9) }
const frameFile = (i) => path.join(args.frames, `f${String(Math.max(0, i)).padStart(5, '0')}.png`)
const lastFrame = fs.readdirSync(args.frames).filter((f) => f.endsWith('.png')).length - 1

const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome-stable',
  args: ['--allow-file-access-from-files'],
})
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } })
page.on('pageerror', (e) => console.error('[page]', e.message))
await page.goto('file://' + path.join(here, 'train_window.html'))
await page.waitForFunction(() => window.ready)
await page.evaluate((v) => window.setVariant(v), args.variant)
const cdp = await page.context().newCDPSession(page)
const draw = async (t) => {
  const i = Math.min(lastFrame, Math.round(t * fps))
  await page.evaluate(([t, url, cue]) => window.renderFrame({ t, frameUrl: url, ...cue }), [t, 'file://' + frameFile(i), cue])
}
const grab = async (format = 'png') => Buffer.from((await cdp.send('Page.captureScreenshot', { format, quality: format === 'jpeg' ? 94 : undefined })).data, 'base64')

if (args.still) {
  await draw(+args.still)
  fs.writeFileSync(args['still-out'], await grab('jpeg'))
  console.log('still', args['still-out'])
}
if (args.out) {
  const start = +args.start, end = +args.end
  const n = Math.round((end - start) * fps)
  const ff = spawn('ffmpeg', ['-v', 'error', '-y', '-f', 'image2pipe', '-c:v', 'png', '-framerate', String(fps), '-i', '-',
    ...(args.audio ? ['-i', args.audio] : []),
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '17', '-maxrate', '12M', '-bufsize', '24M',
    '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-movflags', '+faststart',
    ...(args.audio ? ['-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-shortest'] : []),
    args.out], { stdio: ['pipe', 'inherit', 'inherit'] })
  const t0 = Date.now()
  for (let k = 0; k < n; k++) {
    await draw(start + k / fps)
    const png = await grab()
    if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once('drain', r))
    if (k % (fps * 10) === 0) console.log(`${args.variant}: ${k}/${n} (${((Date.now() - t0) / 1000).toFixed(0)}s)`)
  }
  ff.stdin.end()
  await new Promise((r) => ff.on('close', r))
  console.log('wrote', args.out)
}
await browser.close()
