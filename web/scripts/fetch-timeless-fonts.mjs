// Fetches the Timeless type family into public/fonts/timeless/.
//
// Timeless's license forbids putting its font files in a public repository,
// so they are never committed: this downloads the official archive pinned in
// scripts/timeless-fonts.json, checks its SHA-256 and unpacks only the web
// cuts. The archive is cached outside the repo, so worktrees and repeat builds
// share one download. Android does the same in app/build.gradle.kts.
import { createHash } from 'node:crypto'
import { access, cp, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
await cp(resolve(webRoot, '..', 'licenses'), resolve(webRoot, 'public'), { recursive: true })
const manifestText = await readFile(resolve(webRoot, '..', 'scripts', 'timeless-fonts.json'), 'utf8')
const manifest = JSON.parse(manifestText)
const outDir = resolve(webRoot, 'public', 'fonts', 'timeless')
const outputs = Object.keys(manifest.web).map((name) => resolve(outDir, name))
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')
const stampPath = resolve(webRoot, 'node_modules', '.cache', 'timeless-fonts.sha256')
const stamp = sha256(manifestText)

const exists = (path) => access(path).then(() => true, () => false)
if (await readFile(stampPath, 'utf8').catch(() => null) === stamp &&
    (await Promise.all(outputs.map(exists))).every(Boolean)) {
  console.log('fetch-timeless-fonts: up to date')
  process.exit(0)
}

const cacheDir = resolve(process.env.XDG_CACHE_HOME || resolve(homedir(), '.cache'), 'beautiful-quran')
const archivePath = resolve(cacheDir, basename(new URL(manifest.url).pathname))

let archive = await readFile(archivePath).catch(() => null)
if (!archive || sha256(archive) !== manifest.sha256) {
  console.log(`fetch-timeless-fonts: downloading ${manifest.url}`)
  const response = await fetch(manifest.url)
  if (!response.ok) throw new Error(`Timeless download failed: HTTP ${response.status}`)
  archive = Buffer.from(await response.arrayBuffer())
  if (sha256(archive) !== manifest.sha256) {
    throw new Error(
      `Timeless archive checksum mismatch: got ${sha256(archive)}, want ${manifest.sha256}. ` +
        'If timeless.co replaced the release, update scripts/timeless-fonts.json deliberately.',
    )
  }
  await mkdir(cacheDir, { recursive: true })
  const partial = `${archivePath}.${process.pid}.tmp`
  await writeFile(partial, archive)
  await rename(partial, archivePath)
}

// Read entries through the zip central directory, which carries the sizes
// even when the local headers defer them to a data descriptor.
function zipEntries(zip) {
  let end = zip.length - 22
  while (end >= 0 && zip.readUInt32LE(end) !== 0x06054b50) end -= 1
  if (end < 0) throw new Error('Timeless archive: no zip end-of-central-directory record')
  const count = zip.readUInt16LE(end + 10)
  let at = zip.readUInt32LE(end + 16)
  const entries = new Map()
  for (let i = 0; i < count; i += 1) {
    const method = zip.readUInt16LE(at + 10)
    const compressedSize = zip.readUInt32LE(at + 20)
    const nameLength = zip.readUInt16LE(at + 28)
    const extraLength = zip.readUInt16LE(at + 30)
    const commentLength = zip.readUInt16LE(at + 32)
    const localHeader = zip.readUInt32LE(at + 42)
    const name = zip.toString('utf8', at + 46, at + 46 + nameLength)
    entries.set(name, () => {
      const start = localHeader + 30 + zip.readUInt16LE(localHeader + 26) + zip.readUInt16LE(localHeader + 28)
      const data = zip.subarray(start, start + compressedSize)
      if (method === 0) return data
      if (method === 8) return inflateRawSync(data)
      throw new Error(`Timeless archive: ${name} uses unsupported compression ${method}`)
    })
    at += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

const entries = zipEntries(archive)
await rm(outDir, { recursive: true, force: true })
await mkdir(outDir, { recursive: true })
for (const [name, entry] of Object.entries(manifest.web)) {
  const read = entries.get(entry)
  if (!read) throw new Error(`Timeless archive has no ${entry}`)
  await writeFile(resolve(outDir, name), read())
}
await mkdir(dirname(stampPath), { recursive: true })
await writeFile(stampPath, stamp)
console.log(`fetch-timeless-fonts: wrote ${outputs.length} fonts to public/fonts/timeless`)
