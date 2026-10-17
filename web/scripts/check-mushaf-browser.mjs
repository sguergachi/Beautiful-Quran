/** Mushaf geometry and opening/return turns against the Vite dev server.
 * BROWSER=firefox|chromium, PLAYWRIGHT_MODULE and BROWSER_EXECUTABLE select
 * the engine. Uses canonical bundled text with a tab-local four-page map;
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
  await page.addInitScript(() => localStorage.setItem('beautiful-quran-settings', JSON.stringify({
    readingLayout: 'mushaf', readingMode: 'arabic_only', themeMode: 'dark',
    educationGuidesEnabled: false, gapless5Playback: false,
  })))
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
    ]
    const pages = ranges.map((lines, index) => lines.flatMap(([s, a, first, last, line]) =>
      Array.from({ length: last - first + 1 }, (_, offset) => ({
        surah_id: s, ayah_number: a, position: first + offset, qcf_page: index + 1,
        ayah_page: index + 1, qcf_line: line, qcf_span_end: first + offset,
      }))))
    cache.layoutReady = () => true
    cache.pageWords = p => pages[p - 1] ?? []
    cache.pageOfAyah = (s, a) => pages.findIndex(rows => rows.some(r => r.surah_id === s && r.ayah_number === a)) + 1 || 1
  })
  const settle = async () => {
    await page.waitForFunction(() => !document.querySelector('.mushaf-flip[data-dir], .app-shell[data-air]'))
    await page.waitForTimeout(200)
  }
  const checkLeaf = async () => {
    const geometry = await page.evaluate(() => {
      const leaves = [...document.querySelectorAll('.mushaf[data-side]')]
        .filter(el => !el.closest('.mushaf-flip, .mushaf-fit-probes'))
      return leaves.map(leaf => {
        const rows = [...leaf.querySelectorAll('.mushaf-line')]
        const lines = leaf.querySelector('.mushaf-lines')
        const font = parseFloat(getComputedStyle(lines).fontSize)
        return {
          side: leaf.dataset.side,
          top: leaf.getBoundingClientRect().top,
          height: leaf.clientHeight,
          font,
          pitch: Math.min(...rows.map(row => row.getBoundingClientRect().height)),
          center: (rows[0].getBoundingClientRect().top + rows.at(-1).getBoundingClientRect().bottom) / 2,
          wellCenter: (lines.getBoundingClientRect().top + lines.getBoundingClientRect().bottom) / 2,
          opening: lines.dataset.opening === 'true',
        }
      })
    })
    assert.equal(geometry.length, 2, JSON.stringify(geometry))
    for (const leaf of geometry) {
      assert(leaf.height > leaf.font * 15 * 1.7, JSON.stringify(leaf))
      assert(leaf.pitch >= leaf.font * 1.7, JSON.stringify(leaf))
      if (leaf.opening) assert(Math.abs(leaf.center - leaf.wellCenter) < 2, JSON.stringify(leaf))
    }
    assert(Math.abs(geometry[0].top - geometry[1].top) < 1, JSON.stringify(geometry))
    const medallion = await page.locator('.reader-top-medallion').boundingBox()
    assert(medallion.width >= 15 && medallion.width < 40, JSON.stringify(medallion))
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
  await page.evaluate(() => window.mushafApp.openReading(2, 17))
  await page.locator('.mushaf-flip[data-dir]').waitFor()
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
  await page.evaluate(() => window.mushafApp.setSheet('reader'))
  await page.locator('.mushaf-flip[data-dir]').waitFor()
  await page.evaluate(() => window.mushafApp.setSheet('home'))
  await settle()
  assert.equal(await page.locator('.app-shell').getAttribute('data-stack'), '0')
  await page.evaluate(() => window.mushafApp.setSheet('reader'))
  await settle()
  await checkLeaf()
  assert.deepEqual(errors, [])
  console.log(`${engine}: opening, full leaves, forward/back and chapter opening passed`)
} finally {
  await browser.close()
}
