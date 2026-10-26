/** Optional navigation regression check against the Vite dev server.
 * Requires Playwright; PLAYWRIGHT_MODULE and CHROMIUM_EXECUTABLE can point
 * to existing installations. READER_URL overrides the local dev URL.
 * English verses are canonical; folios are a synthetic, tab-local fixture.
 * This checks navigation and geometry without the live printed-page service.
 */
import assert from 'node:assert/strict'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
;(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_EXECUTABLE,
    headless: true,
    args: ['--no-sandbox'],
  })
  try {
    const page = await browser.newPage({
        viewport: { width: 1440, height: 900 },
      }),
      errors = []
    page.on('pageerror', (e) => {
      errors.push(e.message)
      console.error('PAGE ERROR', e.message)
    })
    await page.addInitScript(() =>
      localStorage.setItem(
        'beautiful-quran-settings',
        JSON.stringify({
          readingLayout: 'mushaf',
          readingMode: 'english_only',
          educationGuidesEnabled: false,
          gapless5Playback: false,
        }),
      ),
    )
    await page.goto(process.env.READER_URL ?? 'http://127.0.0.1:5173/', {
      waitUntil: 'networkidle',
    })
    await page.locator('.app-shell:not([data-booting])').waitFor()
    await page.keyboard.press('Escape')
    await page
      .getByRole('dialog', { name: 'The Noble Quran', exact: true })
      .waitFor({ state: 'detached' })
    await page.waitForTimeout(700)
    await page.locator('.book-return-page').waitFor()
    assert.equal(await page.locator('.book-start-leaf').count(), 0)
    assert.equal(await page.locator('.book-return').count(), 0)
    await page.evaluate(() => {
      window.bookImport = async (path) => {
        const resource = performance
          .getEntriesByType('resource')
          .find((e) => new URL(e.name).pathname === path)
        return import(resource?.name ?? path)
      }
    })
    // Navigation fixture: use canonical bundled ayahs for English leaves. It
    // bypasses only the unavailable authenticated printed-page map in this tab.
    await page.evaluate(async () => {
      const { QuranRepository } = await window.bookImport(
        '/src/data/repository.ts',
      )
      const { runtimeMushafCache: cache } = await window.bookImport(
        '/src/data/runtimeMushaf.ts',
      )
      const rows = QuranRepository.surahContent(36).ayahs.flatMap((a) =>
        a.words.map((w) => ({
          surah_id: 36,
          ayah_number: a.number,
          position: w.position,
          qcf_page: 441 + Math.floor((a.number - 1) / 3),
          ayah_page: 441 + Math.floor((a.number - 1) / 3),
          qcf_line: 1,
          qcf_span_end: w.position,
        })),
      )
      cache.layoutReady = () => true
      const pages = new Map()
      cache.pageWords = (p) => {
        if (!pages.has(p))
          pages.set(
            p,
            rows.filter((r) => r.qcf_page === p),
          )
        return pages.get(p)
      }
      cache.pageOfAyah = (s, a) =>
        s === 36 ? 441 + Math.floor((a - 1) / 3) : 1
    })
    // Settings without a chapter covers Chapters on the left, keeping the resume page on the right.
    await page.locator('.home-settings').click()
    const settings = page.locator('.sheet[data-name="settings"]')
    await page.waitForTimeout(750)
    let box = await settings.boundingBox()
    assert(box.x < 720 && box.x + box.width < 720, JSON.stringify(box))
    assert.equal(await page.locator('.book-return-page').count(), 1)
    assert(
      await page.locator('.sheet[data-name="home"]').evaluate((e) => e.inert),
    )
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-stack'),
      '0',
    )
    // Open a later chapter at an explicit verse, then return to Chapters.
    await page.evaluate(async () => {
      const { appStore } = await window.bookImport('/src/store/appStore.ts')
      appStore.openReading(36, 12)
    })
    await page.waitForTimeout(1800)
    const original = await page
      .locator(
        '.sheet[data-name="reader"] .mushaf[data-side="recto"] .mushaf-head-figure',
      )
      .first()
      .innerText()
    assert(original && Number(original) > 1, original)
    const originalPlace = await page.evaluate(
      async () =>
        (
          await window.bookImport('/src/store/appStore.ts')
        ).appStore.getSnapshot().readingPlace,
    )
    await page.keyboard.press('Escape')
    await page.waitForTimeout(1600)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-stack'),
      '0',
    )
    assert.equal(
      await page
        .locator('.sheet[data-name="reader"]')
        .evaluate((e) => getComputedStyle(e).visibility),
      'hidden',
    )
    const back = page.getByRole('button', { name: /Return to Ya-Sin/ })
    await back.waitFor()
    assert(
      await page.locator('.sheet[data-name="reader"]').evaluate((e) => e.inert),
    )
    await page.keyboard.press('/')
    assert.equal(await page.locator('#surah-search').count(), 0)
    await page.screenshot({ path: '/tmp/quran-book-chapters.png' })
    // Settings preserves the Chapter spread and returns to it.
    await page.locator('.home-settings').click()
    await page.waitForTimeout(750)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-underlay'),
      '0',
    )
    assert.equal(await page.locator('.book-return-page').count(), 1)
    assert(await page.locator('.book-return-page').evaluate((e) => e.inert))
    assert.equal(
      await page.evaluate(
        async () =>
          (await window.bookImport('/src/ui/paper/bookSpread.ts')).bookRest()
            ?.place,
      ),
      1,
    )
    await page.screenshot({ path: '/tmp/quran-book-settings-chapters.png' })
    await page.evaluate(async () => {
      const { appStore } = await window.bookImport('/src/store/appStore.ts')
      appStore.updateSettings({ readingLayout: 'scroll' })
      appStore.updateSettings({ readingLayout: 'mushaf' })
    })
    await page.waitForTimeout(800)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-underlay'),
      '0',
    )
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-stack'),
      '0',
    )
    await back.click()
    await page.waitForTimeout(1600)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-stack'),
      '1',
    )
    assert.equal(
      await page
        .locator(
          '.sheet[data-name="reader"] .mushaf[data-side="recto"] .mushaf-head-figure',
        )
        .first()
        .innerText(),
      original,
    )
    assert.deepEqual(
      await page.evaluate(
        async () =>
          (
            await window.bookImport('/src/store/appStore.ts')
          ).appStore.getSnapshot().readingPlace,
      ),
      originalPlace,
    )
    // Settings, repeat and word sheets all use the left page.
    await page.locator('.reader-actions .settings-button').click()
    await page.waitForTimeout(750)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-underlay'),
      '1',
    )
    assert.equal(await page.locator('.book-return-page').count(), 0)
    box = await settings.boundingBox()
    assert(box.x + box.width < 720)
    assert.equal(
      await page
        .locator(
          '.sheet[data-name="reader"] .mushaf[data-side="recto"] .mushaf-head-figure',
        )
        .first()
        .innerText(),
      original,
    )
    await page.screenshot({ path: '/tmp/quran-book-settings-reader.png' })
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    await page.getByRole('button', { name: 'Open repeat options' }).click()
    await page.waitForTimeout(750)
    const repeat = page.getByRole('dialog', { name: 'Repeat', exact: true })
    box = await repeat.boundingBox()
    assert(box.x + box.width < 720)
    await page.keyboard.press('Escape')
    await repeat.waitFor({ state: 'detached' })
    for (const origin of ['recto', 'verso']) {
      await page.evaluate(async (origin) => {
        const { appStore } = await window.bookImport('/src/store/appStore.ts')
        const { QuranRepository } = await window.bookImport(
          '/src/data/repository.ts',
        )
        const word = QuranRepository.surahContent(36).ayahs.find(
          (a) => a.number === 12,
        ).words[0]
        appStore.openRootViewer(36, 12, word, origin)
      }, origin)
      const root = page.getByRole('region', { name: 'Word meanings' })
      await root.waitFor()
      await page.waitForTimeout(750)
      assert.equal(await root.getAttribute('data-page'), 'verso')
      box = await root.boundingBox()
      assert(box.x + box.width < 720)
      // Settings can lie over a word sheet, and Back peels it first.
      if (origin === 'recto') {
        await page.locator('.reader-actions .settings-button').click()
        await page.waitForTimeout(750)
        await page.keyboard.press('Escape')
        await page.waitForTimeout(550)
        assert.equal(
          await page.locator('.app-shell').getAttribute('data-stack'),
          '1',
        )
        assert.equal(await root.getAttribute('data-closing'), null)
      }
      await page.keyboard.press('Escape')
      await root.waitFor({ state: 'detached' })
    }
    // Settings cancels an airborne leaf before laying its own sheet down.
    await page.getByRole('button', { name: 'Next pages', exact: true }).click()
    await page.locator('.book-turn .mushaf-flip[data-dir]').first().waitFor()
    await page.locator('.reader-actions .settings-button').click()
    await page.waitForTimeout(750)
    assert.equal(
      await page.locator('.book-turn .mushaf-flip[data-dir]').count(),
      0,
    )
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-air'),
      null,
    )
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    await page.setViewportSize({ width: 768, height: 1024 })
    await page.waitForTimeout(1000)
    await page.locator('.reader-actions .settings-button').click()
    await page.waitForTimeout(750)
    box = await settings.boundingBox()
    assert(box.x > 0 && box.x + box.width < 384)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.waitForTimeout(1000)
    await page.locator('.reader-actions .settings-button').click()
    await page.waitForTimeout(800)
    box = await settings.boundingBox()
    assert.equal(Math.round(box.width), 390)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(550)
    assert.equal(
      await page.locator('.app-shell').getAttribute('data-stack'),
      '1',
    )
    assert.deepEqual(errors, [])
    console.log(
      JSON.stringify({
        original,
        originalPlace,
        desktop:
          'resume page, same folio and ayah, Settings context, all overlays left',
        mobile: 'full page Settings and return to reader',
        errors,
      }),
    )
  } finally {
    await browser.close()
  }
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
