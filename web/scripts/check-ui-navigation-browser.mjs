/** Optional UI review regressions against the Vite dev server.
 * Requires Playwright. PLAYWRIGHT_MODULE, CHROMIUM_EXECUTABLE and READER_URL
 * select existing installations and the server. No live recitation is needed.
 */
import assert from 'node:assert/strict'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright')
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_EXECUTABLE,
  headless: true,
  args: ['--no-sandbox'],
})
try {
  const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      colorScheme: 'light',
    }),
    errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.addInitScript(() =>
    localStorage.setItem(
      'beautiful-quran-settings',
      JSON.stringify({
        themeMode: 'system',
        educationGuidesEnabled: false,
        gapless5Playback: false,
      }),
    ),
  )
  const base = process.env.READER_URL ?? 'http://127.0.0.1:5173/'
  const modules = () =>
    page.evaluate(() => {
      // Resolve the active modules, including Vite's HMR revision query strings.
      window.reviewImport = (path) =>
        import(
          performance
            .getEntriesByType('resource')
            .find((e) => new URL(e.name).pathname === path)?.name ?? path
        )
    })
  const update = (patch) =>
    page.evaluate(
      async (p) =>
        (
          await window.reviewImport('/src/store/appStore.ts')
        ).appStore.updateSettings(p),
      patch,
    )
  const openWord = () =>
    page.evaluate(async () => {
      const { appStore } = await window.reviewImport('/src/store/appStore.ts'),
        { QuranRepository } = await window.reviewImport(
          '/src/data/repository.ts',
        )
      appStore.openRootViewer(
        1,
        1,
        QuranRepository.surahContent(1).ayahs[0].words[0],
      )
    })
  const root = page.getByRole('region', { name: 'Word meanings', exact: true })
  const home = page.locator('.app-shell > .sheet[data-name="home"]')
  const slot = page.locator('.book-loose')
  const settings = page.locator('.reader-actions .settings-button')
  await page.goto(base + '#1:1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await modules()
  // OS changes update both the paper and browser chrome, while explicit themes stay fixed.
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.waitForTimeout(150)
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
  assert.equal(
    await page.locator('meta[name="theme-color"]').getAttribute('content'),
    '#0C0B09',
  )
  await page.emulateMedia({ colorScheme: 'light' })
  await page.waitForTimeout(150)
  assert.equal(await page.locator('html').getAttribute('data-theme'), null)
  assert.equal(
    await page.locator('meta[name="theme-color"]').getAttribute('content'),
    '#FAF3E8',
  )
  await update({ themeMode: 'royal_green' })
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.waitForTimeout(150)
  assert.equal(
    await page.locator('html').getAttribute('data-theme'),
    'royal_green',
  )
  await update({ themeMode: 'system' })
  await page.waitForTimeout(150)
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
  // Returning from Settings must keep the still-open word sheet's left page inert.
  await page.locator('#chapter-search').focus()
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  assert(await home.evaluate((e) => e.inert))
  assert(await root.evaluate((e) => e === document.activeElement))
  await settings.click()
  await page.waitForTimeout(700)
  assert(await slot.evaluate((e) => e.inert))
  for (let i = 0; i < 4; i++) {
    await page.keyboard.press('Tab')
    assert.equal(
      await page.evaluate(() =>
        Boolean(
          document.activeElement.closest(
            '.root-sheet, .sheet[data-name="home"]',
          ),
        ),
      ),
      false,
    )
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)
  assert(await home.evaluate((e) => e.inert))
  assert.equal(await slot.evaluate((e) => e.inert), false)
  assert(await root.evaluate((e) => e === document.activeElement))
  await root.getByRole('button', { name: 'Close', exact: true }).click()
  await root.waitFor({ state: 'detached' })
  await page.waitForTimeout(50)
  assert.equal(await home.evaluate((e) => e.inert), false)
  assert(
    await page
      .locator('#chapter-search')
      .evaluate((e) => e === document.activeElement),
  )
  // Resizing remounts the word sheet without leaving stale inert flags behind.
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(500)
  assert(await home.evaluate((e) => e.inert))
  assert.equal(await root.evaluate((e) => Boolean(e.closest('[inert]'))), false)
  assert(
    await page
      .locator('.sheet[data-name="reader"] > .reader-top')
      .evaluate((e) => e.inert),
  )
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.waitForTimeout(500)
  assert(await home.evaluate((e) => e.inert))
  assert.equal(await root.evaluate((e) => Boolean(e.closest('[inert]'))), false)
  await root.getByRole('button', { name: 'Close', exact: true }).click()
  await root.waitFor({ state: 'detached' })
  assert.equal(
    await page
      .locator('.sheet[data-name="reader"] > .reader-top')
      .evaluate((e) => e.inert),
    false,
  )
  // A native Repeat modal above the word sheet stays usable across resizing.
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  await page
    .getByRole('button', { name: 'Open repeat options', exact: true })
    .click()
  const repeat = page.getByRole('dialog', { name: 'Repeat', exact: true })
  await repeat.waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(500)
  assert.equal(
    await repeat.evaluate((e) => Boolean(e.closest('[inert]'))),
    false,
  )
  assert(await repeat.evaluate((e) => e.contains(document.activeElement)))
  await repeat.getByRole('button', { name: 'Cancel', exact: true }).click()
  await repeat.waitFor({ state: 'detached' })
  assert(await root.evaluate((e) => e.contains(document.activeElement)))
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.waitForTimeout(500)
  await root.getByRole('button', { name: 'Close', exact: true }).click()
  await root.waitFor({ state: 'detached' })
  // In Scroll layout the reader is also exposed beside Chapters. Popping
  // Settings must preserve that word sheet even when its opening layer is Home.
  await page.evaluate(async () =>
    (await window.reviewImport('/src/store/appStore.ts')).appStore.setSheet(
      'home',
    ),
  )
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  await settings.click()
  await page.waitForTimeout(700)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)
  assert.equal(await page.locator('.app-shell').getAttribute('data-stack'), '0')
  assert.equal(await root.getAttribute('data-closing'), null)
  assert(await home.evaluate((e) => e.inert))
  await root.getByRole('button', { name: 'Close', exact: true }).click()
  await root.waitFor({ state: 'detached' })
  await page.evaluate(async () =>
    (await window.reviewImport('/src/store/appStore.ts')).appStore.setSheet(
      'reader',
    ),
  )
  // The portalled Mushaf leaf also stays covered when Settings is peeled back.
  await update({ readingLayout: 'mushaf' })
  await page.waitForTimeout(500)
  const verso = page.locator('.book-verso-leaf')
  assert.equal(await verso.evaluate((e) => e.inert), false)
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  assert(await verso.evaluate((e) => e.inert))
  await settings.click()
  await page.waitForTimeout(700)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)
  assert(await verso.evaluate((e) => e.inert))
  assert(await root.evaluate((e) => e === document.activeElement))
  // The visible chapter back control must dismiss the word overlay too.
  await page.locator('.reader-top > .back').click()
  await root.waitFor({ state: 'detached' })
  await page.waitForTimeout(50)
  assert.equal(await page.locator('.app-shell').getAttribute('data-stack'), '0')
  assert.equal(await home.evaluate((e) => e.inert), false)
  assert(await home.evaluate((e) => e.contains(document.activeElement)))
  // Browser Back to the chapter list clears the sheet without resuming old audio.
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.locator('.app-shell:not([data-booting])').waitFor()
  await page.keyboard.press('Escape')
  await page
    .getByRole('dialog', { name: 'The Noble Quran', exact: true })
    .waitFor({ state: 'detached' })
  await modules()
  await page.evaluate(async () =>
    (await window.reviewImport('/src/store/appStore.ts')).appStore.openReading(
      1,
      1,
    ),
  )
  await page.waitForTimeout(900)
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  await page.goBack()
  await root.waitFor({ state: 'detached' })
  await page.waitForTimeout(100)
  assert.equal(await page.locator('.app-shell').getAttribute('data-stack'), '0')
  assert.equal(await home.evaluate((e) => e.inert), false)
  assert(await home.evaluate((e) => e.contains(document.activeElement)))
  assert.equal(
    await page.evaluate(
      async () =>
        (
          await window.reviewImport('/src/store/appStore.ts')
        ).appStore.getSnapshot().player.isPlaying,
    ),
    false,
  )
  // A new verse URL must not restore focus to the word in the previous chapter.
  await page.evaluate(async () =>
    (await window.reviewImport('/src/store/appStore.ts')).appStore.openReading(
      1,
      1,
    ),
  )
  await page.waitForTimeout(500)
  await openWord()
  await root.waitFor()
  await page.waitForTimeout(700)
  await page.evaluate(() => {
    location.hash = '#2:255'
  })
  await root.waitFor({ state: 'detached' })
  await page.waitForTimeout(200)
  const target = await page.evaluate(async () => {
    const s = (
      await window.reviewImport('/src/store/appStore.ts')
    ).appStore.getSnapshot()
    return { surah: s.content.surah.id, ayah: s.openAyah }
  })
  assert.deepEqual(target, { surah: 2, ayah: 255 })
  assert(
    await page
      .locator('.sheet[data-name="reader"]')
      .evaluate((e) => e.contains(document.activeElement)),
  )
  assert.deepEqual(errors, [])
  console.log(
    JSON.stringify({
      theme: 'system changes and manual override',
      focus:
        'nested Settings, original focus, phone/desktop resize and facing leaf',
      navigation: 'chapter control, browser Back and new verse URL',
      errors,
    }),
  )
} finally {
  await browser.close()
}
