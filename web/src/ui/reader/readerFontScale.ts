/**
 * Pinch-to-zoom reader text — Android `ReaderFontScale.kt` + `pinchFontScale`.
 * One-finger scroll and taps stay with the page; two fingers step the text
 * size through the Customize stops, with a quiet deadband round each one.
 */
import { FONT_SCALE_MAX, FONT_SCALE_MIN, FONT_SCALE_STEP, nudgeFontScale } from '../../data/settings'

const PINCH_SCALE_THRESHOLD = FONT_SCALE_STEP * 0.6

export function pinchFontScale(openingScale: number, zoom: number, acceptedScale: number): number {
  if (!Number.isFinite(zoom) || zoom <= 0) return acceptedScale
  const pinched = openingScale * zoom
  let next = acceptedScale
  while (pinched >= next + PINCH_SCALE_THRESHOLD && next < FONT_SCALE_MAX - 1e-6) next = nudgeFontScale(next, 1)
  while (pinched <= next - PINCH_SCALE_THRESHOLD && next > FONT_SCALE_MIN + 1e-6) next = nudgeFontScale(next, -1)
  return next
}

/**
 * Watches [el] for a two-finger pinch. [onScale] receives each newly
 * accepted stop; the page's own scroll is held while two fingers are down.
 */
export function watchReaderPinch(
  el: HTMLElement,
  currentScale: () => number,
  onScale: (scale: number) => void,
): () => void {
  let openingSpan = 0
  let openingScale = 1
  let accepted = 1
  let pinching = false
  const span = (touches: TouchList) =>
    Math.hypot(touches[0]!.clientX - touches[1]!.clientX, touches[0]!.clientY - touches[1]!.clientY)
  const onStart = (event: TouchEvent) => {
    if (event.touches.length < 2) return
    openingSpan = span(event.touches)
    openingScale = currentScale()
    accepted = openingScale
    pinching = true
  }
  const onMove = (event: TouchEvent) => {
    if (!pinching || event.touches.length < 2) return
    event.preventDefault()
    if (openingSpan <= 0) return
    const scale = pinchFontScale(openingScale, span(event.touches) / openingSpan, accepted)
    if (scale !== accepted) {
      accepted = scale
      onScale(scale)
    }
  }
  const onEnd = (event: TouchEvent) => {
    if (event.touches.length < 2) pinching = false
  }
  el.addEventListener('touchstart', onStart, { passive: true })
  el.addEventListener('touchmove', onMove, { passive: false })
  el.addEventListener('touchend', onEnd, { passive: true })
  el.addEventListener('touchcancel', onEnd, { passive: true })
  return () => {
    el.removeEventListener('touchstart', onStart)
    el.removeEventListener('touchmove', onMove)
    el.removeEventListener('touchend', onEnd)
    el.removeEventListener('touchcancel', onEnd)
  }
}
