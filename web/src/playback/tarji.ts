/** PCM-driven tarjīʿ. Android Tarji.kt's 8 kHz / 20 ms analysis clock,
 * octave-folded hold identity, detrended AM/FM correlation and event lifecycle.
 * Visual phase is measured from the recording, never a synthesized oscillator.
 */
const clamp = (x: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, x))
export interface TarjiKnobs {
  minHz: number
  maxHz: number
  holdMinMs: number
  minDepth: number
  periodicity: number
  pitchDrift: number
  attackMs: number
  releaseMs: number
  brightness: number
}
export const TARJI_DEFAULTS: TarjiKnobs = {
  minHz: 1.5,
  maxHz: 10,
  holdMinMs: 300,
  minDepth: 0.035,
  periodicity: 0.4,
  pitchDrift: 0.12,
  attackMs: 250,
  releaseMs: 800,
  brightness: 1,
}
export function reciterTarjiKnobs(reciterId: number): TarjiKnobs {
  return reciterId === 7
    ? {
        ...TARJI_DEFAULTS,
        minHz: 2.6999998,
        maxHz: 8.3,
        holdMinMs: 1200,
        minDepth: 0.12308814,
        periodicity: 0.15000004,
        pitchDrift: 0.3,
        attackMs: 50,
        releaseMs: 100,
        brightness: 2,
      }
    : { ...TARJI_DEFAULTS }
}
export interface TarjiTrack {
  pulse: Float32Array
  gain: Float32Array
  rate: Float32Array
  event: Int32Array
}
export interface TarjiSample {
  pulse: number
  gain: number
  rate: number
  eventMs: number
}
export const QUIET_TARJI: TarjiSample = {
  pulse: 0,
  gain: 0,
  rate: 0,
  eventMs: -1,
}
export function sampleTarji(
  track: TarjiTrack | undefined,
  mediaMs: number,
): TarjiSample {
  if (
    !track ||
    !Number.isFinite(mediaMs) ||
    mediaMs < 0 ||
    mediaMs >= track.gain.length * 20
  )
    return QUIET_TARJI
  const p = Math.max(0, mediaMs / 20 - 1),
    a = Math.floor(p),
    b = Math.min(a + 1, track.gain.length - 1),
    f = p - a
  const mode = f < 0.5 ? a : b
  // Decisions live at hop ends; PCM phase lives at hop centres.
  const phase = clamp(mediaMs / 20 - 0.5, 0, track.pulse.length - 1)
  const pa = Math.floor(phase),
    pb = Math.min(pa + 1, track.pulse.length - 1)
  return {
    pulse: track.pulse[pa] + (phase - pa) * (track.pulse[pb] - track.pulse[pa]),
    gain: track.gain[a] + f * (track.gain[b] - track.gain[a]),
    rate: track.rate[mode],
    eventMs: track.event[mode] < 0 ? -1 : track.event[mode] * 20,
  }
}
export class TarjiEventLedger {
  private owners = new Map<number, number>()
  shownElsewhere(event: number, word: number): boolean {
    return this.owners.has(event) && this.owners.get(event) !== word
  }
  show(event: number, word: number): void {
    if (!this.owners.has(event)) this.owners.set(event, word)
    if (this.owners.size > 64)
      this.owners.delete(this.owners.keys().next().value!)
  }
}
/** One audible event belongs to one utterance, including a 600 ms timing tolerance. */
export class TarjiWordGate {
  private heard = false
  private finished = false
  private peak = 0
  private event = -1
  constructor(private ledger = new TarjiEventLedger()) {}
  allows(
    gain: number,
    detected: boolean,
    eventMs: number,
    wordStartMs: number,
  ): boolean {
    if (this.finished) return false
    const prior =
      eventMs < 0 ||
      wordStartMs < 0 ||
      eventMs < wordStartMs - 600 ||
      this.ledger.shownElsewhere(eventMs, wordStartMs)
    if (!this.heard && (!detected || prior)) return false
    if (gain > 0.01) {
      if (!this.heard) this.event = eventMs
      this.heard = true
      this.peak = Math.max(this.peak, gain)
      if (this.event >= 0) this.ledger.show(this.event, wordStartMs)
      return true
    }
    if (this.heard) {
      if (this.peak >= 0.3) this.finished = true
      else {
        this.heard = false
        this.peak = 0
        this.event = -1
      }
    }
    return false
  }
}
function fold(p: number, anchor: number): number {
  if (p <= 0 || anchor <= 0) return p
  while (p / anchor > 1.4142) p /= 2
  while (p / anchor < 0.7071) p *= 2
  return p
}
interface Scan {
  valid: boolean
  rate: number
  depth: number
  raw: number
  coherent: boolean
  lifecycle: boolean
  periodic: boolean
  keep: boolean
  balance: number
  lag: number
}
function scan(
  values: number[],
  live: number,
  liveLead: number,
  knobs: TarjiKnobs,
  previousLag: number,
  amplitude: boolean,
): Scan {
  const out: Scan = {
    valid: false,
    rate: 0,
    depth: 0,
    raw: 0,
    coherent: false,
    lifecycle: false,
    periodic: false,
    keep: false,
    balance: 0,
    lag: 0,
  }
  const n = values.length,
    mean = values.reduce((a, b) => a + b, 0) / n
  if (mean <= 0 || n < 16) return out
  const edge = Math.max(1, Math.floor(n / 4))
  const leading = values.slice(0, edge).reduce((a, b) => a + b, 0)
  const trailing = values.slice(-edge).reduce((a, b) => a + b, 0)
  const centre = (n - 1) / 2
  let numerator = 0,
    denominator = 0
  for (let j = 0; j < n; j++) {
    numerator += (j - centre) * (values[j] - mean)
    denominator += (j - centre) ** 2
  }
  const slope = numerator / Math.max(1, denominator)
  const residual = values.map((v, j) => v - (mean + slope * (j - centre)))
  const amp = Math.sqrt((2 * residual.reduce((a, b) => a + b * b, 0)) / n)
  const depth = amp / mean
  let sign = 0,
    crossings = 0
  for (const r of residual) {
    const next = r > amp * 0.1 ? 1 : r < -amp * 0.1 ? -1 : 0
    if (next) {
      if (sign && sign !== next) crossings++
      sign = next
    }
  }
  const crossingHz = (crossings * 1000) / (2 * (n - 1) * 20)
  const oscillatory =
    crossings >= 2 &&
    crossingHz >= knobs.minHz - 0.5 &&
    crossingHz <= knobs.maxHz + 1
  const minLag = clamp(Math.ceil(1000 / (knobs.maxHz * 20)), 2, 33)
  const maxLag = clamp(Math.floor(1000 / (knobs.minHz * 20)), minLag, 33)
  const computed = Math.min(34, n - 8),
    corr = new Float32Array(35),
    rawCorr = new Float32Array(35)
  for (let lag = 2; lag <= computed; lag++) {
    let c = 0,
      e0 = 0,
      e1 = 0,
      rc = 0,
      r0 = 0,
      r1 = 0
    for (let j = 0; j < n - lag; j++) {
      const a = residual[j],
        b = residual[j + lag],
        ra = values[j] - mean,
        rb = values[j + lag] - mean
      c += a * b
      e0 += a * a
      e1 += b * b
      rc += ra * rb
      r0 += ra * ra
      r1 += rb * rb
    }
    corr[lag] = c / (Math.sqrt(e0 * e1) + 1e-9)
    rawCorr[lag] = rc / (Math.sqrt(r0 * r1) + 1e-9)
  }
  let best = 0,
    rawBest = 0,
    bestLag = 0
  for (let lag = minLag; lag <= Math.min(maxLag, computed); lag++) {
    rawBest = Math.max(rawBest, amplitude ? rawCorr[lag] : corr[lag])
    if (corr[lag] > best) {
      best = corr[lag]
      bestLag = lag
    }
  }
  if (!oscillatory) bestLag = 0
  const lag =
    computed < maxLag || previousLag < minLag || previousLag > maxLag
      ? bestLag
      : bestLag === 0 || best < 0.4 || corr[previousLag] >= 0.7 * best
        ? previousLag
        : bestLag
  let shortLag = 0,
    shortC = 0
  const cheatLo = Math.floor(1000 / (2 * knobs.maxHz * 20)) + 1
  for (
    let l = Math.max(2, cheatLo);
    l <= Math.min(minLag - 1, computed - 1);
    l++
  ) {
    if (corr[l] >= corr[l - 1] && corr[l] >= corr[l + 1] && corr[l] > shortC) {
      shortC = corr[l]
      shortLag = l
    }
  }
  const leak =
    shortLag >= 2 &&
    lag > shortLag &&
    lag % shortLag === 0 &&
    lag <= 3 * shortLag &&
    shortC >= 0.85 * best
  const rate = lag > 0 ? 1000 / (lag * 20) : 0
  const inBand =
    lag > 0 &&
    oscillatory &&
    !leak &&
    rate >= knobs.minHz &&
    rate <= knobs.maxHz
  let liveSlope = slope
  if (lag > 0) {
    let difference = 0
    for (let j = 0; j < n - lag; j++) difference += values[j + lag] - values[j]
    liveSlope = difference / ((n - lag) * lag)
  }
  return {
    valid: true,
    rate,
    depth,
    raw:
      amp > 1e-6
        ? clamp(
            (live - (mean + liveSlope * (n - 1 + liveLead - centre))) / amp,
            -1.5,
            1.5,
          )
        : 0,
    coherent: inBand && best >= knobs.periodicity,
    lifecycle: inBand && rawBest >= knobs.periodicity,
    periodic:
      inBand && (best >= knobs.periodicity || (amplitude && depth >= 0.06)),
    keep:
      inBand &&
      (best >= knobs.periodicity * 0.7 || (amplitude && depth >= 0.06 * 0.7)),
    balance: Math.min(leading, trailing) / Math.max(leading, trailing),
    lag,
  }
}
/** 80 ms stable autocorrelation identity and short sub-lag YIN motion. */
function pitch(
  pcm: Float32Array,
  from: number,
  length: number,
  yin: boolean,
): { hz: number; clarity: number; lead: number; valid: boolean } {
  const min = Math.floor(8000 / 350),
    max = Math.floor(8000 / 70),
    pairs = length - max
  const corr = new Float32Array(max + 1)
  let energy = 0,
    best = 0,
    cumulative = 0
  for (let j = 0; j < pairs; j++) energy += pcm[from + j] ** 2
  if (energy <= 1e-8) return { hz: 0, clarity: 0, lead: 0, valid: false }
  for (let lag = yin ? 1 : min; lag <= max; lag++) {
    let value = 0
    for (let j = 0; j < pairs; j++) {
      const a = pcm[from + j],
        b = pcm[from + j + lag]
      value += yin ? (a - b) ** 2 : a * b
    }
    cumulative += value
    corr[lag] = yin
      ? cumulative > 1e-8
        ? (value * lag) / cumulative
        : 1
      : value
    if (!yin) best = Math.max(best, value)
  }
  let lag = min
  if (!yin) {
    if (best <= 0) return { hz: 0, clarity: 0, lead: 0, valid: false }
    while (lag <= max && corr[lag] < 0.95 * best) lag++
    return { hz: 8000 / lag, clarity: best / energy, lead: 0, valid: true }
  }
  let thresholdHit = false
  while (lag < max) {
    if (corr[lag] < 0.15) {
      thresholdHit = true
      while (lag < max && corr[lag + 1] < corr[lag]) lag++
      break
    }
    lag++
  }
  if (lag === max && corr[lag] >= 0.15) {
    lag = min
    for (let j = min + 1; j <= max; j++) if (corr[j] < corr[lag]) lag = j
  }
  const curvature = corr[lag - 1] - 2 * corr[lag] + corr[lag + 1]
  const fractional =
    lag > min && lag < max && Math.abs(curvature) > 1e-9
      ? lag +
        clamp((0.5 * (corr[lag - 1] - corr[lag + 1])) / curvature, -0.5, 0.5)
      : lag
  return {
    hz: 8000 / fractional,
    clarity: clamp(1 - corr[lag]),
    valid: thresholdHit,
    lead: (length - 80.5 - (pairs - 1 + fractional) / 2) / 160,
  }
}
/** Offline source-clock analysis: all work runs in a worker, never the paint loop. */
export interface TarjiFrame {
  hop: number
  rms: number
  level: number
  voiced: boolean
  holdMs: number
  holdStart: number
  f0: number
  quality: number
  clarity: number
  amPulse: number
  fmPulse: number
  pitchLead: number
  f0Valid: boolean
}
export function analyzeTarji(
  pcm: Float32Array,
  knobs: TarjiKnobs = TARJI_DEFAULTS,
  frames?: TarjiFrame[],
): TarjiTrack {
  const n = Math.floor(pcm.length / 160)
  const out: TarjiTrack = {
    pulse: new Float32Array(n),
    gain: new Float32Array(n),
    rate: new Float32Array(n),
    event: new Int32Array(n).fill(-1),
  }
  const env: number[] = [],
    pitches: number[] = []
  let peak = 0,
    level = 0,
    anchor = 0,
    holdMs = 0,
    holdStart = 0,
    misses = 0
  let rev = false,
    confirmed = false,
    ended = false,
    waiting = false,
    useAm = true
  let eventPeak = 0,
    eventRate = 0,
    eventHops = 0,
    under = 0,
    pulseUnder = 0,
    gap = 0,
    grace = 0
  let tracked = 0,
    trackedPitch = 0,
    gain = 0,
    eventStart = -1
  for (let hop = 3; hop < n; hop++) {
    const end = (hop + 1) * 160
    let sum = 0,
      hopSum = 0
    for (let j = end - 640; j < end; j++) {
      sum += pcm[j] ** 2
      if (j >= end - 160) hopSum += pcm[j] ** 2
    }
    const rms = Math.sqrt(sum / 640),
      liveRms = Math.sqrt(hopSum / 160)
    peak = Math.max(rms, peak * 0.997)
    level += 0.39 * (rms - level)
    const stable = pitch(pcm, end - 640, 640, false),
      motion = pitch(pcm, end - 320, 320, true)
    const voiced = stable.clarity >= 0.5 && rms >= Math.max(0.006, 0.15 * peak)
    if (voiced) {
      if (
        holdMs > 0 &&
        anchor > 0 &&
        Math.abs(fold(stable.hz, anchor) / anchor - 1) <= knobs.pitchDrift
      ) {
        holdMs += 20
        anchor += 0.1 * (fold(stable.hz, anchor) - anchor)
      } else {
        holdMs = 20
        anchor = stable.hz
        holdStart = env.length + 1
        eventPeak = 0
        eventRate = 0
        eventHops = 0
        under = 0
        pulseUnder = 0
        gap = 0
        grace = 0
        confirmed = false
        waiting = false
        ended = false
        tracked = 0
        trackedPitch = 0
      }
      misses = 0
    } else if (++misses > 6) {
      holdMs = 0
      anchor = 0
      ended = true
      tracked = 0
      trackedPitch = 0
    }
    env.push(rms)
    const motionPitch =
      voiced && motion.clarity >= 0.5 && anchor > 0
        ? fold(motion.hz, anchor)
        : (pitches[pitches.length - 1] ?? 0)
    pitches.push(motionPitch)
    const length = Math.min(env.length - holdStart, 64),
      start = env.length - length
    const was = rev
    let raw = 0,
      rate = 0,
      amPulse = 0,
      fmPulse = 0
    if (length < 16) rev = false
    else {
      const am = scan(env.slice(start), liveRms, 1.5, knobs, tracked, true)
      tracked = am.lag
      const latest = pitches[pitches.length - 1],
        prev = pitches[pitches.length - 2],
        before = pitches[pitches.length - 3]
      const h = clamp(motion.lead),
        projected =
          ((h + 1) * (h + 2) * latest) / 2 -
          h * (h + 2) * prev +
          (h * (h + 1) * before) / 2
      const fm = scan(
        pitches.slice(start),
        projected,
        motion.lead,
        knobs,
        trackedPitch,
        false,
      )
      trackedPitch = fm.lag
      amPulse = am.raw
      fmPulse = fm.raw
      if (!am.valid) {
        rev = false
        ended = true
      } else {
        const amOpen =
          am.depth >= knobs.minDepth && am.periodic && am.balance >= 0.35
        const amKeep = am.depth >= knobs.minDepth * 0.7 && am.keep
        const fmOpen = fm.depth >= 0.006 && fm.periodic,
          fmKeep = fm.depth >= 0.006 * 0.7 && fm.keep
        useAm = !rev
          ? amOpen || !fmOpen
          : useAm && !amKeep && fmKeep
            ? false
            : !useAm && !fmKeep && amKeep
              ? true
              : useAm
        const chosen = useAm ? am : fm
        raw = chosen.raw
        rate = chosen.rate
        const coherent = am.coherent || fm.coherent,
          lifecycle = am.lifecycle || fm.coherent
        const beforeGap = gap
        gap = coherent ? 0 : !amKeep && !fmKeep ? gap + 1 : gap >= 10 ? gap : 0
        if (ended && coherent && beforeGap >= 10) {
          ended = false
          eventPeak = 0
          under = 0
          pulseUnder = 0
          eventRate = 0
          grace = 0
          confirmed = false
          eventHops = 0
        }
        if (eventPeak > 0) eventPeak = Math.max(eventPeak, level)
        under = eventPeak > 0 && level < 0.52 * eventPeak ? under + 1 : 0
        let climaxOver = under >= 24
        const next: boolean =
          holdMs >= knobs.holdMinMs &&
          !ended &&
          (rev
            ? amKeep || fmKeep
            : (amOpen || fmOpen) && (!waiting || lifecycle))
        const ratio =
          eventRate > 0 && rate > 0
            ? Math.max(eventRate / rate, rate / eventRate)
            : 1
        if (rev && ratio <= 3) eventRate += 0.1 * (rate - eventRate)
        const bridge = eventRate > 0 && ratio <= 3 && (amKeep || fmKeep)
        if (climaxOver && bridge) {
          eventPeak = level
          under = 0
          climaxOver = false
          grace = 41
        }
        const readyMs = (Math.floor(1000 / (knobs.minHz * 20)) + 9) * 20
        pulseUnder =
          holdMs < readyMs || lifecycle || grace > 0 || (bridge && under > 0)
            ? 0
            : eventPeak > 0 || next
              ? pulseUnder + 1
              : 0
        if (grace > 0) grace--
        if (eventPeak > 0 && lifecycle) confirmed = true
        const limit =
          confirmed && eventHops < 50
            ? Math.max(
                10,
                Math.ceil(2000 / (Math.max(knobs.minHz, eventRate) * 20)),
              )
            : 10
        const falseStart =
          eventPeak > 0 && !confirmed && !climaxOver && pulseUnder >= limit
        if (falseStart) {
          eventPeak = 0
          under = 0
          pulseUnder = 0
          eventRate = 0
          grace = 0
          eventHops = 0
          waiting = true
        } else if (climaxOver || pulseUnder >= limit) ended = true
        rev = next && !ended && !falseStart
        if (eventPeak === 0 && rev) {
          eventPeak = level
          eventRate = rate
          under = 0
          eventHops = 0
          confirmed = lifecycle
          waiting = false
        }
      }
    }
    if (!was && rev) eventStart = hop
    let target = 0
    if (rev && confirmed) {
      eventHops++
      target =
        clamp(eventHops / 50) *
        clamp(((eventPeak > 0 ? level / eventPeak : 1) - 0.52) / 0.23)
    }
    const tau = rev ? knobs.attackMs : ended ? 50 : knobs.releaseMs
    gain += Math.min(1, 20 / Math.max(1, tau)) * (target - gain)
    out.pulse[hop] = rev ? raw : raw * gain
    out.gain[hop] = gain
    out.rate[hop] = rate
    out.event[hop] = rev ? eventStart : -1
    frames?.push({
      hop,
      rms: liveRms,
      level,
      voiced,
      holdMs,
      holdStart,
      f0: motionPitch,
      quality: motion.clarity,
      clarity: stable.clarity,
      amPulse,
      fmPulse,
      pitchLead: motion.lead,
      f0Valid:
        motion.valid &&
        voiced &&
        holdMs >= 40 &&
        motionPitch >= 70 &&
        motionPitch <= 350,
    })
  }
  return out
}
