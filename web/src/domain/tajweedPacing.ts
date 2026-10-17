/** Letter pacing port of Android domain/TajweedPacing.kt (gate/cruise/hold).
 * QPC Hafs marks distinguish voiced U+06E1 from silent U+0652.
 */
const clamp = (x: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, x))
export interface Hold {
  madd: boolean
  ghunnah: boolean
  waqf: boolean
  connect: boolean
  isAyahFinal: boolean
  cruiseCap: number
  waqfShare: number
  waqfLengthScale: number
  creep: number
  maddAaridWaqf: boolean
}
export const DEFAULT_HOLD: Hold = {
  madd: true,
  ghunnah: false,
  waqf: true,
  connect: true,
  isAyahFinal: false,
  cruiseCap: 1.25,
  waqfShare: 0.55,
  waqfLengthScale: 0.7,
  creep: 0.08,
  maddAaridWaqf: false,
}
export class PacingCurve {
  constructor(
    readonly times: number[],
    readonly positions: number[],
    readonly letterCount: number,
    readonly hasStrongHold: boolean,
  ) {}
  forWash(feather: number): PacingCurve {
    const edge = Math.max(0, feather)
    return new PacingCurve(
      this.times,
      this.positions.map((x) =>
        x <= 0 || x >= 1 ? x : (x + edge / 2) / (1 + edge),
      ),
      this.letterCount,
      this.hasStrongHold,
    )
  }
  at(t: number): number {
    if (t >= 1) return 1
    const c = Math.max(0, t)
    let i = this.times.length - 1
    while (i > 0 && this.times[i] > c) i--
    if (i >= this.times.length - 1) return 1
    const span = this.times[i + 1] - this.times[i]
    if (span <= 0) return this.positions[i + 1]
    return (
      this.positions[i] +
      ((this.positions[i + 1] - this.positions[i]) * (c - this.times[i])) / span
    )
  }
}
interface Event {
  base: string
  shadda: boolean
  sukoon: boolean
  maddah: boolean
  fatha: boolean
  damma: boolean
  kasra: boolean
  tanween: boolean
  madd: boolean
  silent: boolean
}
const ghunnahAfterNoon = 'تثجدذزسشصضطظفقكب'
const waslTargets = 'يرملون' + ghunnahAfterNoon
const haraka = (e?: Event) => !!e && (e.fatha || e.damma || e.kasra)
const ghunnah = (e: Event) => e.shadda && 'نم'.includes(e.base)
function tokenize(arabic: string): Event[] {
  const out: Event[] = []
  for (const ch of arabic) {
    if ((ch >= 'ء' && ch <= 'غ') || (ch >= 'ف' && ch <= 'ي') || ch === 'ٱ') {
      out.push({
        base: ch === 'آ' ? 'ا' : ch,
        shadda: false,
        sukoon: false,
        maddah: ch === 'آ',
        fatha: false,
        damma: false,
        kasra: false,
        tanween: false,
        madd: false,
        silent: false,
      })
      continue
    }
    const e = out[out.length - 1]
    if (!e) continue
    if (ch === 'ّ') e.shadda = true
    else if (ch === 'ۡ') e.sukoon = true
    else if ('ْ۟۠'.includes(ch)) e.silent = true
    else if (ch === 'ٓ') e.maddah = true
    else if (ch === 'َ') e.fatha = true
    else if (ch === 'ُ') e.damma = true
    else if (ch === 'ِ') e.kasra = true
    else if ('ًࣰٌࣱٍࣲٖٗٞ'.includes(ch)) e.tanween = true
    else if ('ٰۥۦۧ'.includes(ch)) e.madd = true
  }
  return out
}
function weight(events: Event[], i: number): number {
  const e = events[i],
    prev = events[i - 1],
    next = events[i + 1]
  if (
    e.silent ||
    e.base === 'ٱ' ||
    (e.base === 'ل' && prev?.base === 'ٱ' && next?.shadda)
  )
    return 0
  if (e.maddah) return next && (next.shadda || next.sukoon) ? 6 : 4
  let n = haraka(e) || e.tanween ? 1 : 0
  if (e.shadda) n += 'نم'.includes(e.base) ? 2 : 1
  if (e.madd) n += haraka(e) ? 1 : 2
  if (e.sukoon) n += 'قطبجد'.includes(e.base) ? 0.75 : 0.5
  if (n > 0) return n
  if ('اى'.includes(e.base)) return prev?.fatha ? 2 : 0
  if ((e.base === 'و' && prev?.damma) || (e.base === 'ي' && prev?.kasra))
    return 2
  if (
    (e.base === 'ن' && next && ghunnahAfterNoon.includes(next.base)) ||
    (e.base === 'م' && next?.base === 'ب')
  )
    return 2
  return 0.5
}
function endsWithNoon(arabic: string): boolean {
  const events = tokenize(arabic)
  let i = events.length - 1
  while (i >= 0 && events[i].silent) i--
  const e = events[i]
  return (
    !!e &&
    (e.tanween ||
      (e.base === 'ا' && !!events[i - 1]?.tanween) ||
      (e.base === 'ن' && !haraka(e)))
  )
}
export function connection(
  prevArabic: string,
  arabic: string,
): { prefixFraction: number } | null {
  const events = tokenize(arabic)
  return endsWithNoon(prevArabic) &&
    events.length &&
    waslTargets.includes(events[0].base)
    ? { prefixFraction: 1 / events.length }
    : null
}
export function waslPrefixStart(sweepMs: number, minPrefixMs = 480): number {
  return 1 - clamp(Math.max(1, minPrefixMs) / Math.max(1, sweepMs), 0.18, 0.75)
}
export function waslPrefixCompletion(
  sweepMs: number,
  minPrefixMs = 480,
  maxCompletion = 0.45,
): number {
  return clamp(
    ((1 - waslPrefixStart(sweepMs, minPrefixMs)) * Math.max(1, sweepMs)) /
      Math.max(1, minPrefixMs),
    0,
    clamp(maxCompletion),
  )
}
export function connectionProgress(
  t: number,
  start = 0.82,
  completion = 1,
): number {
  const u = clamp(((t - start) / Math.max(1e-4, 1 - start)) * completion)
  return u * u * (3 - 2 * u)
}
export function tajweedCurve(
  arabic: string,
  spokenFraction = 1,
  options: Partial<Hold> = {},
  prevArabic?: string,
): PacingCurve | null {
  const hold = { ...DEFAULT_HOLD, ...options }
  const events = tokenize(arabic),
    n = events.length
  const counts = events.map((_, i) => weight(events, i))
  const letters = counts.filter((x) => x > 0).length
  if (letters < 3) return null
  let last = counts.length - 1
  while (last >= 0 && counts[last] <= 0) last--
  const wasl =
    hold.connect &&
    !!prevArabic &&
    endsWithNoon(prevArabic) &&
    waslTargets.includes(events[0].base)
  if (wasl) counts[0] = Math.max(counts[0], 2)
  const waqf = hold.waqf && hold.isAyahFinal
  let waqfIndex = waqf ? last : -1
  const isMadd = (i: number) => {
    const e = events[i],
      prev = events[i - 1]
    return (
      e.madd ||
      e.maddah ||
      ('اى'.includes(e.base) && prev?.fatha) ||
      (e.base === 'و' && prev?.damma && !haraka(e)) ||
      (e.base === 'ي' && prev?.kasra && !haraka(e))
    )
  }
  if (waqf && hold.maddAaridWaqf && !isMadd(last)) {
    let i = last - 1
    while (i >= 0 && counts[i] <= 0) i--
    if (i >= 0 && isMadd(i)) waqfIndex = i
  }
  if (waqfIndex >= 0) counts[waqfIndex] = Math.max(counts[waqfIndex], 6)
  const held = counts.map(
    (c, i) =>
      c > 0 &&
      ((wasl && i === 0) ||
        i === waqfIndex ||
        (hold.madd && c >= 4) ||
        (hold.ghunnah && c >= 2 && ghunnah(events[i]))),
  )
  if (!held.some(Boolean)) return null
  const s = clamp(hold.waqfLengthScale)
  const dwell = clamp(
    waqf
      ? hold.waqfShare * (1 - s + s * clamp((letters - 2) / 6))
      : 1 - 1 / Math.max(1, hold.cruiseCap),
    0,
    0.85,
  )
  if (dwell <= 0) return null
  const spoken = clamp(spokenFraction, 0.25, 1),
    creep = clamp(hold.creep, 0, 0.5)
  const slotWidth = (i: number) => {
    let start = i
    while (start > 0 && counts[start - 1] <= 0) start--
    return ((i === last ? n : i + 1) - start) / n
  }
  const excess = counts.map((c, i) => (held[i] ? Math.max(0.5, c - 1) : 0))
  const total = excess.reduce((a, b) => a + b, 0)
  const creepWidth = held.reduce(
    (sum, h, i) => sum + (h ? creep * slotWidth(i) : 0),
    0,
  )
  const cruise = (1 - creepWidth) / (1 - dwell)
  const times = [0],
    positions = [0]
  let t = 0,
    x = 0
  const point = () => {
    times.push(t * spoken)
    positions.push(x)
  }
  const glide = (target: number) => {
    if (target > x) {
      t += (target - x) / cruise
      x = target
      point()
    }
  }
  for (let i = 0; i < n; i++) {
    if (counts[i] <= 0) continue
    const end = i === last ? 1 : (i + 1) / n
    if (!held[i]) {
      glide(end)
      continue
    }
    const start = end - slotWidth(i)
    const ownOpening =
      !wasl && start <= 0 && (counts[i] >= 4 || ghunnah(events[i]))
    if (!ownOpening) glide(start + 0.5 * (end - start))
    t += (dwell * excess[i]) / total
    x = ownOpening ? Math.max(x, 0.28) : x + creep * (end - start)
    point()
  }
  if (x < 1) glide(1)
  times[times.length - 1] = spoken
  positions[positions.length - 1] = 1
  times.push(1)
  positions.push(1)
  return new PacingCurve(
    times,
    positions,
    letters,
    held.some((h, i) => h && !(wasl && i === 0)),
  )
}
