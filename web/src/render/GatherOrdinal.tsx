/**
 * The gather ordinal in the soak's top corner — Android `GatherOrdinalMark`:
 * a Western numeral held by a tight ink-brush circle, in the bookmark gutter.
 * Ink, not gold.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { brushCircleParams, brushMarkPath, type BrushBox } from '../ui/kit/brushMark'

const PARAMS = { ...brushCircleParams('baseline'), padX: 3, padY: 2, bow: 2 }

export function GatherOrdinal({ ordinal, side }: { ordinal: number; side: 'left' | 'right' }) {
  const digitRef = useRef<HTMLSpanElement>(null)
  const [box, setBox] = useState<BrushBox | null>(null)
  const [progress, setProgress] = useState(0)

  useLayoutEffect(() => {
    const el = digitRef.current
    if (!el) return
    setBox({ x: 14, y: 14, w: el.offsetWidth, h: el.offsetHeight })
  }, [ordinal])

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setProgress(1)
      return
    }
    setProgress(0)
    const start = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / PARAMS.paintMs)
      setProgress(1 - (1 - t) ** 3)
      if (t < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [ordinal])

  return (
    <span className="gather-ordinal" data-side={side} aria-label={`Selected, ${ordinal}`}>
      {box && progress > 0 ? (
        <svg
          className="gather-ordinal-brush"
          width={box.w + 28}
          height={box.h + 28}
          aria-hidden="true"
        >
          <path d={brushMarkPath(box, progress, PARAMS)} style={{ opacity: PARAMS.alpha }} />
        </svg>
      ) : null}
      <span ref={digitRef} className="gather-ordinal-digit">
        {ordinal}
      </span>
    </span>
  )
}
