/**
 * Repeat — Android `RepeatSheet`: ink spreads from the play bar's repeat
 * button into a sheet of paper (no scrim); a muted "Repeat" label, the
 * circled choices, wheels that write themselves out as "X to Y" or a
 * distance dial captioned "ayahs", and "Done". The margins dismiss.
 */
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import type { RepeatMode, RepeatRange } from '../../playback/playlistNext'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { paperSelectHaptic } from '../kit/paperHaptics'
import {
  chooseRepeat,
  REPEAT_CHOICES,
  repeatDraft,
  repeatSelection,
  type RepeatChoice,
} from './repeatPolicy'
import './repeatSheet.css'

const ITEM = 42
const WHEEL_HEIGHT = 168
const SPREAD_MS = 420

/** A vertical number wheel (Android SearchDialWheel): snap, fade, tick. */
function NumberWheel({
  count,
  value,
  label,
  onChange,
  width,
}: {
  count: number
  value: number
  label: string
  onChange: (value: number) => void
  width: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const settled = useRef(value)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    if (Math.round(el.scrollTop / ITEM) + 1 !== value) el.scrollTop = (value - 1) * ITEM
    settled.current = value
  }, [value])
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let raf = 0
    const read = () => {
      raf = 0
      const next = Math.min(count, Math.max(1, Math.round(el.scrollTop / ITEM) + 1))
      if (next !== settled.current) {
        settled.current = next
        paperSelectHaptic()
        onChange(next)
      }
    }
    const onScroll = () => {
      if (!raf) raf = requestAnimationFrame(read)
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      if (raf) cancelAnimationFrame(raf)
      el.removeEventListener('scroll', onScroll)
    }
  }, [count, onChange])
  const step = (delta: number) => {
    const next = Math.min(count, Math.max(1, value + delta))
    ref.current?.scrollTo({ top: (next - 1) * ITEM, behavior: 'smooth' })
  }
  return (
    <div
      ref={ref}
      className="repeat-wheel"
      style={{ width, height: WHEEL_HEIGHT, paddingBlock: (WHEEL_HEIGHT - ITEM) / 2 }}
      role="spinbutton"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={1}
      aria-valuemax={count}
      aria-valuenow={value}
      onKeyDown={(event: KeyboardEvent) => {
        if (event.key === 'ArrowUp') step(-1)
        else if (event.key === 'ArrowDown') step(1)
        else if (event.key === 'PageUp') step(-5)
        else if (event.key === 'PageDown') step(5)
        else return
        event.preventDefault()
      }}
    >
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="repeat-wheel-item" data-selected={i + 1 === value || undefined}>
          {i + 1}
        </div>
      ))}
    </div>
  )
}

export function RepeatSheet({
  currentAyah,
  ayahCount,
  mode,
  range,
  retainedChoice,
  liveAyah,
  onApply,
  onDismiss,
}: {
  currentAyah: number
  ayahCount: number
  /** Kept for the host's call; the sheet no longer subtitles the chapter. */
  chapter?: string
  mode: RepeatMode
  range: RepeatRange | null
  retainedChoice: RepeatChoice | null
  /** The ayah playing now — "This ayah" loops it at Done (Android). */
  liveAyah?: () => number | null
  onApply: (mode: RepeatMode, range: RepeatRange | null, choice: RepeatChoice) => void
  onDismiss: () => void
}) {
  // Freeze the opening ayah: ongoing playback must not move an editing range.
  const [anchor] = useState(() => Math.max(1, Math.min(ayahCount, currentAyah)))
  const [draft, setDraft] = useState(() => repeatDraft(mode, range, anchor, ayahCount, retainedChoice))
  const rootRef = useRef<HTMLDivElement>(null)
  const [origin, setOrigin] = useState<{ x: number; y: number; r: number } | null>(null)
  const [closing, setClosing] = useState(false)
  const pending = useRef<null | (() => void)>(null)

  useLayoutEffect(() => {
    const root = rootRef.current
    if (!root) return
    const box = root.getBoundingClientRect()
    const button = document.querySelector<HTMLElement>('.repeat-control')?.getBoundingClientRect()
    const x = button ? button.left + button.width / 2 - box.left : box.width / 2
    const y = button ? button.top + button.height / 2 - box.top : box.height
    const r = Math.hypot(Math.max(x, box.width - x), Math.max(y, box.height - y)) + 24
    setOrigin({ x, y, r })
    root.querySelector<HTMLElement>('[role="radio"], button')?.focus({ preventScroll: true })
  }, [])

  const close = (after: () => void) => {
    if (closing) return
    pending.current = after
    setClosing(true)
    window.setTimeout(() => pending.current?.(), SPREAD_MS * 0.8)
  }
  const dismiss = () => close(onDismiss)
  const commit = () => {
    const live = liveAyah?.()
    const at = draft.choice === 'ayah' && live != null && live >= 1 ? Math.min(ayahCount, live) : anchor
    const selected = repeatSelection(draft, at, ayahCount)
    close(() => onApply(selected.mode, selected.range, draft.choice))
  }

  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      dismiss()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  const maxCount = Math.max(1, ayahCount - anchor + 1)
  const count = Math.min(draft.count, maxCount)
  const last = anchor + count - 1
  return (
    <div
      ref={rootRef}
      className="repeat-spread"
      data-closing={closing || undefined}
      style={
        origin
          ? {
              ['--spread-x' as string]: `${origin.x}px`,
              ['--spread-y' as string]: `${origin.y}px`,
              ['--spread-r' as string]: `${origin.r}px`,
            }
          : { visibility: 'hidden' }
      }
      role="group"
      aria-label="Repeat"
      onClick={(event) => {
        if (event.target === event.currentTarget) dismiss()
      }}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <div className="repeat-column" onClick={(event) => event.stopPropagation()}>
        <p className="repeat-label">Repeat</p>
        <PaperChoiceList
          aria-label="Repeat mode"
          value={draft.choice}
          options={[...REPEAT_CHOICES]}
          onChange={(choice) => setDraft((previous) => chooseRepeat(previous, choice, anchor, ayahCount))}
        />
        {draft.choice === 'range' ? (
          <div className="repeat-wheels" role="group" aria-label="Ayah range">
            <NumberWheel
              count={ayahCount}
              value={draft.from}
              width={110}
              label="From ayah"
              onChange={(from) => setDraft((d) => ({ ...d, from, to: Math.max(from, d.to) }))}
            />
            <span className="repeat-joint" aria-hidden="true">to</span>
            <NumberWheel
              count={ayahCount}
              value={draft.to}
              width={110}
              label="To ayah"
              onChange={(to) => setDraft((d) => ({ ...d, to, from: Math.min(to, d.from) }))}
            />
          </div>
        ) : draft.choice === 'from_here' ? (
          <div className="repeat-from-here">
            <div className="repeat-from-here-caption">ayahs</div>
            <div className="repeat-from-here-row">
              <span className="repeat-range-label" aria-live="polite">
                <span className="repeat-figure">{anchor}</span>
                {last !== anchor ? (
                  <>
                    <span className="repeat-joint">{' to '}</span>
                    <span className="repeat-figure">{last}</span>
                  </>
                ) : null}
              </span>
              <NumberWheel
                count={maxCount}
                value={count}
                width={68}
                label="Number of ayahs"
                onChange={(next) => setDraft((d) => ({ ...d, count: next }))}
              />
            </div>
          </div>
        ) : null}
        <button type="button" className="repeat-done-line" onClick={commit}>
          Done
        </button>
      </div>
    </div>
  )
}
