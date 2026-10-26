/** Optional Vite/browser regressions for chapter handoff and render isolation.
 * PLAYWRIGHT_MODULE, CHROMIUM_EXECUTABLE and READER_URL select existing tools.
 * Uses canonical local text; external audio is not part of these measurements.
 */
import assert from 'node:assert/strict'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_EXECUTABLE,
  headless: true,
  args: ['--no-sandbox'],
})
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('beautiful-quran-settings', JSON.stringify({
      themeMode: 'dark', educationGuidesEnabled: false, gapless5Playback: false,
    }))
    window.profile = { commits: [], reader: [], ornaments: 0 }
    const rendered = new WeakMap()
    // Count committed renders without modifying application code. Vite's
    // React Refresh also expects the renderer registry on this hook.
    window.__REACT_DEVTOOLS_GLOBAL_HOOK__ = {
      supportsFiber: true, renderers: new Map(),
      inject(renderer) { this.renderers.set(1, renderer); return 1 },
      onCommitFiberUnmount() {},
      onCommitFiberRoot(_id, root) {
        const now = performance.now()
        window.profile.commits.push(now)
        const walk = fiber => {
          if (!fiber) return
          const previous = rendered.get(fiber) ?? (fiber.alternate && rendered.get(fiber.alternate))
          // Bailouts can retain PerformedWork from an earlier commit. Count
          // the render clock, remembering both halves of React's fiber pair.
          if (fiber.actualStartTime >= 0 && fiber.actualStartTime !== previous) {
            if (fiber.type?.name === 'ReaderScreen') window.profile.reader.push(now)
            if (fiber.type?.name === 'GeneratedRosette') window.profile.ornaments++
          }
          rendered.set(fiber, fiber.actualStartTime)
          if (fiber.alternate) rendered.set(fiber.alternate, fiber.actualStartTime)
          walk(fiber.child)
          walk(fiber.sibling)
        }
        walk(root.current)
      },
    }
  })
  const base = process.env.READER_URL ?? 'http://127.0.0.1:5173/'
  await page.goto(base + '#112:1', { waitUntil: 'networkidle' })
  await page.locator('.app-shell:not([data-booting])').waitFor()
  await page.evaluate(async () => {
    const path = '/src/store/appStore.ts'
    const url = performance.getEntriesByType('resource').find(e => new URL(e.name).pathname === path)?.name ?? path
    window.reviewApp = (await import(url)).appStore
    await document.fonts.ready
  })
  const open = async (id, ayah = 1) => {
    await page.evaluate(([chapter, verse]) => window.reviewApp.openSurah(chapter, verse), [id, ayah])
    await page.waitForTimeout(700)
  }
  const footer = page.locator('.next-chapter-pill')
  const end = async () => {
    await page.evaluate(() => { document.querySelector('.sheet[data-name="reader"] .scroll').scrollTop = 1e8 })
    await page.waitForTimeout(500)
  }
  const idle = () => page.evaluate(() => Array.from(document.querySelectorAll('.reader-verse-rise')).every(node => {
    const style = getComputedStyle(node)
    return style.willChange === 'auto' && style.transform === 'none' && style.opacity === '1'
  }))
  await page.waitForTimeout(900)
  assert(await idle(), 'idle verses must not retain animation layers')
  // Eight boundary-equivalent updates exercise the reader subscription without
  // involving network reciters. Static ornament geometry must stay asleep.
  const ornaments = await page.evaluate(async () => {
    window.profile.ornaments = 0
    for (let i = 0; i < 8; i++) {
      window.reviewApp.set({ inkActivation: 100 + i })
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    }
    return window.profile.ornaments
  })
  assert.equal(ornaments, 0, 'word-boundary reader updates rebuilt static ornaments')
  await end()
  await page.evaluate(() => {
    window.profile.commits = []
    window.profile.reader = []
    window.firstIncoming = null
    const sample = () => {
      const header = document.querySelector('.sheet[data-name="reader"] .scroll > .surah-header h2')
      if (header?.textContent.includes('Al-Falaq')) {
        const verse = document.querySelector('.sheet[data-name="reader"] .reader-verse-rise')
        window.firstIncoming = { at: performance.now(), opacity: getComputedStyle(verse).opacity }
      } else requestAnimationFrame(sample)
    }
    requestAnimationFrame(sample)
  })
  await footer.click()
  await page.locator('.chapter-fly-header').waitFor()
  assert.equal(await page.locator('.next-chapter-invite-pill').getAttribute('aria-hidden'), 'true')
  const palette = await page.evaluate(() => {
    const source = getComputedStyle(document.querySelector('.next-chapter-opening .ar-title'))
    const clone = getComputedStyle(document.querySelector('.chapter-fly-header .ar-title'))
    return { source: [source.color, source.fontSize], clone: [clone.color, clone.fontSize] }
  })
  assert.deepEqual(palette.clone, palette.source, 'flying header lost the source theme or type scale')
  await page.waitForFunction(() => window.firstIncoming != null)
  assert.equal(await page.evaluate(() => window.firstIncoming.opacity), '0', 'the arriving verses flashed fully visible')
  await page.waitForFunction(() => !document.querySelector('.scroll[data-chapter-advancing]'))
  const commits = await page.evaluate(() => ({
    total: window.profile.commits.length,
    outgoingReader: window.profile.reader.filter(t => t < window.firstIncoming.at).length,
  }))
  assert(commits.outgoingReader < 12, `animation rerendered the reader per frame: ${JSON.stringify(commits)}`)
  assert(await idle())
  assert.equal(await page.locator('.chapter-fly-header').count(), 0)
  // A newer route must win before or after the animated chapter handoff.
  await open(112)
  await end()
  await footer.click()
  await page.waitForTimeout(140)
  await open(114)
  await page.waitForTimeout(900)
  assert.equal(await page.evaluate(() => window.reviewApp.getSnapshot().content.surah.id), 114)
  assert.equal(await page.locator('.chapter-fly-header').count(), 0)
  assert(await idle())
  await open(112)
  await end()
  await footer.click()
  await page.waitForTimeout(140)
  await open(112, 2)
  await page.waitForTimeout(900)
  assert.equal(await page.evaluate(() => window.reviewApp.getSnapshot().content.surah.id), 112)
  assert.equal(await page.evaluate(() => window.reviewApp.getSnapshot().openAyah), 2)
  assert(await idle())
  await end()
  await footer.click()
  await page.waitForFunction(() => window.reviewApp.getSnapshot().content.surah.id === 113)
  await open(113, 3)
  assert(await idle(), 'an interrupted arrival left its verses invisible')
  // Resizing cancels source geometry; a phone must never keep a desktop flyer.
  await open(112)
  await end()
  await footer.click()
  await page.locator('.chapter-fly-header').waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(300)
  assert.equal(await page.locator('.chapter-fly-header').count(), 0)
  assert(await idle())
  // Reduced motion advances directly with no detached flyer or parked verses.
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await open(112)
  await end()
  await footer.click()
  await page.waitForFunction(() => window.reviewApp.getSnapshot().content.surah.id === 113)
  assert.equal(await page.locator('.chapter-fly-header').count(), 0)
  assert(await idle())
  // Editing a range while its mode stays 'range' must still refresh the icon.
  await page.evaluate(() => window.reviewApp.setRepeat('range', { first: 1, last: 3 }))
  await page.waitForTimeout(100)
  const previous = await page.locator('.repeat-control').innerHTML()
  await page.evaluate(() => window.reviewApp.setRepeat('range', { first: 2, last: 2 }))
  await page.waitForTimeout(100)
  assert.notEqual(await page.locator('.repeat-control').innerHTML(), previous)
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({
    staticOrnamentRendersFor8ReaderUpdates: ornaments,
    chapterCommits: commits,
    checks: 'arrival fade, theme, navigation cancellation, resize, reduced motion, idle layers and repeat controls',
    errors,
  }))
} finally {
  await browser.close()
}
