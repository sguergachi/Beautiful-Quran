import { useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'
import {
  NuqtaParamsContext,
  nuqtaDropPath,
  nuqtaDropRadii,
  nuqtaFingers,
  nuqtaInkStops,
  nuqtaPath,
  NUQTA_INK_STOP_COUNT,
} from './nuqta'
import { paperSelectHaptic } from './paperHaptics'

export type PaperChoiceOption<T extends string = string> = {
  value: T
  label: string
  /** Quieter second line (e.g. “No word highlighting”). */
  description?: string
  /** Optional trailing ornament (theme swatches, etc.). */
  trailing?: ReactNode
}

type Props<T extends string> = {
  'aria-label': string
  value: T
  options: PaperChoiceOption<T>[]
  onChange: (value: T) => void
}

/**
 * Vertical ink choice list — Android `SelectRow` parity. A calligraphic nuqta
 * leads each row; selection is carried by ink strength (alpha), not accent color on
 * the label. No floating popup, no Material radio chrome.
 */
export function PaperChoiceList<T extends string>({
  'aria-label': ariaLabel,
  value,
  options,
  onChange,
}: Props<T>) {
  return (
    <RadioGroup
      className="paper-choice-list"
      aria-label={ariaLabel}
      value={value}
      onValueChange={(next) => {
        if (next == null || next === value) return
        paperSelectHaptic()
        onChange(next as T)
      }}
    >
      {options.map((opt) => {
        const selected = opt.value === value
        return (
          <label
            key={opt.value}
            className={`paper-choice-row${selected ? ' is-selected' : ''}`}
          >
            <Radio.Root value={opt.value} className="paper-choice-radio ink-nuqta">
              <InkNuqta selected={selected} />
            </Radio.Root>
            <span className="paper-choice-copy">
              <span className="paper-choice-label">{opt.label}</span>
              {opt.description ? (
                <span className="paper-choice-desc">{opt.description}</span>
              ) : null}
            </span>
            {opt.trailing ? (
              <span className="paper-choice-trailing">{opt.trailing}</span>
            ) : null}
          </label>
        )
      })}
    </RadioGroup>
  )
}

/**
 * Android `InkNuqta`: a qalam-cut Arabic dot that one drop of ink soaks into.
 * The drop lands off centre, runs out along uneven fibres, deepens from pale
 * to dense as it soaks in, and wears a feathered wet fringe; unchecking lifts
 * it whole. The same drop model as Android (kit/nuqta.ts), driven per frame.
 */
function InkNuqta({ selected }: { selected: boolean }) {
  const ids = useId()
  const p = useContext(NuqtaParamsContext)
  const fingers = useMemo(() => nuqtaFingers(p.seed), [p.seed])
  const [frame, setFrame] = useState(() => ({ t: selected ? 1 : 0, lift: selected ? 1 : 0 }))
  const live = useRef(frame)
  const params = useRef(p)
  params.current = p

  useEffect(() => {
    const from = live.current
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    const q = params.current
    const set = (next: { t: number; lift: number }) => {
      live.current = next
      setFrame(next)
    }
    if (selected ? from.lift === 1 && from.t === 1 : from.lift === 0) return
    if (reduce) {
      set(selected ? { t: 1, lift: 1 } : { t: from.t, lift: 0 })
      return
    }
    // Re-chosen mid-lift: a fresh drop rather than reviving the half-dried one.
    const t0 = selected ? (from.lift < 1 ? 0 : from.t) : from.t
    const lift0 = from.lift
    const duration = selected ? q.spreadMs * (1 - t0) : q.liftMs * lift0
    const ease = cubicBezier(q.liftX1, q.liftY1, q.liftX2, q.liftY2)
    const began = performance.now()
    let raf = 0
    const step = (now: number) => {
      const k = duration > 0 ? Math.min((now - began) / duration, 1) : 1
      set(
        selected
          ? { t: t0 + (1 - t0) * k, lift: 1 }
          : { t: t0, lift: lift0 * (1 - ease(k)) },
      )
      if (k < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [selected])

  // Everything is laid out in a 20-unit box; dp knobs scale into it.
  const unit = 20 / p.sizeDp
  const d = nuqtaPath(20, p.bow)
  const { t, lift } = frame
  const inked = t > 0 && lift > 0
  const ox = 10 + p.originDx * unit
  const oy = 10 + p.originDy * unit
  const full = 14 * p.reach
  const radii = inked ? nuqtaDropRadii(t, p, fingers) : null
  const edge = radii ? Math.max(...radii) : 0
  const drop = radii ? nuqtaDropPath(ox, oy, full, radii) : ''
  const stops = inked ? nuqtaInkStops(t, p, edge) : []
  const stroke = p.strokeDp * unit
  const clip = `${ids}-clip`
  const grad = `${ids}-ink`
  const inside = `${ids}-in`
  const stain = `${ids}-stain`
  const stainStrength = p.selectedOutlineAlpha / Math.max(p.inkAlpha, 0.05)
  const outside = `${ids}-out`
  return (
    <svg
      className="ink-nuqta-mark"
      viewBox="0 0 20 20"
      aria-hidden="true"
      style={{ width: `${p.sizeDp / 16}rem`, height: `${p.sizeDp / 16}rem` }}
    >
      {inked ? (
        <>
          <defs>
            <clipPath id={clip}>
              <path d={d} />
            </clipPath>
            <clipPath id={inside}>
              <path d={drop} />
            </clipPath>
            {/* The outline keeps its resting hairline wherever the ink has not reached. */}
            <mask id={outside} maskUnits="userSpaceOnUse" x={-2} y={-2} width={24} height={24}>
              <rect x={-2} y={-2} width={24} height={24} fill="white" />
              <path d={drop} fill="black" />
            </mask>
            {/* One density field paints the ink and the outline it stains. */}
            {[grad, stain].map((id) => (
              <radialGradient
                key={id}
                id={id}
                gradientUnits="userSpaceOnUse"
                cx={ox}
                cy={oy}
                r={Math.max(full * edge, 0.05)}
              >
                {Array.from({ length: NUQTA_INK_STOP_COUNT }, (_, k) => (
                  <stop
                    key={k}
                    offset={stops[k * 2]}
                    className="ink-nuqta-stop"
                    stopOpacity={Math.min(
                      lift * stops[k * 2 + 1] * (id === stain ? stainStrength : 1),
                      1,
                    )}
                  />
                ))}
              </radialGradient>
            ))}
          </defs>
          <path clipPath={`url(#${clip})`} fill={`url(#${grad})`} d={drop} />
          <path
            className="ink-nuqta-outline"
            mask={`url(#${outside})`}
            d={d}
            strokeWidth={stroke}
            strokeOpacity={p.restingOutlineAlpha * 0.65}
          />
          <g clipPath={`url(#${inside})`}>
            <path
              className="ink-nuqta-outline"
              d={d}
              strokeWidth={stroke}
              strokeOpacity={p.restingOutlineAlpha * 0.65 * (1 - lift)}
            />
            <path
              className="ink-nuqta-outline is-wet"
              d={d}
              strokeWidth={stroke}
              stroke={`url(#${stain})`}
            />
          </g>
        </>
      ) : (
        <path
          className="ink-nuqta-outline"
          d={d}
          strokeWidth={stroke}
          strokeOpacity={p.restingOutlineAlpha * 0.65}
        />
      )}
    </svg>
  )
}

/** A CSS `cubic-bezier()` as a function of progress, for the lift. */
function cubicBezier(x1: number, y1: number, x2: number, y2: number) {
  const at = (a: number, b: number, s: number) =>
    3 * a * s * (1 - s) ** 2 + 3 * b * s * s * (1 - s) + s ** 3
  return (x: number) => {
    if (x <= 0 || x >= 1) return x
    let lo = 0
    let hi = 1
    for (let i = 0; i < 24; i++) {
      const mid = (lo + hi) / 2
      if (at(x1, x2, mid) < x) lo = mid
      else hi = mid
    }
    return at(y1, y2, (lo + hi) / 2)
  }
}
