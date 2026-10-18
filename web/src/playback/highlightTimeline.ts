import { highlightFrame, type HighlightFrame, type HighlightSyncState } from '../domain/HighlightSync'

/** Shared frame authority. DOM masks read it without publishing React frame state. */
class HighlightTimeline {
  state: HighlightSyncState | null = null
  frame(nowMs = performance.now()): HighlightFrame | null {
    return this.state ? highlightFrame(this.state, nowMs * 1e6) : null
  }
  /** Capture the calibrated media window once, keeping visual minimum durations at entry speed. */
  window(startMs: number, wallDurationMs: number): MediaWashWindow | null {
    const state = this.state
    if (!state) return null
    return { item: state.sample.item, inkGeneration: state.sample.inkGeneration,
      startMs, durationMs: Math.max(1, wallDurationMs * state.sample.speed) }
  }
}
export interface MediaWashWindow {
  item: HighlightFrame['item']; inkGeneration: number; startMs: number; durationMs: number
}
export function mediaWashProgress(window: MediaWashWindow, frame: HighlightFrame | null): number {
  if (!frame || window.inkGeneration !== frame.inkGeneration || window.item.surah !== frame.item.surah ||
    window.item.ayah !== frame.item.ayah || window.item.reciter !== frame.item.reciter) return 1
  return Math.max(0, Math.min(1, (frame.inkMs - window.startMs) / window.durationMs))
}
export const highlightTimeline = new HighlightTimeline()
