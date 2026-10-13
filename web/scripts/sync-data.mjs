import { copyFile, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import initSqlJs from 'sql.js'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// Both apps consume the canonical core database. The web boot database omits
// the timing table and emits lazy corpora for independently sourced reciters.
// QF repeat data is obtained through authenticated Content Sync at runtime,
// never packaged here.
const runtimeTimingReciters = new Set([1, 2, 3, 4, 5, 7])
const assets = [
  { name: 'lexicon.db', label: "Lane's lexicon database", required: false },
  { name: 'dictionary.db', label: 'Wiktionary dictionary database', required: false },
  { name: 'search_concepts.json', label: 'Quran concept search index', required: true },
]

const quranSource = resolve(webRoot, '..', 'data', 'quran.db')
const quranDestination = resolve(webRoot, 'public', 'quran.db')
const timingsDestination = resolve(webRoot, 'public', 'timings')

// Splitting the 35 MB database takes a couple of seconds and runs before
// every dev server, build and Pages build. Its result depends only on the
// canonical files and this script, so their sizes and modified times are
// stamped and an unchanged set is left alone. The stamp stays out of
// `public/`, which is copied whole into the build.
const stampPath = resolve(webRoot, 'node_modules', '.cache', 'sync-data.json')
async function fingerprint(path) {
  try {
    const info = await stat(path)
    return `${info.size}:${info.mtimeMs}`
  } catch {
    return 'missing'
  }
}
const inputs = [
  fileURLToPath(import.meta.url),
  quranSource,
  ...assets.map((asset) => resolve(webRoot, '..', 'data', asset.name)),
]
const stamp = JSON.stringify(Object.fromEntries(
  await Promise.all(inputs.map(async (path) => [path, await fingerprint(path)])),
))
const sourceStamps = JSON.parse(stamp)
const outputs = [
  quranDestination,
  timingsDestination,
  // An optional asset that was never built has no copy to look for.
  ...assets
    .filter((asset) => sourceStamps[resolve(webRoot, '..', 'data', asset.name)] !== 'missing')
    .map((asset) => resolve(webRoot, 'public', asset.name)),
]
const built = await readFile(stampPath, 'utf8').catch(() => null)
if (built === stamp && !(await Promise.all(outputs.map(fingerprint))).includes('missing')) {
  console.log('sync-data: up to date')
  process.exit(0)
}
const SQL = await initSqlJs({
  locateFile: (file) => resolve(webRoot, 'node_modules', 'sql.js', 'dist', file),
})
const canonical = new SQL.Database(await readFile(quranSource))
const timingRows = canonical.exec(
  'SELECT reciter_id, surah_id, ayah_number, segments FROM timings ORDER BY reciter_id, surah_id, ayah_number',
)[0]?.values ?? []
const corpora = new Map()

for (const [reciterId, surahId, ayahNumber, segments] of timingRows) {
  if (runtimeTimingReciters.has(Number(reciterId))) continue
  const corpus = corpora.get(reciterId) ?? {}
  const surah = corpus[surahId] ?? {}
  surah[ayahNumber] = JSON.parse(segments)
  corpus[surahId] = surah
  corpora.set(reciterId, corpus)
}

const highlightedReciters = canonical.exec(
  'SELECT id FROM reciters WHERE has_timings = 1 ORDER BY id',
)[0]?.values.flat() ?? []
for (const reciterId of highlightedReciters) {
  if (!runtimeTimingReciters.has(Number(reciterId)) && !corpora.has(reciterId)) {
    throw new Error(`Missing timing corpus for highlighted reciter ${reciterId}`)
  }
}

await rm(timingsDestination, { recursive: true, force: true })
await mkdir(timingsDestination, { recursive: true })
for (const [reciterId, corpus] of corpora) {
  await writeFile(
    resolve(timingsDestination, `${reciterId}.json`),
    JSON.stringify(corpus),
  )
}

canonical.exec('DROP TABLE timings; VACUUM')
await writeFile(quranDestination, canonical.export())
canonical.close()

for (const asset of assets) {
  const source = resolve(webRoot, '..', 'data', asset.name)
  const destination = resolve(webRoot, 'public', asset.name)

  let sourceStat
  try {
    sourceStat = await stat(source)
  } catch {
    if (!asset.required) {
      console.warn(`sync-data: skipping ${asset.name} — not built yet`)
      continue
    }
    throw new Error(`Missing canonical ${asset.label}: ${source}`)
  }

  if (!sourceStat.isFile() || sourceStat.size === 0) {
    throw new Error(`Canonical ${asset.label} is empty or invalid: ${source}`)
  }

  await mkdir(dirname(destination), { recursive: true })
  await copyFile(source, destination)
}

await mkdir(dirname(stampPath), { recursive: true })
await writeFile(stampPath, stamp)
