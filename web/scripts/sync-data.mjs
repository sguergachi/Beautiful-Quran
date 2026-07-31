import { copyFile, mkdir, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// quran.db is required. lexicon.db is fetched only when a reader opens a root,
// so a missing one degrades to a Root Viewer without Lane rather than a broken
// build — run `python3 tools/build_lexicon_db.py` to produce it.
const assets = [
  { name: 'quran.db', label: 'Quran database', required: true },
  { name: 'lexicon.db', label: "Lane's lexicon database", required: false },
]

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
