/**
 * Reader-facing playback failure copy. Raw exception text, MediaError codes and
 * library names (Gapless-5, NotAllowedError…) never reach the page; callers log
 * the original to the console and show one of these calm sentences instead.
 * Wording matches the Android app.
 */
export const PLAYBACK_MESSAGES = {
  network: 'No connection · press play to try again',
  unavailable: "This verse isn't available right now",
  decode: "This verse couldn't be played",
  autoplay: 'Press play to begin',
  stalled: 'Still loading · press play to continue',
  generic: 'Recitation stopped · press play to try again',
} as const

export type PlaybackErrorKind = keyof typeof PLAYBACK_MESSAGES

// HTMLMediaElement MediaError codes (spelled out so this runs without a DOM).
const MEDIA_ERR_ABORTED = 1
const MEDIA_ERR_NETWORK = 2
const MEDIA_ERR_DECODE = 3
const MEDIA_ERR_SRC_NOT_SUPPORTED = 4

function isOffline(): boolean {
  return typeof navigator !== 'undefined' && navigator.onLine === false
}

function mediaErrorCode(value: unknown): number | null {
  if (!value || typeof value !== 'object') return null
  const code = (value as { code?: unknown }).code
  // MediaError has `code` + `message` and no `name`; DOMException has all three.
  if (typeof code !== 'number' || 'name' in value) return null
  return code >= MEDIA_ERR_ABORTED && code <= MEDIA_ERR_SRC_NOT_SUPPORTED ? code : null
}

/** Classify any thrown value, MediaError, or library error string. */
export function classifyPlaybackError(error: unknown): PlaybackErrorKind {
  const name = errorName(error)
  if (name === 'NotAllowedError') return 'autoplay'
  if (isOffline()) return 'network'

  const code = mediaErrorCode(error) ?? mediaErrorCode((error as { cause?: unknown } | null)?.cause)
  if (code === MEDIA_ERR_NETWORK) return 'network'
  if (code === MEDIA_ERR_DECODE) return 'decode'
  if (code === MEDIA_ERR_SRC_NOT_SUPPORTED) return 'unavailable'
  if (code === MEDIA_ERR_ABORTED) return 'generic'

  if (name === 'NotSupportedError') return 'unavailable'
  if (name === 'EncodingError') return 'decode'
  if (name === 'TimeoutError' || name === 'NetworkError') return 'network'

  const message = typeof error === 'string'
    ? error
    : error instanceof Error ? error.message : ''
  if (/decod/i.test(message)) return 'decode'
  if (/timeout|timed out/i.test(message)) return 'network'
  // Gapless-5 reports both HTTP 4xx/5xx and transport failure this way; we are
  // online here, so the file itself is the likelier culprit.
  if (/failed to load audio track/i.test(message)) return 'unavailable'
  if (error instanceof TypeError || /fetch|network|load failed/i.test(message)) return 'network'
  return 'generic'
}

export function playbackErrorMessage(error: unknown): string {
  return PLAYBACK_MESSAGES[classifyPlaybackError(error)]
}

/** Log the raw failure for debugging and return the reader-facing sentence. */
export function describePlaybackError(error: unknown, context: string): string {
  console.warn(`[playback] ${context}`, error)
  return playbackErrorMessage(error)
}

// DOMException is not an Error subclass in every runtime, so read `name`
// structurally rather than relying on instanceof.
function errorName(value: unknown): string {
  if (!value || typeof value !== 'object') return ''
  const name = (value as { name?: unknown }).name
  return typeof name === 'string' ? name : ''
}
