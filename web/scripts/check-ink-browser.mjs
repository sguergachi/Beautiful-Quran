/** Optional browser regression check. Run the Vite dev server first.
 * Requires Playwright (PLAYWRIGHT_MODULE may point to an installed index.mjs).
 * CHROMIUM_EXECUTABLE selects a system browser instead of Playwright's bundle.
 * Audio is deterministic local PCM: this does not certify external reciters.
 */
import assert from 'node:assert/strict'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const pcm = Buffer.alloc(44 + 32000 * 2)
pcm.write('RIFF', 0)
pcm.writeUInt32LE(pcm.length - 8, 4)
pcm.write('WAVEfmt ', 8)
pcm.writeUInt32LE(16, 16)
pcm.writeUInt16LE(1, 20)
pcm.writeUInt16LE(1, 22)
pcm.writeUInt32LE(8000, 24)
pcm.writeUInt32LE(16000, 28)
pcm.writeUInt16LE(2, 32)
pcm.writeUInt16LE(16, 34)
pcm.write('data', 36)
pcm.writeUInt32LE(64000, 40)
for (let i = 0; i < 32000; i++)
  pcm.writeInt16LE(
    Math.round(
      6500 *
        (1 + 0.2 * Math.sin((2 * Math.PI * 5 * i) / 8000)) *
        Math.sin((2 * Math.PI * 150 * i) / 8000),
    ),
    44 + i * 2,
  )

async function main() {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_EXECUTABLE,
    headless: true,
    args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'],
  })
  try {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
    })
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    const joined = Buffer.from(pcm.subarray(0, 44 + 16000))
    joined.writeUInt32LE(joined.length - 8, 4)
    joined.writeUInt32LE(16000, 40)
    await page.route('**/join-*.wav', (r) =>
      r.fulfill({ body: joined, contentType: 'audio/wav' }),
    )
    await page.route('**/fixture.wav', (r) =>
      r.fulfill({ body: pcm, contentType: 'audio/wav' }),
    )
    await page.goto(process.env.READER_URL ?? 'http://127.0.0.1:5173/', {
      waitUntil: 'networkidle',
    })
    await page.keyboard.press('Escape')
    await page.waitForTimeout(1100)
    for (const [width, height] of [
      [1440, 900],
      [1920, 1080],
      [2560, 1080],
      [768, 1024],
      [390, 844],
    ]) {
      await page.setViewportSize({ width, height })
      await page.waitForTimeout(1200)
      const result = await page.evaluate(() => {
        const shell = document.querySelector('.app-shell')
        const home = document.querySelector('.sheet[data-name="home"]'),
          reader = document.querySelector('.sheet[data-name="reader"]')
        const a = home.getBoundingClientRect(),
          b = reader.getBoundingClientRect()
        return {
          width: innerWidth,
          spread: shell.dataset.spread === 'true',
          ratio: (a.width + b.width) / innerWidth,
          top: b.top,
          bottom: b.bottom,
          readerWidth: b.width,
        }
      })
      if (result.spread) {
        assert(result.ratio >= 0.799, JSON.stringify(result))
        assert(result.top >= 0)
        assert(result.bottom <= height + 1)
      } else assert(result.readerWidth >= width - 1, JSON.stringify(result))
      console.log('layout', result)
    }
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.waitForTimeout(1200)
    const setup = await page.evaluate(async () => {
      const { default: React } = await import(
        '/node_modules/.vite/deps/react.js'
      )
      const { default: client } = await import(
        '/node_modules/.vite/deps/react-dom_client.js'
      )
      const { createRoot } = client
      const { default: dom } = await import(
        '/node_modules/.vite/deps/react-dom.js'
      )
      const { flushSync } = dom
      const { HafsWord } = await import('/src/render/HafsWord.tsx')
      const { WordUnit } = await import('/src/render/WordUnit.tsx')
      const { WordMotionProvider } = await import(
        '/src/render/WordMotionContext.tsx'
      )
      const hookCode = await (await fetch('/src/render/useWordWash.ts')).text()
      const signalPath = hookCode.match(
        /import \{ tarjiSignal \} from "([^"]+)"/,
      )[1]
      const { tarjiSignal } = await import(signalPath)
      const { normalizeSettings } = await import('/src/data/settings.ts')
      await tarjiSignal.prepare('/fixture.wav', 1, {
        ensure: async () => '/fixture.wav',
      })
      tarjiSignal.setClock('/fixture.wav', 2400, true, 1)
      if (tarjiSignal.sample().gain < 0.3)
        throw Error('Native decode / worker did not prepare a pulse')
      document.documentElement.dataset.theme = 'dark'
      const host = document.createElement('div')
      host.id = 'ink-harness'
      host.style.cssText =
        'position:fixed;inset:100px;background:var(--paper);z-index:99999;padding:60px;font-size:48px'
      document.body.append(host)
      const root = createRoot(host)
      let now = 0
      window.inkRender = (
        state = 'Active',
        activation = 0,
        english = false,
      ) => {
        flushSync(() =>
          root.render(
            React.createElement(
              WordMotionProvider,
              null,
              React.createElement(english ? WordUnit : HafsWord, {
                word: {
                  arabic: 'ٱلضَّآلِّينَ',
                  translation: 'those who have gone astray',
                  transliteration: '',
                  position: 1,
                },
                ink: { state, repeat: false },
                sweepMs: 1400,
                activation,
                motion: {
                  key: '1:7:1',
                  timing: { startMs: 0, spokenMs: 1400, durationMs: 1400 },
                  isAyahFinal: true,
                },
                englishMode: english,
                showGloss: false,
                showTransliteration: false,
                onPlay() {},
                onHold() {},
              }),
            ),
          ),
        )
      }
      window.twoRender = (first = 'Active', second = 'Upcoming') => {
        flushSync(() =>
          root.render(
            React.createElement(
              WordMotionProvider,
              null,
              ...[first, second].map((state, i) =>
                React.createElement(HafsWord, {
                  key: i,
                  word: {
                    arabic: i ? 'وَقَالَ' : 'مِنۡ',
                    translation: '',
                    transliteration: '',
                    position: i + 1,
                  },
                  ink: { state, repeat: i === 0 || second === 'Active' },
                  sweepMs: 1400,
                  activation: 1,
                  motion: {
                    key: `1:2:${i + 1}`,
                    timing:
                      state === 'Active'
                        ? {
                            startMs: i ? 100 : 0,
                            spokenMs: i ? 100 : 400,
                            durationMs: i ? 100 : 400,
                          }
                        : null,
                    isAyahFinal: false,
                  },
                  onPlay() {},
                  onHold() {},
                }),
              ),
            ),
          ),
        )
      }
      window.clock = (ms, playing = true) => {
        now = ms
        tarjiSignal.setClock('/fixture.wav', now, playing, 1, 1)
      }
      window.mask = () =>
        document.querySelector('#ink-harness .ink-paper-cover')?.style.maskImage
      window.clock(0)
      window.inkRender()
      return { font: normalizeSettings().fontScale }
    })
    assert.equal(setup.font, 0.95)
    await page.waitForTimeout(50)
    await page.evaluate(() => window.clock(400))
    await page.waitForTimeout(50)
    const partial = await page.evaluate(() => window.mask())
    assert(partial.includes('linear-gradient'), partial)
    await page.evaluate(() => window.clock(400, false))
    await page.waitForTimeout(180)
    assert.equal(
      await page.evaluate(() => window.mask()),
      partial,
      'pause must freeze the base wash',
    )
    await page.evaluate(() => {
      window.clock(400, true)
      window.inkRender('Recited')
    })
    await page.waitForTimeout(50)
    assert(
      (await page.evaluate(() => window.mask())).includes('linear-gradient'),
      'residual must survive handoff',
    )
    await page.evaluate(() => window.inkRender('Upcoming'))
    await page.waitForTimeout(50)
    assert.equal(
      await page.evaluate(() => window.mask()),
      '',
      'seek must abandon residual',
    )
    await page.evaluate(() => {
      window.clock(0)
      window.inkRender('Active', 1)
    })
    await page.waitForTimeout(20)
    assert(
      (await page.evaluate(() => window.mask())).includes('linear-gradient'),
      'seek reactivation must arm before paint',
    )
    await page.evaluate(() => window.clock(1500))
    await page.waitForTimeout(100)
    assert.equal(
      await page.evaluate(() => window.mask()),
      '',
      'completion must clear paper',
    )
    await page.evaluate(() => {
      window.clock(0)
      window.twoRender()
    })
    await page.waitForTimeout(30)
    await page.evaluate(() => {
      window.clock(100)
      window.twoRender('Recited', 'Active')
    })
    await page.waitForTimeout(30)
    const repeats = () =>
      page.evaluate(() =>
        [...document.querySelectorAll('#ink-harness .hafs-repeat-overlay')].map(
          (e) => ({ mask: e.style.maskImage, opacity: e.style.opacity }),
        ),
      )
    let layers = await repeats()
    assert(layers[0].mask.includes('gradient'))
    assert(layers[1].mask.includes('gradient'))
    await page.evaluate(() => window.clock(210))
    await page.waitForTimeout(40)
    layers = await repeats()
    assert(
      layers[0].mask.includes('gradient'),
      'prior residual must keep finishing',
    )
    assert.equal(
      layers[1].mask,
      '',
      'current repeated word must finish without waiting for prior residual',
    )
    console.log('concurrent repeat wash passed')
    const joins = await page.evaluate(async () => {
      const { Gapless5Backend } = await import(
        '/src/playback/gapless5Backend.ts'
      )
      const events = [],
        time = []
      let finish
      const done = new Promise((resolve) => {
        finish = resolve
      })
      const backend = new Gapless5Backend({
        onTime: (ms, d, index) => time.push({ ms, index }),
        onPlay: () => events.push('play'),
        onPause: () => events.push('pause'),
        onIndex: (i) => events.push(i),
        onFinishedAll: () => finish(),
        onError: (e) => finish(e),
        onBuffering() {},
      })
      try {
        await backend.ensureReady()
        await backend.resumeContext()
        backend.syncPlaylist(
          [1, 2].map((i) => ({
            ayah: i,
            url: location.origin + `/join-${i}.wav`,
          })),
          {},
        )
        backend.goto(0, true)
        const timeout = new Promise((resolve) =>
          setTimeout(() => resolve('join timeout'), 6000),
        )
        const error = await Promise.race([done, timeout])
        return { error, events, time }
      } finally {
        backend.dispose()
      }
    })
    assert.equal(joins.error, undefined, JSON.stringify(joins))
    assert(joins.events.includes(1), JSON.stringify(joins))
    assert(!joins.events.includes('pause'), JSON.stringify(joins))
    const incoming = joins.time.find((t) => t.index === 1)
    assert(incoming && incoming.ms < 250, JSON.stringify(joins))
    console.log('native two-verse gapless handoff passed')
    if (process.env.INK_SCREENSHOT)
      await page.screenshot({ path: process.env.INK_SCREENSHOT })
    console.log('ink lifecycle passed; page errors', errors)
    assert.deepEqual(errors, [])
  } finally {
    await browser.close()
  }
}
await main()
