/**
 * Hold-to-pin ticks (Android `holdToPin`: CLOCK_TICK as the hold takes,
 * CONFIRM when it is full). The Vibration API exists mostly on Android
 * browsers; elsewhere this is silent.
 */
export function holdHaptic(kind: 'engaged' | 'confirmed'): void {
  try {
    if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return
    navigator.vibrate(kind === 'engaged' ? 6 : [12, 40, 18])
  } catch {
    // Blocked or unsupported: the fill still answers the hold.
  }
}
