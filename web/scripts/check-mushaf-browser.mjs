/** Mushaf geometry and opening/return turns against the Vite dev server.
 * BROWSER=firefox|chromium, PLAYWRIGHT_MODULE and BROWSER_EXECUTABLE select
 * the engine. Uses canonical bundled text with tab-local selected page maps;
 * the authenticated content service is not needed for this layout check.
 */
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
const engines = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const engine = process.env.BROWSER ?? 'chromium'
const browser = await engines[engine].launch({
  executablePath: process.env.BROWSER_EXECUTABLE,
  headless: true,
  args: engine === 'chromium' ? ['--no-sandbox'] : [],
})
const out = process.env.ARTIFACT_DIR ?? '/tmp/quran-mushaf-browser'
await mkdir(out, { recursive: true })
try {
  const page = await browser.newPage({
    viewport: { width: 1920, height: 1296 },
    ...(process.env.RECORD_VIDEO ? { recordVideo: { dir: out, size: { width: 1280, height: 864 } } } : {}),
  })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('beautiful-quran-settings', JSON.stringify({
    readingLayout: 'mushaf', readingMode: 'arabic_only', themeMode: 'dark',
    educationGuidesEnabled: true, gapless5Playback: false,
    }))
    localStorage.setItem('beautiful-quran-education-ayah-rail-v1', '1')
    localStorage.setItem('beautiful-quran-education-bookmark-saved-v2', '1')
  })
  await page.goto(process.env.READER_URL ?? 'http://127.0.0.1:5173/', { waitUntil: 'networkidle' })
  await page.locator('.app-shell:not([data-booting])').waitFor()
  await page.keyboard.press('Escape')
  await page.getByRole('dialog', { name: 'The Noble Quran', exact: true }).waitFor({ state: 'detached' })
  await page.evaluate(async () => {
    const load = async path => import(performance.getEntriesByType('resource')
      .find(entry => new URL(entry.name).pathname === path)?.name ?? path)
    window.mushafApp = (await load('/src/store/appStore.ts')).appStore
    const { runtimeMushafCache: cache } = await load('/src/data/runtimeMushaf.ts')
    // [surah, ayah, first word, last word, printed line], pages 1–4.
    const ranges = [
      [[1,1,1,4,2],[1,2,1,4,3],[1,3,1,2,4],[1,4,1,3,4],[1,5,1,4,5],[1,6,1,1,5],[1,6,2,3,6],[1,7,1,3,6],[1,7,4,7,7],[1,7,8,9,8]],
      [[2,1,1,1,3],[2,2,1,6,3],[2,2,7,7,4],[2,3,1,5,4],[2,3,6,8,5],[2,4,1,4,5],[2,4,5,12,6],[2,5,1,6,7],[2,5,7,8,8]],
      [[2,6,1,9,1],[2,6,10,11,2],[2,7,1,7,2],[2,7,8,12,3],[2,8,1,2,3],[2,8,3,11,4],[2,9,1,8,5],[2,9,9,10,6],[2,10,1,6,6],[2,10,7,12,7],[2,11,1,3,7],[2,11,4,11,8],[2,12,1,7,9],[2,13,1,2,9],[2,13,3,12,10],[2,13,13,19,11],[2,14,1,2,11],[2,14,3,12,12],[2,14,13,16,13],[2,15,1,4,13],[2,15,5,7,14],[2,16,1,4,14],[2,16,5,11,15]],
      [[2,17,1,9,1],[2,17,10,17,2],[2,18,1,1,2],[2,18,2,6,3],[2,19,1,5,3],[2,19,6,14,4],[2,19,15,19,5],[2,20,1,3,5],[2,20,4,13,6],[2,20,14,23,7],[2,20,24,25,8],[2,21,1,6,8],[2,21,7,11,9],[2,22,1,3,9],[2,22,4,12,10],[2,22,13,22,11],[2,22,23,23,12],[2,23,1,8,12],[2,23,9,17,13],[2,23,18,20,14],[2,24,1,6,14],[2,24,7,13,15]],
      [[9,1,1,6,2]],
      [[81,1,1,3,1]],
      [[112,1,1,4,3],[112,2,1,2,3],[112,3,1,2,3],[112,3,3,4,4],[112,4,1,5,4],[113,1,1,4,7],[113,2,1,4,7],[113,3,1,1,7],[113,3,2,5,8],[113,4,1,4,8],[113,4,5,5,9],[113,5,1,5,9],[114,1,1,4,12],[114,2,1,2,12],[114,3,1,1,12],[114,3,2,2,13],[114,4,1,4,13],[114,5,1,1,13],[114,5,2,5,14],[114,6,1,3,15]],
    ]
    const numbers = [1, 2, 3, 4, 187, 586, 604]
    const pages = new Map(ranges.map((lines, index) => [numbers[index], lines.flatMap(([s, a, first, last, line]) =>
      Array.from({ length: last - first + 1 }, (_, offset) => ({
        surah_id: s, ayah_number: a, position: first + offset, qcf_page: numbers[index],
        ayah_page: numbers[index], qcf_line: line, qcf_span_end: first + offset,
      })))]))
    cache.layoutReady = () => true
    cache.pageWords = p => pages.get(p) ?? []
    cache.pageOfAyah = (s, a) => [...pages].find(([, rows]) => rows.some(r => r.surah_id === s && r.ayah_number === a))?.[0] ?? 1
    // TurningLeaf captures in a parent layout effect. The band must already
    // contain its paths there, rather than waiting for a later React render.
    const react = await load('/node_modules/.vite/deps/react.js')
    const client = await load('/node_modules/.vite/deps/react-dom_client.js')
    const { createElement, useLayoutEffect } = react.default ?? react
    const { createRoot } = client.default ?? client
    const { MushafChapterBand } = await load('/src/ui/reader/MushafChapterBand.tsx')
    const { QuranRepository } = await load('/src/data/repository.ts')
    const host = document.createElement('div')
    host.style.cssText = 'position:absolute;width:400px;height:60px;font-size:24px'
    document.body.append(host)
    function Capture() {
      useLayoutEffect(() => { window.cartoucheAtCapture = host.querySelectorAll('svg path').length }, [])
      return createElement(MushafChapterBand, { surah: QuranRepository.surahContent(1).surah })
    }
    const root = createRoot(host)
    root.render(createElement(Capture))
    for (let frame = 0; window.cartoucheAtCapture == null && frame < 120; frame++) await new Promise(requestAnimationFrame)
    root.unmount()
    host.remove()
  })
  assert(await page.evaluate(() => window.cartoucheAtCapture > 0), 'Cartouche absent when a turning parent captures it')
  const settle = async () => {
    await page.waitForFunction(() => !document.querySelector('.mushaf-flip[data-dir], .app-shell[data-air]'))
    await page.waitForTimeout(200)
  }
  // Observe the flight before navigation; a busy browser can finish it
  // before a subsequent locator command sees the transient canvas.
  const observeTurn = async (interrupt = false) => page.evaluate(interrupt => {
    window.sawMushafTurn = false
    const observer = new MutationObserver(() => {
      if (!document.querySelector('.mushaf-flip[data-dir]')) return
      observer.disconnect()
      window.sawMushafTurn = true
      if (interrupt) window.mushafApp.setSheet('home')
    })
    observer.observe(document.body, { childList: true, subtree: true })
  }, interrupt)
  const checkLeaf = async () => {
    const geometry = await page.evaluate(() => {
      const leaves = [...document.querySelectorAll('.mushaf[data-side]')]
        .filter(el => !el.closest('.mushaf-flip, .mushaf-fit-probes'))
      return leaves.map(leaf => {
        const rows = [...leaf.querySelectorAll('.mushaf-line')]
        const lines = leaf.querySelector('.mushaf-lines')
        const slots = [...lines.children]
        const head = leaf.querySelector('.mushaf-head')
        const leafStyle = getComputedStyle(leaf)
        const font = parseFloat(getComputedStyle(lines).fontSize)
        return {
          spacing: rows.filter(row => row.children.length > 1).map(row => ({
            word: parseFloat(row.style.getPropertyValue('--word-gap')),
            mark: parseFloat(row.style.getPropertyValue('--mark-gap')),
            centered: row.hasAttribute('data-short') || lines.dataset.opening === 'true',
            fillError: row.getBoundingClientRect().width -
              (row.firstElementChild.getBoundingClientRect().right - row.lastElementChild.getBoundingClientRect().left),
          })),
          side: leaf.dataset.side,
          top: leaf.getBoundingClientRect().top,
          height: leaf.clientHeight,
          font,
          pitch: Math.min(...rows.map(row => row.getBoundingClientRect().height)),
          center: (slots[0].getBoundingClientRect().top + slots.at(-1).getBoundingClientRect().bottom) / 2,
          wellCenter: (lines.getBoundingClientRect().top + lines.getBoundingClientRect().bottom) / 2,
          opening: lines.dataset.opening === 'true',
          headStart: head.getBoundingClientRect().left - leaf.getBoundingClientRect().left - parseFloat(leafStyle.paddingLeft),
          headEnd: leaf.getBoundingClientRect().right - head.getBoundingClientRect().right - parseFloat(leafStyle.paddingRight),
          titles: [...leaf.querySelectorAll('.mushaf-chapter-band')].map(band => Number(band.dataset.chapter)),
          basmalahs: leaf.querySelectorAll('.mushaf-basmalah').length,
        }
      })
    })
    assert.equal(geometry.length, 2, JSON.stringify(geometry))
    for (const leaf of geometry) {
      for (const spacing of leaf.spacing) {
        assert(spacing.mark >= 0 && spacing.mark < spacing.word, JSON.stringify(spacing))
        if (leaf.opening) assert(spacing.word / leaf.font <= 0.221, JSON.stringify(spacing))
        if (!spacing.centered) assert(Math.abs(spacing.fillError) < 1, JSON.stringify(spacing))
      }
      assert(leaf.height > leaf.font * 15 * 1.7, JSON.stringify(leaf))
      assert(leaf.pitch >= leaf.font * 1.7, JSON.stringify(leaf))
      assert(Math.abs(leaf.headStart) < 1 && Math.abs(leaf.headEnd) < 1, JSON.stringify(leaf))
      if (leaf.opening) assert(Math.abs(leaf.center - leaf.wellCenter) < 2, JSON.stringify(leaf))
    }
    assert(Math.abs(geometry[0].top - geometry[1].top) < 1, JSON.stringify(geometry))
    assert.equal(await page.locator('.reader-top-title, .reader-top-medallion').count(), 0, 'Mushaf must not carry the scroll reader toolbar title')
    return geometry
  }
  await page.evaluate(() => window.mushafApp.openReading(1, 1))
  await page.locator('.mushaf[data-side="recto"]').waitFor()
  await settle()
  if (process.env.LEGACY_SCALE) await page.evaluate(() => {
    document.querySelector('.app-shell').style.setProperty('--furn', 'max(0.85, calc(var(--u) / 27.5px))')
  })
  await page.screenshot({ path: `${out}/${engine}-opening.png` })
  console.log('Opening geometry', await checkLeaf())
  const opening = await checkLeaf()
  assert.deepEqual(opening.map(leaf => leaf.titles), [[1], [2]])
  assert.deepEqual(opening.map(leaf => leaf.basmalahs), [0, 1])
  assert.equal(await page.locator('.reader-word-hint').count(), 0)
  await page.getByRole('button', { name: 'Next pages', exact: true }).click()
  await settle()
  await checkLeaf()
  await page.screenshot({ path: `${out}/${engine}-full-page.png` })
  await page.getByRole('button', { name: 'Previous pages', exact: true }).click()
  await settle()
  await checkLeaf()
  // The first opening leaves carry no pile; a later page must lift Chapters
  // with a pile, land, return, and survive a quick opposite navigation.
  await page.evaluate(() => window.mushafApp.setSheet('home'))
  await settle()
  await observeTurn()
  await page.evaluate(() => window.mushafApp.openReading(2, 17))
  await page.waitForFunction(() => window.sawMushafTurn)
  await page.screenshot({ path: `${out}/${engine}-turning.png` })
  await settle()
  await checkLeaf()
  // Resize the same mounted book: the golden grid and the 0.85 furniture
  // floor must both remain usable, including a narrow tablet spread.
  for (const viewport of [{ width: 1280, height: 800 }, { width: 744, height: 1133 }]) {
    await page.setViewportSize(viewport)
    await page.waitForTimeout(400)
    await checkLeaf()
  }
  await page.setViewportSize({ width: 1920, height: 1296 })
  await page.waitForTimeout(400)
  assert.equal(await page.locator('.mushaf-flip-canvas').count(), 0)
  assert.equal(await page.locator('.sheet[data-name="home"]').evaluate(el => el.inert), true)
  // Reverse a chapter-opening turn while its pile is still airborne.
  await page.evaluate(() => window.mushafApp.setSheet('home'))
  await settle()
  await observeTurn(true)
  await page.evaluate(() => window.mushafApp.setSheet('reader'))
  await page.waitForFunction(() => window.sawMushafTurn)
  await settle()
  assert.equal(await page.locator('.app-shell').getAttribute('data-stack'), '0')
  await page.evaluate(() => window.mushafApp.setSheet('reader'))
  await settle()
  await checkLeaf()
  for (const [surah, expected] of [[9, [9]], [81, [81]], [112, [112, 113, 114]]]) {
    await page.evaluate(s => window.mushafApp.openReading(s, 1), surah)
    await settle()
    const leaves = await checkLeaf()
    assert.deepEqual(leaves.flatMap(leaf => leaf.titles), expected)
    assert.equal(leaves.reduce((n, leaf) => n + leaf.basmalahs, 0), surah === 9 ? 0 : expected.length)
  }
  await page.screenshot({ path: `${out}/${engine}-three-chapters.png` })
  await page.evaluate(() => window.mushafApp.updateSettings({ readingMode: 'english_only', pageNumberScript: 'english' }))
  await settle()
  const english = page.locator('.mushaf[data-side="verso"] .mushaf-chapter-name')
  assert.equal(await english.count(), 3)
  assert.equal(await english.first().getAttribute('lang'), 'en')
  await page.evaluate(() => window.mushafApp.updateSettings({ readingLayout: 'scroll' }))
  await page.locator('.reader-top-title .reader-top-medallion').waitFor({ state: 'attached' })
  assert.equal(await page.locator('.reader-top-title').count(), 1, 'Scrolling reader keeps its chapter title')
  assert.deepEqual(errors, [])
  console.log(`${engine}: opening, full leaves, forward/back and chapter opening passed`)
} finally {
  await browser.close()
}
