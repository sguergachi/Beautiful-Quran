import type { PlayerState } from '../../playback/player'

/** Android `HomeViewModel.cycleRepeatMode`: off → one → all → off. The
 * cover has no range sheet, so a range on the way round falls back to off. */
export function nextRepeatMode(mode: PlayerState['repeatMode']): PlayerState['repeatMode'] {
  switch (mode) {
    case 'off':
      return 'ayah'
    case 'ayah':
      return 'surah'
    default:
      return 'off'
  }
}

/** Android `HomeViewModel.cycleSpeed`: 0.75 → 1 → 1.25 → 1.5 → 0.75. */
export const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5] as const

export function nextSpeed(current: number): number {
  const at = PLAYBACK_SPEEDS.findIndex((speed) => Math.abs(speed - current) < 0.01)
  return PLAYBACK_SPEEDS[(at + 1) % PLAYBACK_SPEEDS.length]!
}

/** "1×", "1.25×" — whole speeds drop the decimal, as Android prints them. */
export function speedLabel(speed: number): string {
  return `${Number.isInteger(speed) ? speed.toFixed(0) : String(speed)}×`
}
