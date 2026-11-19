/**
 * Gapless-5 Web Audio `playbackRate` always shifts pitch. Prefer natural pitch
 * via HTMLMediaElement at non-1× speeds over seamless verse joins.
 *
 * A pause between verses has no seamless join to protect, and Gapless-5 walks
 * on into the next clip by itself: the media-element path holds the silence.
 */
export function wantsGapless5Transport(
  preferred: boolean,
  speed: number,
  verseGapMs = 0,
): boolean {
  return preferred && speed === 1 && verseGapMs <= 0
}
