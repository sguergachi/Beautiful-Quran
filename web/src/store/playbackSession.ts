import type { PlayerState } from '../playback/player'

/**
 * Which chapter holds a listening session, for the cover's floating
 * transport (Android `shouldShowFloatingPlayback`: a verse is *loaded*).
 *
 * Android loads a playlist only when the reader asks to listen, so opening
 * a chapter never raises the transport. The web parks a quiet, paused
 * playlist on every chapter open so the first Play is instant; that parked
 * clip must not count. A session begins when playback starts (play intent:
 * playing or buffering toward play) and follows the recitation as it
 * advances through the chapter. A quiet open of another chapter changes
 * `nowPlaying` without starting anything, so the old session no longer
 * matches it and the transport stays down. Stop clears it.
 */
export function nextPlaybackSession(
  session: number | null,
  prev: PlayerState,
  next: PlayerState,
): number | null {
  const np = next.nowPlaying
  if (np == null) return null
  const active = next.isPlaying || next.isBuffering
  if (!active) return session
  const wasActive = prev.isPlaying || prev.isBuffering
  if (!wasActive) return np.surahId
  // Already listening: a new ayah of the same chapter is the recitation
  // moving on (including into a chapter auto-advanced to). A chapter swap
  // with no ayah step is a quiet open landing while the old clip pauses.
  const sameSurah = prev.nowPlaying?.surahId === np.surahId
  if (sameSurah && (prev.nowPlaying?.ayah !== np.ayah || session === np.surahId)) {
    return np.surahId
  }
  return session
}

/** True while [player]'s loaded verse belongs to a listening session. */
export function hasPlaybackSession(session: number | null, player: PlayerState): boolean {
  return player.nowPlaying != null && session === player.nowPlaying.surahId
}
