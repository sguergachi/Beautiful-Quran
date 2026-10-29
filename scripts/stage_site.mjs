// Stages the product page into _site/, the directory wrangler.jsonc serves as
// static assets. Whitelist only: docs/ also holds internal audits, design
// studies and markdown that must never be published from a private repo.
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const out = join(root, '_site')

const docs = [
  'index.html', 'privacy.html', 'terms.html',
  'styles.css', 'ornaments.css',
  'theme.js', 'nav.js', 'ink-fade.js', 'reveal.js', 'loops.js',
  'ornaments',
  'hero_playing.mp4', 'hero_playing_poster.jpg',
  'theme_nightfall.mp4', 'theme_nightfall_poster.jpg',
  'theme_paper.mp4', 'theme_paper_poster.jpg',
  'theme_royal.mp4', 'theme_royal_poster.jpg',
  'ss1_home.png', 'ss2_reader.png', 'ss3_settings.png', 'ss4_playing.png',
]
// Favicons and the bundled type live with the web sources; the page reuses them.
const webPublic = ['favicon.ico', 'favicon.svg', 'apple-touch-icon.png', 'fonts.css', 'fonts']

rmSync(out, { recursive: true, force: true })
mkdirSync(out, { recursive: true })
for (const [base, names] of [['docs', docs], ['web/public', webPublic]]) {
  for (const name of names) {
    const from = join(root, base, name)
    if (!existsSync(from)) throw new Error(`Missing site file: ${base}/${name}`)
    cpSync(from, join(out, name), { recursive: true })
  }
}

// Every local reference in the HTML must resolve inside _site.
for (const page of ['index.html', 'privacy.html', 'terms.html']) {
  const html = readFileSync(join(out, page), 'utf8')
  for (const [, ref] of html.matchAll(/(?:href|src)="([^"#:]+?)(?:#[^"]*)?"/g)) {
    if (!existsSync(join(out, ref))) throw new Error(`${page} references missing ${ref}`)
  }
}
console.log(`Staged ${readdirSync(out).length} entries into _site/`)
