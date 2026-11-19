/** Labels for Settings → Playback (Android `PlaybackScreen.kt`). */

export function verseGapLabel(seconds: number): string {
  return seconds === 0 ? 'None' : `${seconds} s`
}

/** The note under Settings' Playback row. */
export function playbackSummary(verseGapSeconds: number): string {
  return verseGapSeconds > 0
    ? `${verseGapSeconds} s between verses`
    : 'Verses follow one another'
}
