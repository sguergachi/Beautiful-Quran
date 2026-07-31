/**
 * Route-based output-latency presets for the karaoke clock.
 * Port of Android `domain/OutputLatency.kt`.
 *
 * HighlightEngine stays pure: Bluetooth (and similar) delay is a property of
 * the playback path. The reader subtracts a small offset from media position
 * before HighlightClock / HighlightEngine see it.
 *
 * Web has no reliable “ms until the ear” API, so route classification stays
 * available for a future monitor; the reader currently uses LOCAL (0 ms) lag
 * and still applies word-ink highlight lead via [highlightMs].
 */

export enum OutputKind {
  LOCAL = 'LOCAL',
  BLUETOOTH_A2DP = 'BLUETOOTH_A2DP',
  BLUETOOTH_LE = 'BLUETOOTH_LE',
}

export enum OutputRoute {
  LOCAL = 'LOCAL',
  BLUETOOTH_A2DP = 'BLUETOOTH_A2DP',
  BLUETOOTH_LE = 'BLUETOOTH_LE',
}

export const LOCAL_MS = 0
/** Typical classic A2DP stack delay; devices vary ~100–300 ms. */
export const A2DP_MS = 180
/** LE Audio is usually lower-latency than classic A2DP. */
export const LE_MS = 80

/** Prefer A2DP over LE over local so a connected BT headset is not ignored. */
export function classify(kinds: ReadonlySet<OutputKind>): OutputRoute {
  if (kinds.has(OutputKind.BLUETOOTH_A2DP)) return OutputRoute.BLUETOOTH_A2DP
  if (kinds.has(OutputKind.BLUETOOTH_LE)) return OutputRoute.BLUETOOTH_LE
  return OutputRoute.LOCAL
}

export function latencyMsForRoute(route: OutputRoute): number {
  switch (route) {
    case OutputRoute.BLUETOOTH_A2DP:
      return A2DP_MS
    case OutputRoute.BLUETOOTH_LE:
      return LE_MS
    default:
      return LOCAL_MS
  }
}

export function latencyMs(kinds: ReadonlySet<OutputKind>): number {
  return latencyMsForRoute(classify(kinds))
}

/** Media-timeline position adjusted so the highlight tracks what is heard. */
export function heardMs(mediaPositionMs: number, latencyMsValue: number): number {
  return Math.max(0, Math.trunc(mediaPositionMs) - Math.max(0, Math.trunc(latencyMsValue)))
}

/**
 * Karaoke query time: heard playhead, then advance by [leadMs] so word ink can
 * run ahead of the segment table. Net form so lag and lead cancel cleanly.
 *
 * When [leadNotBeforeMs] is positive, encoded opening silence stays on the
 * heard clock; after the first word starts, lead ramps from 0 to [leadMs].
 */
export function highlightMs(
  mediaPositionMs: number,
  latencyMsValue: number,
  leadMs = 0,
  leadNotBeforeMs = 0,
): number {
  const heardPositionMs = heardMs(mediaPositionMs, latencyMsValue)
  const lead = Math.max(0, Math.trunc(leadMs))
  const gate = Math.max(0, Math.trunc(leadNotBeforeMs))
  if (gate <= 0) {
    return Math.max(
      0,
      Math.trunc(mediaPositionMs) - Math.max(0, Math.trunc(latencyMsValue)) + lead,
    )
  }
  if (heardPositionMs < gate) return heardPositionMs
  const pastGate = heardPositionMs - gate
  const appliedLead = Math.min(lead, pastGate)
  return Math.max(0, heardPositionMs + appliedLead)
}

export const OutputLatency = {
  OutputKind,
  Route: OutputRoute,
  LOCAL_MS,
  A2DP_MS,
  LE_MS,
  classify,
  latencyMsForRoute,
  latencyMs,
  heardMs,
  highlightMs,
}
