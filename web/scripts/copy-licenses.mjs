// Copies the repo's font licenses into public/font-licenses/ so they ship with
// the reader (Acknowledgements). Generated; git-ignored.
import { cp, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
// Cleared first, so a license removed from the repo leaves the reader too.
await rm(resolve(webRoot, 'public', 'font-licenses'), { recursive: true, force: true })
await cp(resolve(webRoot, '..', 'licenses'), resolve(webRoot, 'public'), { recursive: true })
