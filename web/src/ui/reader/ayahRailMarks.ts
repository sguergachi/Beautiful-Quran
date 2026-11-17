/**
 * Wayfinding marks and release physics for the open ayah wheel — Android
 * `AyahSelectorRail.kt` (`pageStartByAyah`, `hizbAyahs`, `juzStartByAyah`,
 * `sajdaAyahs`, `collapsedAyahBarIndex`, `settleDialWheel`).
 */
import type { Ayah } from '../../data/models'
import { juzOf } from './mushafFollow'
import { symbolicAyahBarCount } from './ayahRailMath'

/** Rubʿ al-ḥizb mark in Uthmani text (۞). */
export const HIZB_MARK = '۞'
/** The prostration sign (۩). */
export const SAJDA_MARK = '۩'

type MarkAyah = Pick<Ayah, 'surahId' | 'number' | 'text' | 'page'>

/**
 * Ayah → mushaf page for ayahs that open a new page (the first ayah whose
 * page differs from the previous one). Ayahs without page data (0) are
 * skipped without breaking the boundary chain.
 */
export function pageStartByAyah(ayahs: readonly MarkAyah[]): Map<number, number> {
  const out = new Map<number, number>()
  let previous = 0
  for (const ayah of ayahs) {
    if (ayah.page <= 0) continue
    if (ayah.page !== previous) out.set(ayah.number, ayah.page)
    previous = ayah.page
  }
  return out
}

/** The juzʾ (1..30) that opens exactly at this ayah, or null. */
export function juzOpeningAt(surahId: number, ayah: number): number | null {
  if (surahId === 1 && ayah === 1) return 1
  const here = juzOf(surahId, ayah)
  return here !== juzOf(surahId, ayah - 1) ? here : null
}

/** Ayah → juzʾ for the verses where a juzʾ opens in this surah ("JUZ N"). */
export function juzStartByAyah(ayahs: readonly MarkAyah[]): Map<number, number> {
  const out = new Map<number, number>()
  for (const ayah of ayahs) {
    const juz = juzOpeningAt(ayah.surahId, ayah.number)
    if (juz != null) out.set(ayah.number, juz)
  }
  return out
}

/** Ayahs carrying the mushaf's own ۞. */
export function hizbAyahs(ayahs: readonly MarkAyah[]): Set<number> {
  return new Set(ayahs.filter((a) => a.text.includes(HIZB_MARK)).map((a) => a.number))
}

/** Ayahs carrying the mushaf's own ۩. */
export function sajdaAyahs(ayahs: readonly MarkAyah[]): Set<number> {
  return new Set(ayahs.filter((a) => a.text.includes(SAJDA_MARK)).map((a) => a.number))
}

export type RailMarks = {
  pageStarts: ReadonlyMap<number, number>
  hizb: ReadonlySet<number>
  juzStarts: ReadonlyMap<number, number>
  sajda: ReadonlySet<number>
}

export function railMarks(ayahs: readonly MarkAyah[]): RailMarks {
  return {
    pageStarts: pageStartByAyah(ayahs),
    hizb: hizbAyahs(ayahs),
    juzStarts: juzStartByAyah(ayahs),
    sajda: sajdaAyahs(ayahs),
  }
}

/**
 * The trailing annotation pieces for one verse, in Android order: "p. N",
 * then ۞, then "JUZ N" (a middle dot holds the ۞'s place when a juzʾ verse
 * has none), then ۩.
 */
export type RailMarkPiece = { text: string; kind: 'page' | 'sign' | 'juz' | 'dot' }

export function railMarkPieces(marks: RailMarks | null | undefined, ayah: number): RailMarkPiece[] {
  if (!marks) return []
  const out: RailMarkPiece[] = []
  const page = marks.pageStarts.get(ayah)
  if (page != null) out.push({ text: `p. ${page}`, kind: 'page' })
  const hizb = marks.hizb.has(ayah)
  if (hizb) out.push({ text: HIZB_MARK, kind: 'sign' })
  const juz = marks.juzStarts.get(ayah)
  if (juz != null) {
    if (!hizb) out.push({ text: '·', kind: 'dot' })
    out.push({ text: `JUZ ${juz}`, kind: 'juz' })
  }
  if (marks.sajda.has(ayah)) out.push({ text: SAJDA_MARK, kind: 'sign' })
  return out
}

/** Nearest collapsed-stack bar for an ayah (place + bookmark ink). */
export function collapsedAyahBarIndex(ayahCount: number, ayah: number): number {
  const bars = symbolicAyahBarCount(ayahCount)
  const clamped = Math.min(ayahCount, Math.max(1, ayah))
  const progress = ayahCount > 1 ? (clamped - 1) / (ayahCount - 1) : 0
  return Math.min(bars - 1, Math.max(0, Math.round(progress * (bars - 1))))
}

/** Android haptic: CONTEXT_CLICK on first / last / every fifth verse, else CLOCK_TICK. */
export function isMajorHapticAyah(ayah: number, ayahCount: number): boolean {
  return ayah === 1 || ayah === ayahCount || ayah % 5 === 0
}

/** Compose `exponentialDecay(frictionMultiplier = 1.85)`: rate per second. */
const DECAY_RATE = 4.2 * 1.85
/** Android `settleDialWheel`: a release slower than this (ayah/s) only springs. */
export const FLING_MIN_AYAH_PER_SEC = 0.06

/**
 * Where a flung wheel coasts to, bounded to [1, ayahCount], and how long it
 * takes (ms) — the closed form of Compose's exponential decay.
 */
export function wheelCoast(
  start: number,
  velocityAyahPerSec: number,
  ayahCount: number,
): { end: number; durationMs: number } {
  const min = 1
  const max = Math.max(1, ayahCount)
  const s = Math.min(max, Math.max(min, start))
  if (Math.abs(velocityAyahPerSec) <= FLING_MIN_AYAH_PER_SEC) return { end: s, durationMs: 0 }
  const free = s + velocityAyahPerSec / DECAY_RATE
  // Time until |v| falls under ~0.1 ayah/s (Compose's visibility threshold).
  let durationMs = (Math.log(Math.abs(velocityAyahPerSec) / 0.1) / DECAY_RATE) * 1000
  let end = free
  if (free > max || free < min) {
    end = free > max ? max : min
    // Time at which the decay reaches the bound: stop right there.
    const frac = (end - s) / (free - s)
    durationMs = (-Math.log(Math.max(1e-6, 1 - frac)) / DECAY_RATE) * 1000
  }
  return { end, durationMs: Math.max(0, durationMs) }
}

/** Position along the coast at [tMs] (same curve as [wheelCoast]). */
export function wheelCoastAt(start: number, velocityAyahPerSec: number, tMs: number): number {
  return start + (velocityAyahPerSec / DECAY_RATE) * (1 - Math.exp((-DECAY_RATE * tMs) / 1000))
}

/** Grace window after the wheel settles before the jump commits (Android 1.5s). */
export const RAIL_COMMIT_GRACE_MS = 1500
