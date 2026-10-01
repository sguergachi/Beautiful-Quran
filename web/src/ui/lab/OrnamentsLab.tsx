/**
 * Ornaments Lab — a developer tool for exploring the procedural ornament
 * generator. Reachable at `#lab` (revealed by the Developer-mode setting).
 * Self-contained and database-free, so it opens instantly without the book.
 *
 * Explore a seed → live previews of its cover ornament, chapter header, and
 * the arabesque field; read the seed's decoded traits; "design" an
 * ornament by choosing traits and searching for a seed that matches; and
 * save named seeds to reuse later. Every preview uses the very same
 * generator + renderers the app ships, so what you see is what you get.
 */
import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import {
  generateChapterOrnament,
  generateCoverOrnament,
  type CoverOrnament,
  type FieldSpec,
} from '../theme/ornamentGenerator'
import { GeneratedRosette } from '../theme/GeneratedOrnament'

type ThemeName = 'light' | 'dark' | 'royal_green'

interface SavedSeed {
  name: string
  seed: number
}

const SAVED_KEY = 'beautiful-quran-lab-seeds'

function loadSaved(): SavedSeed[] {
  try {
    const raw = localStorage.getItem(SAVED_KEY)
    return raw ? (JSON.parse(raw) as SavedSeed[]) : []
  } catch {
    return []
  }
}

function storeSaved(list: SavedSeed[]): void {
  localStorage.setItem(SAVED_KEY, JSON.stringify(list))
}

function randomSeed(): number {
  // Full 32-bit signed range, matching Mulberry32's `seed | 0` domain.
  return (Math.floor(Math.random() * 0x100000000) - 0x80000000) | 0
}

// ── Decoded traits ──────────────────────────────────────────────────────────

interface CoverTraits {
  medallionFold: number
  sealFold: number
  borderSignature: string
}

/** Derive high-level traits from a generated ornament (what's actually drawn). */
function coverTraits(o: CoverOrnament): CoverTraits {
  return {
    medallionFold: o.medallion.fold,
    sealFold: o.cornerSeal.fold,
    borderSignature: `${o.border.strokes.length}·${o.border.dots.length}`,
  }
}

// ── Field rendering (crisp inline SVG, full control over ink) ────────────────

function fieldPaths(field: FieldSpec, cols: number, rows: number, cellPx: number): { d: string; weight: 'rule' | 'hairline' }[] {
  const paths: { d: string; weight: 'rule' | 'hairline' }[] = []
  for (let gx = -1; gx <= cols; gx++) {
    for (let gy = -1; gy <= rows; gy++) {
      for (const s of field.strokes) {
        const d =
          s.points
            .map(
              (p, i) =>
                `${i === 0 ? 'M' : 'L'} ${((gx + p.x) * cellPx).toFixed(2)} ${((gy + p.y) * cellPx).toFixed(2)}`,
            )
            .join(' ') + (s.closed ? ' Z' : '')
        paths.push({ d, weight: s.weight })
      }
    }
  }
  return paths
}

function FieldSurface({
  field,
  cellPx,
  stroke,
  strokeWidth,
  background,
  height,
}: {
  field: FieldSpec
  cellPx: number
  stroke: string
  strokeWidth: number
  background?: string
  height?: number
}) {
  const patternId = useId()
  const h = height ?? 240
  const paths = useMemo(() => fieldPaths(field, 1, 1, cellPx), [field, cellPx])
  return (
    <svg
      style={{ width: '100%', height: h, display: 'block', background }}
      aria-hidden="true"
    >
      <defs>
        <pattern id={patternId} width={cellPx} height={cellPx} patternUnits="userSpaceOnUse">
          {paths.map(({ d, weight }, i) => (
            <path key={i} d={d} fill="none" stroke={stroke} strokeWidth={strokeWidth * (weight === 'rule' ? 1 : 0.55)} strokeLinejoin="round" />
          ))}
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#${patternId})`} />
    </svg>
  )
}

const THEME_PAPER: Record<ThemeName, string> = {
  light: '#faf3e8',
  dark: '#0a0b0c',
  royal_green: '#062c24',
}

// ── The Lab ──────────────────────────────────────────────────────────────────

export function OrnamentsLab() {
  const [seed, setSeed] = useState<number>(() => {
    const fromHash = Number(new URLSearchParams(location.hash.split('?')[1] ?? '').get('seed'))
    return Number.isFinite(fromHash) && fromHash !== 0 ? fromHash | 0 : randomSeed()
  })
  const [theme, setTheme] = useState<ThemeName>('light')
  const [saved, setSaved] = useState<SavedSeed[]>(loadSaved)
  const [saveName, setSaveName] = useState('')
  const [copied, setCopied] = useState(false)

  // Trait filters for "design by trait" search.
  const [fFold, setFFold] = useState('any')
  const [searchNote, setSearchNote] = useState('')

  const cover = useMemo(() => generateCoverOrnament(seed), [seed])
  const chapter = useMemo(() => generateChapterOrnament(seed), [seed])
  const traits = useMemo(() => coverTraits(cover), [cover])

  // Keep the seed in the URL so a reload (or a shared link) restores it.
  useEffect(() => {
    history.replaceState(null, '', `#lab?seed=${seed}`)
  }, [seed])

  const close = useCallback(() => {
    history.replaceState(null, '', location.pathname + location.search)
    // Nudge listeners (App watches hashchange).
    window.dispatchEvent(new HashChangeEvent('hashchange'))
  }, [])

  const copySeed = useCallback(() => {
    void navigator.clipboard?.writeText(String(seed))
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1200)
  }, [seed])

  const save = useCallback(() => {
    const name = saveName.trim() || `Seed ${seed}`
    const next = [{ name, seed }, ...saved.filter((s) => s.seed !== seed)].slice(0, 60)
    setSaved(next)
    storeSaved(next)
    setSaveName('')
  }, [saveName, seed, saved])

  const remove = useCallback(
    (target: number) => {
      const next = saved.filter((s) => s.seed !== target)
      setSaved(next)
      storeSaved(next)
    },
    [saved],
  )

  const findSeed = useCallback(() => {
    setSearchNote('Searching…')
    // Scan deterministically from a random offset so repeated searches with
    // the same filters surface different matches.
    const start = randomSeed()
    for (let i = 0; i < 200_000; i++) {
      const candidate = (start + i) | 0
      const t = coverTraits(generateCoverOrnament(candidate))
      if (fFold !== 'any' && t.medallionFold !== Number(fFold)) continue
      setSeed(candidate)
      setSearchNote(`Found after ${i + 1} tries`)
      return
    }
    setSearchNote('No match in 200k seeds — loosen the filters')
  }, [fFold])

  const gold = { brightGold: 'var(--gold-bright)', deepGold: 'var(--gold-deep)', embossDark: 'var(--emboss-dark)', embossLight: 'var(--emboss-light)' }
  const paper = THEME_PAPER[theme]

  return (
    <div className="lab" data-theme={theme === 'light' ? undefined : theme}>
      <header className="lab-bar">
        <strong>Ornaments Lab</strong>
        <div className="lab-seed">
          <button onClick={() => setSeed((s) => (s - 1) | 0)} aria-label="Previous seed">‹</button>
          <input
            type="number"
            value={seed}
            onChange={(e) => setSeed((Number(e.target.value) || 0) | 0)}
            aria-label="Seed"
          />
          <button onClick={() => setSeed((s) => (s + 1) | 0)} aria-label="Next seed">›</button>
          <button onClick={() => setSeed(randomSeed())}>Random</button>
          <button onClick={copySeed}>{copied ? 'Copied!' : 'Copy seed'}</button>
        </div>
        <div className="lab-themes">
          {(['light', 'dark', 'royal_green'] as ThemeName[]).map((t) => (
            <button
              key={t}
              className={t === theme ? 'is-active' : ''}
              onClick={() => setTheme(t)}
            >
              {t === 'royal_green' ? 'green' : t}
            </button>
          ))}
        </div>
        <button className="lab-close" onClick={close}>Close ✕</button>
      </header>

      <div className="lab-grid">
        {/* The background pattern — the arabesque field, drawn bold. */}
        <section className="lab-panel" style={{ background: paper }}>
          <h3>Field pattern (this seed)</h3>
          <FieldSurface
            field={cover.field}
            cellPx={cover.field.cellWidthDp}
            stroke="var(--gold-deep)"
            strokeWidth={1.4}
            height={240}
          />
        </section>

        {/* Chapter header is judged on paper — stage the whisper field on
            light parchment even when the lab itself is dark/green. */}
        <section className="lab-panel" style={{ background: '#faf3e8' }}>
          <h3 style={{ color: 'rgba(28, 27, 24, 0.55)' }}>Chapter header</h3>
          <div className="lab-header-preview">
            <FieldSurface
              field={chapter.field}
              cellPx={chapter.field.cellWidthDp}
              stroke="rgba(28, 27, 24, 0.10)"
              strokeWidth={1}
              height={200}
            />
            <div className="lab-header-rosette">
              <GeneratedRosette spec={chapter.rosette} className="lab-rosette" built animated={false} ruleWidth={4.6} hairWidth={4.6} {...gold} />
            </div>
          </div>
        </section>

        {/* The cover ornament pieces. */}
        <section className="lab-panel" style={{ background: paper }}>
          <h3>Medallion</h3>
          <div className="lab-center">
            <GeneratedRosette spec={cover.medallion} className="lab-medallion" built animated={false} {...gold} />
          </div>
        </section>

        <section className="lab-panel" style={{ background: paper }}>
          <h3>Corner seal</h3>
          <div className="lab-center">
            <GeneratedRosette spec={cover.cornerSeal} className="lab-seal" built animated={false} ruleWidth={4.6} hairWidth={4.6} {...gold} />
          </div>
        </section>

        {/* Decoded traits. */}
        <section className="lab-panel lab-traits">
          <h3>Traits</h3>
          <dl>
            <div><dt>Medallion fold</dt><dd>{traits.medallionFold}</dd></div>
            <div><dt>Seal fold</dt><dd>{traits.sealFold}</dd></div>
            <div><dt>Field style</dt><dd>star-and-cross filigree</dd></div>
            <div><dt>Border</dt><dd>{traits.borderSignature}</dd></div>
          </dl>
        </section>

        {/* Design by trait → find a seed. */}
        <section className="lab-panel lab-design">
          <h3>Design by trait</h3>
          <p className="lab-hint">Pick what you want, then find a seed that makes it.</p>
          <label>Medallion fold
            <select value={fFold} onChange={(e) => setFFold(e.target.value)}>
              <option value="any">any</option><option>8</option><option>10</option><option>12</option><option>16</option>
            </select>
          </label>
          <button onClick={findSeed}>Find a seed</button>
          {searchNote && <p className="lab-note">{searchNote}</p>}
        </section>

        {/* Saved seeds. */}
        <section className="lab-panel lab-saved">
          <h3>Saved seeds</h3>
          <div className="lab-save-row">
            <input
              type="text"
              placeholder="name this seed…"
              value={saveName}
              onChange={(e) => setSaveName(e.target.value)}
            />
            <button onClick={save}>Save {seed}</button>
          </div>
          <ul>
            {saved.length === 0 && <li className="lab-empty">Nothing saved yet.</li>}
            {saved.map((s) => (
              <li key={s.seed}>
                <button className="lab-swatch" onClick={() => setSeed(s.seed)} aria-label={`Load ${s.name}`}>
                  <FieldSurface
                    field={generateCoverOrnament(s.seed).field}
                    cellPx={22}
                    stroke="var(--gold-deep)"
                    strokeWidth={1}
                    height={44}
                  />
                </button>
                <span className="lab-saved-name" onClick={() => setSeed(s.seed)}>{s.name}</span>
                <code>{s.seed}</code>
                <button className="lab-del" onClick={() => remove(s.seed)} aria-label="Delete">✕</button>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  )
}
