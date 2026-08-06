import { useCallback, useRef } from 'react'
import {
  FONT_SCALE_MAX,
  FONT_SCALE_MIN,
  FONT_SCALE_STEPS,
  nudgeFontScale,
} from '../../data/settings'

type Props = {
  scale: number
  onChange: (scale: number) => void
}

/**
 * Text size as ink, not a Material slider — an "A" at each size flanks a thin
 * paper track with a green dot; tap or drag the track, or tap the small/large
 * "A" to nudge one stop (Android `TextSizeControl` parity).
 */
export function FontSizeControl({ scale, onChange }: Props) {
  const trackRef = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)

  const setFromClientX = useCallback(
    (clientX: number) => {
      const el = trackRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const f = Math.min(1, Math.max(0, (clientX - rect.left) / Math.max(1, rect.width)))
      const stop = Math.round(f * FONT_SCALE_STEPS)
      onChange(FONT_SCALE_MIN + (stop / FONT_SCALE_STEPS) * (FONT_SCALE_MAX - FONT_SCALE_MIN))
    },
    [onChange],
  )

  const fraction = Math.min(
    1,
    Math.max(0, (scale - FONT_SCALE_MIN) / (FONT_SCALE_MAX - FONT_SCALE_MIN)),
  )

  return (
    <div
      className="font-size-control"
      role="group"
      aria-label="Text size"
    >
      <button
        type="button"
        className="font-size-glyph font-size-glyph-sm"
        aria-label="Decrease text size"
        onClick={() => onChange(nudgeFontScale(scale, -1))}
      >
        A
      </button>
      <div
        ref={trackRef}
        className="font-size-track"
        role="slider"
        aria-label="Text size"
        aria-valuemin={FONT_SCALE_MIN}
        aria-valuemax={FONT_SCALE_MAX}
        aria-valuenow={scale}
        aria-valuetext={`${Math.round(scale * 100)}%`}
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
            e.preventDefault()
            onChange(nudgeFontScale(scale, +1))
          } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
            e.preventDefault()
            onChange(nudgeFontScale(scale, -1))
          } else if (e.key === 'Home') {
            e.preventDefault()
            onChange(FONT_SCALE_MIN)
          } else if (e.key === 'End') {
            e.preventDefault()
            onChange(FONT_SCALE_MAX)
          }
        }}
        onPointerDown={(e) => {
          dragging.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          setFromClientX(e.clientX)
        }}
        onPointerMove={(e) => {
          if (!dragging.current) return
          setFromClientX(e.clientX)
        }}
        onPointerUp={(e) => {
          dragging.current = false
          try {
            e.currentTarget.releasePointerCapture(e.pointerId)
          } catch {
            /* already released */
          }
        }}
        onPointerCancel={() => {
          dragging.current = false
        }}
      >
        <span className="font-size-rail" aria-hidden="true" />
        <span
          className="font-size-dot"
          aria-hidden="true"
          style={{ left: `${fraction * 100}%` }}
        />
      </div>
      <button
        type="button"
        className="font-size-glyph font-size-glyph-lg"
        aria-label="Increase text size"
        onClick={() => onChange(nudgeFontScale(scale, +1))}
      >
        A
      </button>
    </div>
  )
}
