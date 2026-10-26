/** Android TarjiRecordingDetector: two-sided cycles, conservative recording
 * noise calibration, event lifecycle and per-reciter drama admission.
 * The full recording decides admission; source PCM supplies visual phase.
 */
import {
  analyzeTarji,
  TARJI_DEFAULTS,
  type TarjiFrame,
  type TarjiKnobs,
  type TarjiTrack,
} from './tarji'
const clamp = (x: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, x))
interface Extremum {
  time: number
  value: number
  segment: number
}
function extrema(
  series: number[],
  weights: number[],
  retreat: number,
): Extremum[] {
  const out: Extremum[] = []
  let direction = 0,
    high = -Infinity,
    low = Infinity,
    highAt = 0,
    lowAt = 0,
    missing = 0,
    section = 0
  const add = (at: number, value: number) => {
    if (at <= 0 || at >= series.length - 1) return
    let shift = 0
    if (weights[at - 1] > 0 && weights[at + 1] > 0) {
      const curvature = series[at - 1] - 2 * value + series[at + 1]
      if (Math.abs(curvature) > 1e-8)
        shift = clamp(
          (0.5 * (series[at - 1] - series[at + 1])) / curvature,
          -0.5,
          0.5,
        )
    }
    out.push({ time: at + shift, value, segment: section })
  }
  for (let i = 0; i < series.length; i++) {
    if (weights[i] <= 0) {
      if (++missing === 3) {
        section++
        direction = 0
        high = -Infinity
        low = Infinity
      }
      continue
    }
    missing = 0
    const v = series[i]
    if (direction >= 0) {
      if (v >= high) {
        high = v
        highAt = i
      }
      if (high - v >= retreat) {
        add(highAt, high)
        direction = -1
        low = v
        lowAt = i
      }
    }
    if (direction <= 0) {
      if (v <= low) {
        low = v
        lowAt = i
      }
      if (v - low >= retreat) {
        add(lowAt, low)
        direction = 1
        high = v
        highAt = i
      }
    }
  }
  return out
}
interface Region {
  accepted: boolean[]
  rate: Float32Array
  balance: Float32Array
}
function region(n: number): Region {
  return {
    accepted: Array(n).fill(false),
    rate: new Float32Array(n),
    balance: new Float32Array(n).fill(1),
  }
}
function regions(
  source: number[],
  quality: number[],
  from: number,
  until: number,
  knobs: TarjiKnobs,
  floor: number,
  out: Region,
  amplitude: boolean,
): void {
  const n = until - from
  if (n < 12) return
  const values = source.slice(from, until),
    weights = quality.slice(from, until)
  const residual = Array<number>(n).fill(0),
    periods = Array<number>(n).fill(1000 / (Math.max(1.5, knobs.minHz) * 20))
  const centre = () => {
    for (let i = 0; i < n; i++) {
      const radius = Math.min(i, n - 1 - i, Math.round(periods[i] * 0.5))
      let sum = 0,
        weight = 0
      for (let j = i - radius; j <= i + radius; j++) {
        sum += values[j] * weights[j]
        weight += weights[j]
      }
      residual[i] = weight > 0 ? values[i] - sum / weight : 0
    }
  }
  const cycleRate = (period: number) => {
    const hz = 1000 / (period * 20)
    return period > 0 && hz >= knobs.minHz && hz <= knobs.maxHz ? hz : null
  }
  centre()
  let peaks = extrema(residual, weights, 0.7 * floor)
  for (let i = 0; i < peaks.length - 2; i++) {
    if (peaks[i].segment !== peaks[i + 2].segment) continue
    const period = peaks[i + 2].time - peaks[i].time
    if (cycleRate(period) == null) continue
    for (
      let j = Math.ceil(peaks[i].time);
      j <= Math.floor(peaks[i + 2].time);
      j++
    )
      periods[j] = period
  }
  centre()
  peaks = extrema(residual, weights, 0.7 * floor)
  const cycles = Math.max(0, peaks.length - 2),
    strong = Array(cycles).fill(false),
    weak = Array(cycles).fill(false)
  const rates = Array(cycles).fill(0),
    balances = Array(cycles).fill(1)
  for (let i = 0; i < cycles; i++) {
    if (peaks[i].segment !== peaks[i + 2].segment) continue
    const period = peaks[i + 2].time - peaks[i].time,
      rate = cycleRate(period)
    if (rate == null) continue
    const peer =
      i + 4 < peaks.length && peaks[i].segment === peaks[i + 4].segment
        ? i + 2
        : i >= 2 && peaks[i].segment === peaks[i - 2].segment
          ? i - 2
          : -1
    if (peer < 0) continue
    const peerPeriod = peaks[peer + 2].time - peaks[peer].time
    const jitter = Math.abs(period - peerPeriod) / Math.max(period, peerPeriod)
    const depth =
      Math.min(
        Math.abs(peaks[i + 1].value - peaks[i].value),
        Math.abs(peaks[i + 2].value - peaks[i + 1].value),
      ) * 0.5
    const first = Math.ceil(peaks[i].time),
      last = Math.floor(peaks[i + 2].time)
    let valid = 0
    for (let j = first; j <= last; j++) if (weights[j] > 0) valid++
    const coverage = valid / Math.max(1, last - first + 1),
      fraction = (peaks[i + 1].time - peaks[i].time) / period
    if (fraction < 0.2 || fraction > 0.8) continue
    if (amplitude) {
      const mid = Math.floor((first + last) / 2)
      let a = 0,
        b = 0
      for (let j = first; j <= mid; j++) a += values[j]
      for (let j = mid + 1; j <= last; j++) b += values[j]
      balances[i] = Math.exp(
        -Math.abs(a / (mid - first + 1) - b / Math.max(1, last - mid)),
      )
    }
    strong[i] =
      jitter <= 0.2 && depth >= floor && coverage >= 0.85 && balances[i] >= 0.35
    weak[i] =
      jitter <= 0.35 &&
      depth >= 0.7 * floor &&
      coverage >= 0.7 &&
      balances[i] >= 0.35
    rates[i] = rate
  }
  for (let seed = 0; seed < cycles - 2; seed++) {
    if (
      !strong[seed] ||
      !strong[seed + 2] ||
      peaks[seed].segment !== peaks[seed + 4].segment
    )
      continue
    let first = seed,
      last = seed + 2
    while (
      first > 0 &&
      weak[first - 1] &&
      peaks[first - 1].segment === peaks[seed].segment
    )
      first--
    while (
      last + 1 < cycles &&
      weak[last + 1] &&
      peaks[last + 3].segment === peaks[seed].segment
    )
      last++
    for (let c = first; c <= last; c++)
      for (
        let j = Math.ceil(peaks[c].time);
        j <= Math.floor(peaks[c + 2].time);
        j++
      ) {
        if (weights[j] <= 0) continue
        out.accepted[from + j] = true
        out.rate[from + j] = rates[c]
        out.balance[from + j] = balances[c]
      }
  }
}
const median = (a: number[]) =>
  a.sort((a, b) => a - b)[Math.floor(a.length / 2)] ?? 0
/** Standout-event scoring from Android TarjiDrama / TarjiDramaWeights. */
export function dramaScore(
  eventMs: number,
  holdMs: number,
  loudness: number,
  pitchRatio: number,
  reciterId = 0,
): number {
  const ramp = (x: number, a: number, b: number) => clamp((x - a) / (b - a))
  const db = loudness > 0 ? 20 * Math.log10(loudness) : -60,
    st = pitchRatio > 0 ? 12 * Math.log2(pitchRatio) : 0
  if (reciterId === 7) {
    const loud = ramp(db, 1, 5),
      pitch = Math.abs(st) <= 4 && db >= 0.5 ? ramp(st, 1, 3) : 0
    return Math.sqrt(ramp(eventMs, 400, 1000) * clamp(loud + 0.5 * pitch))
  }
  const lift = Math.max(
    ramp(db, -2, 3),
    Math.abs(st) <= 6 ? ramp(st, -1, 3) : 0,
  )
  return (
    0.4 * ramp(eventMs, 600, 1500) +
    0.35 * ramp(holdMs, 1200, 3000) +
    0.25 * lift
  )
}
export function analyzeRecording(
  pcm: Float32Array,
  knobs: TarjiKnobs = TARJI_DEFAULTS,
  reciterId = 0,
): TarjiTrack {
  const frames: TarjiFrame[] = []
  const track = analyzeTarji(pcm, knobs, frames)
  const n = frames.length,
    rms = frames.map((f) => f.rms),
    am: number[] = [],
    fm: number[] = [],
    aw: number[] = [],
    fw: number[] = []
  let hold = -1,
    reference = 0
  for (let i = 0; i < n; i++) {
    const f = frames[i]
    if (hold !== f.holdStart) {
      hold = f.holdStart
      reference = 0
    }
    const before =
      i > 0 && frames[i - 1].holdStart === hold ? rms[i - 1] : rms[i]
    const after =
      i < n - 1 && frames[i + 1].holdStart === hold ? rms[i + 1] : rms[i]
    am[i] = Math.log(Math.max(1e-5, (before + 2 * rms[i] + after) * 0.25))
    aw[i] = f.voiced ? 1 : 0
    const valid = f.f0Valid
    if (valid) {
      if (!reference) reference = f.f0
      fm[i] = 1200 * Math.log2(f.f0 / reference)
      fw[i] = f.quality
    } else {
      fm[i] = 0
      fw[i] = 0
    }
  }
  const rawPitch = fm.slice()
  for (let i = 0; i < n; i++) {
    if (fw[i] <= 0) continue
    let sum = 2 * rawPitch[i],
      weight = 2
    for (const j of [i - 1, i + 1])
      if (
        j >= 0 &&
        j < n &&
        fw[j] > 0 &&
        frames[j].holdStart === frames[i].holdStart
      ) {
        sum += rawPitch[j]
        weight++
      }
    fm[i] = sum / weight
  }
  const p20 = rms.slice().sort((a, b) => a - b)[Math.floor(n / 5)] ?? 0
  const noise = frames
    .filter((f) => f.rms < p20 && f.clarity < 0.5)
    .map((f) => f.rms)
  const noiseMedian = median(noise),
    mad = median(noise.map((v) => Math.abs(v - noiseMedian)))
  const floor = Math.max(
    0.006,
    noise.length >= 25 && mad > 0 ? noiseMedian + 6 * mad : 0,
  )
  const ar = region(n),
    fr = region(n)
  for (let from = 0; from < n; ) {
    if (!frames[from].voiced || frames[from].holdMs <= 0) {
      from++
      continue
    }
    let until = from + 1
    while (
      until < n &&
      frames[until].voiced &&
      frames[until].holdStart === frames[from].holdStart
    )
      until++
    regions(
      am,
      aw,
      from,
      until,
      knobs,
      Math.atanh(clamp(knobs.minDepth, 0.035, 0.95)),
      ar,
      true,
    )
    regions(fm, fw, from, until, knobs, 10, fr, false)
    from = until
  }
  const modes = new Uint8Array(track.gain.length).fill(1)
  track.gain.fill(0)
  track.event.fill(-1)
  track.pulse.fill(0)
  track.rate.fill(0)
  let active = false,
    ended = false,
    useAm = true,
    peak = 0,
    rate = 0,
    under = 0,
    pulseUnder = 0,
    gap = 0,
    hops = 0,
    grace = 0,
    gain = 0,
    start = -1
  hold = -1
  for (let i = 0; i < n; i++) {
    const f = frames[i]
    if (hold !== f.holdStart) {
      hold = f.holdStart
      active = false
      ended = false
      peak = 0
      rate = 0
      under = 0
      pulseUnder = 0
      gap = 0
      hops = 0
      grace = 0
      gain = 0
    }
    const was: boolean = active
    const loud = f.level >= floor,
      keepLoud = f.level >= 0.7 * floor
    const ao = loud && f.voiced && ar.accepted[i] && ar.balance[i] >= 0.35,
      fo = loud && f.voiced && fr.accepted[i]
    const ak = keepLoud && f.voiced && ar.accepted[i],
      fk = keepLoud && f.voiced && fr.accepted[i]
    const coherent = ak || fk,
      beforeGap = gap
    gap = coherent ? 0 : gap + 1
    if (ended && coherent && beforeGap >= 10) {
      peak = 0
      rate = 0
      under = 0
      pulseUnder = 0
      hops = 0
      grace = 0
      ended = false
    }
    useAm = !was
      ? ao || !fo
      : useAm && !ak && fk
        ? false
        : !useAm && !fk && ak
          ? true
          : useAm
    const currentRate = useAm ? ar.rate[i] : fr.rate[i]
    const ratio =
      currentRate > 0 && rate > 0
        ? Math.max(currentRate / rate, rate / currentRate)
        : 1
    if (was && ratio <= 3) rate += 0.1 * (currentRate - rate)
    if (peak > 0) peak = Math.max(peak, f.level)
    under = peak > 0 && f.level < 0.52 * peak ? under + 1 : 0
    let climaxOver = under >= 24
    const bridge = rate > 0 && ratio <= 3 && coherent
    if (climaxOver && bridge) {
      peak = f.level
      under = 0
      climaxOver = false
      grace = Math.ceil(1000 / (Math.max(1.5, knobs.minHz) * 20)) + 8
    }
    const next: boolean =
      f.holdMs >= knobs.holdMinMs &&
      f.holdMs > 0 &&
      !ended &&
      (was ? coherent : ao || fo)
    pulseUnder =
      coherent || grace > 0 || (bridge && under > 0)
        ? 0
        : peak > 0 || next
          ? pulseUnder + 1
          : 0
    if (grace > 0) grace--
    const limit =
      hops < 50 && rate > 0 ? Math.max(10, Math.ceil(2000 / (rate * 20))) : 10
    if (climaxOver || pulseUnder >= limit || f.holdMs <= 0) ended = true
    active = next && !ended
    if (active && peak === 0) {
      peak = f.level
      rate = currentRate
      hops = 0
    }
    if (active && !was) start = f.hop + 1
    let target = 0
    if (active) {
      hops++
      target =
        clamp(hops / 50) *
        clamp(((peak > 0 ? f.level / peak : 1) - 0.52) / 0.23)
    }
    const tau = active ? knobs.attackMs : ended ? 50 : knobs.releaseMs
    gain += Math.min(1, 20 / Math.max(1, tau)) * (target - gain)
    track.gain[f.hop] = gain
    track.rate[f.hop] = currentRate
    track.event[f.hop] = active ? start : -1
    modes[f.hop] = useAm ? 1 : 0
  }
  const typicalRms = median(frames.filter((f) => f.voiced).map((f) => f.rms))
  const typicalPitch = median(frames.filter((f) => f.f0Valid).map((f) => f.f0))
  const events = new Map<number, TarjiFrame[]>()
  for (const f of frames) {
    const event = track.event[f.hop]
    if (event >= 0) {
      const group = events.get(event) ?? []
      group.push(f)
      events.set(event, group)
    }
  }
  const scores = [...events.entries()]
    .map(([id, group]) => {
      const loudness =
        typicalRms > 0
          ? group.reduce((sum, f) => sum + f.rms, 0) / group.length / typicalRms
          : 1
      const pitch = median(group.filter((f) => f.f0Valid).map((f) => f.f0))
      const score = dramaScore(
        group.length * 20,
        Math.max(...group.map((f) => f.holdMs)),
        loudness,
        typicalPitch > 0 && pitch > 0 ? pitch / typicalPitch : 1,
        reciterId,
      )
      const peak = Math.max(...group.map((f) => track.gain[f.hop]))
      return { id, score: peak >= (reciterId === 7 ? 0.3 : 0) ? score : -1 }
    })
    .filter((e) => e.score >= 0.5)
    .sort((a, b) => b.score - a.score)
  const kept = new Set(
    scores.slice(0, reciterId === 7 ? 2 : scores.length).map((e) => e.id),
  )
  for (let i = 0; i < track.event.length; i++)
    if (track.event[i] >= 0 && !kept.has(track.event[i])) {
      track.gain[i] = 0
      track.event[i] = -1
    }
  // Android TarjiEarPulse: centred period baseline and 1-2-1 blur. The
  // long correlation scan decides admission, never the visible phase.
  const levels = new Float32Array(track.gain.length),
    pitches = new Float32Array(track.gain.length)
  const leads = new Float32Array(track.gain.length)
  for (const f of frames) {
    levels[f.hop] = f.rms
    pitches[f.hop] = f.f0
    leads[f.hop] = f.pitchLead
  }
  for (let hop = 0; hop < track.pulse.length; hop++) {
    const period =
      track.rate[hop] > 0
        ? clamp(Math.round(1000 / (track.rate[hop] * 20)), 6, 16)
        : 10
    const amplitude = modes[hop] === 1,
      series = amplitude ? levels : pitches
    const at = clamp(
      hop + (amplitude ? 0 : leads[hop]),
      0,
      track.pulse.length - 1,
    )
    const first = Math.floor(at),
      second = Math.min(first + 1, track.pulse.length - 1)
    const a = earPulse(series, first, period, amplitude ? 0.03 : 0.003)
    const b =
      second === first
        ? a
        : earPulse(series, second, period, amplitude ? 0.03 : 0.003)
    track.pulse[hop] = a + (at - first) * (b - a)
  }
  return track
}

export function earPulse(
  series: Float32Array,
  hop: number,
  period: number,
  floor: number,
): number {
  const half = Math.floor(period / 2),
    newest = series.length - 1
  const valueAt = (i: number) => series[clamp(i, 0, newest)]
  let sumSq = 0,
    level = 0,
    flutter = 0
  for (let j = hop - period; j <= hop; j++) {
    let mean = 0
    for (let k = j - half; k <= j + half; k++) {
      mean +=
        (period % 2 === 0 && (k === j - half || k === j + half) ? 0.5 : 1) *
        valueAt(k)
    }
    mean /= period % 2 === 0 ? period : 2 * half + 1
    const value = (valueAt(j - 1) + 2 * valueAt(j) + valueAt(j + 1)) * 0.25
    const residual = value - mean
    sumSq += residual * residual
    if (j === hop) {
      level = mean
      flutter = residual
    }
  }
  return clamp(
    flutter /
      Math.max(
        Math.sqrt((2 * sumSq) / (period + 1)),
        floor * Math.abs(level),
        1e-6,
      ),
    -1.5,
    1.5,
  )
}
