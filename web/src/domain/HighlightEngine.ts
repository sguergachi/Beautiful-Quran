/**
 * Pure mapping from a playback position to the word that should be lit.
 * Port of Android `domain/HighlightEngine.kt` — keep in lockstep.
 */
import type { Segment } from '../data/models'

export interface ActiveInfo {
  position: number
  startMs: number
  endMs: number
  /**
   * When the karaoke hold ends: next segment start, or [endMs] for the last
   * word. Letter sweep uses this so a min-sweep clamp cannot outlive Active.
   */
  holdEndMs: number
  isRepeat: boolean
  highWater: number
  /** First word of the current repeat chain. Equals [position] when not repeating. */
  repeatStart: number
}

export class PreparedTimings {
  private constructor(
    readonly segments: Segment[],
    private readonly maxBeforeByIndex: Int32Array,
    private readonly repeatStartByIndex: Int32Array,
  ) {}

  activeInfo(positionMs: number): ActiveInfo | null {
    const idx = this.activeOccurrence(positionMs)
    return idx == null ? null : this.infoAtOccurrence(idx)
  }

  activeOccurrence(positionMs: number): number | null { return activeIndex(this.segments, positionMs) }

  infoAtOccurrence(idx: number): ActiveInfo {
    const seg = this.segments[idx]!
    const maxBefore = this.maxBeforeByIndex[idx]!
    const next = this.segments[idx + 1]
    const holdEndMs = Math.max(seg.startMs, next ? next.startMs : seg.endMs)
    return {
      position: seg.position,
      startMs: seg.startMs,
      endMs: seg.endMs,
      holdEndMs,
      isRepeat: seg.position <= maxBefore,
      highWater: Math.max(maxBefore, seg.position),
      repeatStart: this.repeatStartByIndex[idx]!,
    }
  }

  static prepare(segments: Segment[]): PreparedTimings {
    const n = segments.length
    const maxBefore = new Int32Array(n)
    const repeatStart = new Int32Array(n)
    let runningMax = -1
    for (let i = 0; i < n; i++) {
      maxBefore[i] = runningMax
      const pos = segments[i]!.position
      const isRepeat = pos <= runningMax
      if (isRepeat) {
        let startIndex = i
        while (
          startIndex > 0 &&
          segments[startIndex - 1]!.position <= maxBefore[startIndex - 1]!
        ) {
          startIndex--
        }
        let minPos = segments[startIndex]!.position
        for (let j = startIndex + 1; j <= i; j++) {
          minPos = Math.min(minPos, segments[j]!.position)
        }
        repeatStart[i] = minPos
      } else {
        repeatStart[i] = pos
      }
      runningMax = Math.max(runningMax, pos)
    }
    return new PreparedTimings(segments, maxBefore, repeatStart)
  }
}

export function activeWord(segments: Segment[], positionMs: number): number | null {
  return activeSegment(segments, positionMs)?.position ?? null
}

export function activeSegment(segments: Segment[], positionMs: number): Segment | null {
  const idx = activeIndex(segments, positionMs)
  return idx == null ? null : segments[idx]!
}

export function activeInfo(segments: Segment[], positionMs: number): ActiveInfo | null {
  return PreparedTimings.prepare(segments).activeInfo(positionMs)
}

/** Index of the last segment whose start <= positionMs, or null when nothing should be lit. */
function activeIndex(segments: Segment[], positionMs: number): number | null {
  if (segments.length === 0) return null
  if (positionMs < segments[0]!.startMs) return null
  if (positionMs >= segments[segments.length - 1]!.endMs) return null

  let lo = 0
  let hi = segments.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >>> 1
    if (segments[mid]!.startMs <= positionMs) lo = mid
    else hi = mid - 1
  }
  return lo
}

export const HighlightEngine = {
  PreparedTimings,
  activeWord,
  activeSegment,
  activeInfo,
}
