import { PreparedTimings, type ActiveInfo } from './HighlightEngine'
import { heardMs, highlightMs } from './OutputLatency'

export const HIGHLIGHT_ENGINE_VERSION = 2
export interface HighlightItem { surah: number; ayah: number; reciter: number }
export interface HighlightPlaybackSample {
  item: HighlightItem; positionMs: number; sampledAtNanos: number; isPlaying: boolean
  isBuffering: boolean; speed: number; clockRevision: number; inkGeneration: number
}
export interface HighlightTimingContext { revision: number; timings: PreparedTimings; voiceStartMs: number }
export interface HighlightCalibration { outputLagWallMs: number; wordLeadMs: number }
export interface HighlightSyncState {
  sample: HighlightPlaybackSample; timing: HighlightTimingContext; calibration: HighlightCalibration
  acceptedMediaMs: number; projectionBaseMs: number; retainedInkMs: number
  settleStartNanos: number; stableSamples: number; suspended?: boolean
}
export interface HighlightFrame {
  item: HighlightItem; inkGeneration: number; timingRevision: number; mediaMs: number
  heardMs: number; inkMs: number; occurrence: number | null; active: ActiveInfo | null
  phase: 'MissingTimings' | 'WaitingForVoice' | 'Speaking' | 'GapHold' | 'Paused' | 'Buffering' | 'Completed'
  linearProgress: number
}
const advances = (s: HighlightPlaybackSample) => s.isPlaying && !s.isBuffering
const sameItem = (a: HighlightItem, b: HighlightItem) => a.surah === b.surah && a.ayah === b.ayah && a.reciter === b.reciter
const lag = (c: HighlightCalibration, speed: number) => Math.trunc(c.outputLagWallMs * speed)
const ink = (media: number, c: HighlightCalibration, speed: number, voice: number) => highlightMs(media, lag(c, speed), c.wordLeadMs, voice)

/** Pure Android V2 policy. Timestamps use the same monotonic nanosecond units in shared replay. */
export function sampleHighlight(previous: HighlightSyncState | null, next: HighlightPlaybackSample,
  timing: HighlightTimingContext, calibration: HighlightCalibration = { outputLagWallMs: 0, wordLeadMs: 0 }): HighlightSyncState {
  if (next.positionMs < 0 || !Number.isFinite(next.speed) || next.speed <= 0 || next.clockRevision < 0 || next.inkGeneration < 0)
    throw new Error('Invalid highlight playback sample')
  const old = previous?.sample
  if (previous && old && (next.sampledAtNanos < old.sampledAtNanos || next.clockRevision < old.clockRevision ||
    next.inkGeneration < old.inkGeneration || timing.revision < previous.timing.revision)) return previous
  const same = old != null && sameItem(old.item, next.item) && old.inkGeneration === next.inkGeneration
  const retained = same && previous ? previous.suspended ? previous.retainedInkMs : highlightFrame(previous, next.sampledAtNanos).inkMs : 0
  if (!previous || !old || !same || old.clockRevision !== next.clockRevision || previous.suspended)
    return { sample: next, timing, calibration, acceptedMediaMs: next.positionMs, projectionBaseMs: next.positionMs,
      retainedInkMs: retained, settleStartNanos: next.sampledAtNanos, stableSamples: 0 }
  const expected = advances(old) ? (next.sampledAtNanos - old.sampledAtNanos) / 1e6 * old.speed : 0
  const resuming = !advances(old) && advances(next)
  const settleStartNanos = resuming ? next.sampledAtNanos : previous.settleStartNanos
  const settleNs = next.sampledAtNanos - settleStartNanos
  const settling = settleNs < 1.5e9 && (settleNs < 4e8 || previous.stableSamples < 4 || resuming)
  const believable = Math.max(66, expected * 2)
  const rawAdvance = next.positionMs - old.positionMs
  const gap = next.positionMs - previous.acceptedMediaMs
  const stableSamples = advances(old) && rawAdvance >= 0 && rawAdvance <= believable && gap >= 0 ? Math.min(4, previous.stableSamples + 1) : 0
  const acceptedMediaMs = gap < 0 || settling && rawAdvance > Math.max(100, expected * 3) ? previous.acceptedMediaMs :
    settling && gap > believable ? previous.acceptedMediaMs + Math.trunc(believable) : next.positionMs
  return { sample: next, timing, calibration, acceptedMediaMs, projectionBaseMs: Math.min(acceptedMediaMs, next.positionMs),
    retainedInkMs: retained, settleStartNanos, stableSamples }
}

/** Project media before calibration; ordinary samples never stop interpolation at a poll boundary. */
export function highlightFrame(state: HighlightSyncState, atNanos: number): HighlightFrame {
  const { sample: s, timing: t, calibration: c } = state
  const elapsed = advances(s) && !state.suspended ? Math.max(0, atNanos - s.sampledAtNanos) / 1e6 * s.speed : 0
  const mediaMs = Math.trunc(state.projectionBaseMs + elapsed)
  const inkMs = Math.max(state.retainedInkMs, ink(mediaMs, c, s.speed, t.voiceStartMs))
  const segments = t.timings.segments
  const before = inkMs < t.voiceStartMs
  const occurrence = before ? null : t.timings.activeOccurrence(inkMs)
  const active = occurrence == null ? null : t.timings.infoAtOccurrence(occurrence)
  const phase = segments.length === 0 ? 'MissingTimings' : s.isBuffering ? 'Buffering' : !s.isPlaying ? 'Paused' :
    before || inkMs < segments[0]!.startMs ? 'WaitingForVoice' : inkMs >= segments.at(-1)!.endMs ? 'Completed' :
    active && inkMs >= active.endMs ? 'GapHold' : 'Speaking'
  const start = occurrence === 0 && active ? Math.max(active.startMs, t.voiceStartMs) : active?.startMs
  const duration = active && start != null ? active.holdEndMs - start : 0
  const linearProgress = !active || start == null ? 0 : duration <= 0 ? 1 : Math.max(0, Math.min(1, (inkMs - start) / duration))
  return { item: s.item, inkGeneration: s.inkGeneration, timingRevision: t.revision, mediaMs,
    heardMs: heardMs(mediaMs, lag(c, s.speed)), inkMs, phase, occurrence, active, linearProgress }
}
export function suspendHighlight(state: HighlightSyncState, atNanos: number): HighlightSyncState {
  const frame = highlightFrame(state, atNanos)
  return { ...state, projectionBaseMs: frame.mediaMs, retainedInkMs: frame.inkMs, suspended: true }
}
