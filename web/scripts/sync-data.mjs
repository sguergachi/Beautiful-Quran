import { copyFile, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import initSqlJs from 'sql.js'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// The canonical database remains Android's complete offline asset. The web
// build strips its large timing table from the boot database and emits one
// lazy timing corpus per reciter.
const assets = [
  { name: 'lexicon.db', label: "Lane's lexicon database", required: false },
  { name: 'dictionary.db', label: 'Wiktionary dictionary database', required: false },
  { name: 'search_concepts.json', label: 'Quran concept search index', required: true },
]

const quranSource = resolve(webRoot, '..', 'data', 'quran.db')
const quranDestination = resolve(webRoot, 'public', 'quran.db')
const timingsDestination = resolve(webRoot, 'public', 'timings')
const SQL = await initSqlJs({
  locateFile: (file) => resolve(webRoot, 'node_modules', 'sql.js', 'dist', file),
})
const canonical = new SQL.Database(await readFile(quranSource))
const timingRows = canonical.exec(
  'SELECT reciter_id, surah_id, ayah_number, segments FROM timings ORDER BY reciter_id, surah_id, ayah_number',
)[0]?.values ?? []
const corpora = new Map()

for (const [reciterId, surahId, ayahNumber, segments] of timingRows) {
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
  if (!corpora.has(reciterId)) {
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
