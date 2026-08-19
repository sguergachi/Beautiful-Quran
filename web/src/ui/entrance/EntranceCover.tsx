/**
 * Entrance ceremony — the closed mushaf over the paper stack.
 * Also the cold-start loading screen: the cover is up while quran.db loads,
 * with progress inked onto the leather; arrive → du'a text fade → open once
 * the book is ready.
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { animate, type AnimationPlaybackControls } from 'motion'
import { washMaskImage } from '../theme/Fade'
import { coverLayout, coverLayoutCssVars } from './coverLayout'
import { generateCoverOrnament, type CoverOrnament, type RosetteSpec } from '../theme/ornamentGenerator'
import {
  fieldWeaveBackground,
  GeneratedBorder,
  GeneratedRosette,
  useOrnamentBuilt,
} from '../theme/GeneratedOrnament'

const ISTIADHA_ARABIC = 'أَعُوذُ بِٱللَّهِ مِنَ ٱلشَّيْطَٰنِ ٱلرَّجِيمِ'
const ISTIADHA_ENGLISH = 'I seek refuge in Allah from Shaytan, the accursed'

const SHEET_FADE_MS = 550
const TITLE_WASH_MS = 1_500
const ARRIVAL_HOLD_MS = 300
const DUA_WASH_MS = 2_400
const DUA_HOLD_MS = 900
const OPEN_MS = 1_150

type Phase = 'loading' | 'arriving' | 'dua' | 'opening'

export interface EntranceCoverProps {
  /** True once quran.db is open and the chapter list can be shown. */
  ready: boolean
  /** Status line while the book loads (empty when ready). */
  loadLabel: string
  /** 0..1 while DB bytes stream; null for indeterminate prepare phases. */
  loadProgress: number | null
  /** Boot failure — shown on the cover with a retry control. */
  error: string | null
  onRetry?: () => void
  onFinished: () => void
}

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const t = window.setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      window.clearTimeout(t)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

function applyWash(
  el: HTMLElement | null,
  progress: number,
  restingAlpha: number,
) {
  if (!el) return
  if (progress >= 1) {
    el.style.maskImage = 'none'
    el.style.webkitMaskImage = 'none'
    el.style.opacity = '1'
    return
  }
  const mask = washMaskImage(progress, restingAlpha, true)
  el.style.maskImage = mask
  el.style.webkitMaskImage = mask
  el.style.opacity = '1'
}

/** Drive a letter wash from 0→1 over [durationMs] via Motion. */
async function runWash(
  durationMs: number,
  paint: (progress: number) => void,
  signal: AbortSignal,
): Promise<void> {
  if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
  paint(0)
  let controls: AnimationPlaybackControls | null = null
  await new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      controls?.stop()
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
    controls = animate(0, 1, {
      duration: Math.max(0.001, durationMs / 1000),
      ease: 'linear',
      onUpdate: (p) => paint(p),
      onComplete: () => {
        signal.removeEventListener('abort', onAbort)
        paint(1)
        resolve()
      },
    })
  })
}

/**
 * Doubled gilt rule + generated corner seals — sizes from the board layout
 * grid. The seals are the hubs the border band's channels taper onto, part
 * of the tooled binding rather than the ink wash, so they render complete
 * from the first frame (matches Android's static `GeneratedCornerSeals`).
 */
function MushafCoverFrame({ seal }: { seal: RosetteSpec }) {
  const corner = (pos: string) => (
    <GeneratedRosette
      spec={seal}
      built
      animated={false}
      className={`entrance-corner entrance-corner--${pos}`}
      ruleWidth={4.6}
      hairWidth={4.6}
    />
  )
  return (
    <div className="entrance-frame" aria-hidden="true">
      <div className="entrance-frame-outer" />
      <div className="entrance-frame-inner" />
      {corner('tl')}
      {corner('tr')}
      {corner('bl')}
      {corner('br')}
    </div>
  )
}

/**
 * Cold-start closed mushaf — also the loading screen. The cover is up from
 * the first paint; load progress inks onto the leather; the du'a text wash
 * and hinge open wait until the book is ready. Tap / Escape opens once ready.
 */
export function EntranceCover({
  ready,
  loadLabel,
  loadProgress,
  error,
  onRetry,
  onFinished,
}: EntranceCoverProps) {
  const [phase, setPhase] = useState<Phase>('loading')
  const [sheetAlpha, setSheetAlpha] = useState(0)
  const [opening, setOpening] = useState(false)
  const [captionOn, setCaptionOn] = useState(false)
  const [arrivalDone, setArrivalDone] = useState(false)
  const [board, setBoard] = useState(() => ({ w: 390, h: 844, layout: coverLayout(390, 844) }))
  const layoutVars = useMemo(() => coverLayoutCssVars(board.layout), [board.layout])
  // A fresh ornament every visit — the generating machine's whole point.
  const ornament: CoverOrnament = useMemo(
    () => generateCoverOrnament((Math.random() * 0x7fffffff) | 0),
    [],
  )
  const weave = useMemo(() => fieldWeaveBackground(ornament.field), [ornament])
  // Flips one frame after mount; starts every stroke's dash-reveal clock.
  const built = useOrnamentBuilt()

  const boardRef = useRef<HTMLDivElement>(null)
  const titleArRef = useRef<HTMLParagraphElement>(null)
  const titleEnRef = useRef<HTMLParagraphElement>(null)
  const duaRef = useRef<HTMLParagraphElement>(null)
  const momentAcRef = useRef<AbortController | null>(null)
  const finishedRef = useRef(false)
  const ceremonyStartedRef = useRef(false)
  const onFinishedRef = useRef(onFinished)
  onFinishedRef.current = onFinished

  const canOpen = ready && !error
  const showLoading = !ready && !error
  const showProgress =
    showLoading && loadProgress != null && loadProgress >= 0 && loadProgress < 1

  // Modular cover grid from the live board size (width × height).
  useLayoutEffect(() => {
    const el = boardRef.current
    if (!el) return
    const apply = (w: number, h: number) => {
      if (w < 2 || h < 2) return
      setBoard({ w, h, layout: coverLayout(w, h) })
    }
    apply(el.clientWidth, el.clientHeight)
    const ro = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect
      if (box) apply(box.width, box.height)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const skipToOpening = useCallback(() => {
    if (!canOpen || phase === 'opening') return
    setPhase('opening')
    setCaptionOn(true)
    momentAcRef.current?.abort()
  }, [canOpen, phase])

  useEffect(() => {
    const prevTheme = document.querySelector('meta[name="theme-color"]')?.getAttribute('content')
    const meta = document.querySelector('meta[name="theme-color"]')
    meta?.setAttribute('content', '#031C15')
    document.documentElement.dataset.entrance = 'true'
    return () => {
      document.documentElement.removeAttribute('data-entrance')
      if (meta && prevTheme) meta.setAttribute('content', prevTheme)
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        skipToOpening()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [skipToOpening])

  // Arrival — fade + title wash once, while the book may still be loading.
  useEffect(() => {
    const ac = new AbortController()
    const { signal } = ac
    ;(async () => {
      try {
        setSheetAlpha(1)
        setPhase('arriving')
        await wait(SHEET_FADE_MS, signal)
        await runWash(
          TITLE_WASH_MS,
          (p) => {
            applyWash(titleArRef.current, p, 0)
            applyWash(titleEnRef.current, p, 0)
          },
          signal,
        )
        await wait(ARRIVAL_HOLD_MS, signal)
        if (!signal.aborted) setArrivalDone(true)
      } catch {
        /* unmount */
      }
    })()
    return () => ac.abort()
  }, [])

  // Once arrival is done and the book is ready, fade the du'a then open.
  // Skip aborts the in-flight moment without replaying the title.
  useEffect(() => {
    if (!arrivalDone || !canOpen || ceremonyStartedRef.current || finishedRef.current) {
      return
    }
    ceremonyStartedRef.current = true

    const momentAc = new AbortController()
    momentAcRef.current = momentAc
    const openAc = new AbortController()
    const { signal } = momentAc

    ;(async () => {
      try {
        setPhase('dua')
        setCaptionOn(true)
        await runWash(
          DUA_WASH_MS,
          (p) => applyWash(duaRef.current, p, 0.12),
          signal,
        )
        await wait(DUA_HOLD_MS, signal)
      } catch {
        /* skip / unmount — fall through to open */
      }

      if (finishedRef.current || openAc.signal.aborted) return

      applyWash(titleArRef.current, 1, 0)
      applyWash(titleEnRef.current, 1, 0)
      applyWash(duaRef.current, 1, 0.12)
      setSheetAlpha(1)
      setCaptionOn(true)
      setPhase('opening')
      setOpening(true)
      const openMs = window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 0
        : OPEN_MS
      try {
        await wait(openMs, openAc.signal)
      } catch {
        return
      }
      if (!finishedRef.current && !openAc.signal.aborted) {
        finishedRef.current = true
        onFinishedRef.current()
      }
    })()

    return () => {
      momentAc.abort()
      openAc.abort()
      momentAcRef.current = null
    }
  }, [arrivalDone, canOpen])

  const progressPct =
    loadProgress != null ? Math.round(Math.min(1, Math.max(0, loadProgress)) * 100) : null

  return (
    <div
      className="entrance"
      role="dialog"
      aria-modal="true"
      aria-label={error ? 'Failed to open the book' : 'The Noble Quran'}
      aria-busy={showLoading || undefined}
    >
      <div
        ref={boardRef}
        className={`entrance-board${opening ? ' entrance-board--opening' : ''}`}
        style={{
          ...layoutVars,
          opacity: opening ? undefined : sheetAlpha || 1,
        }}
        role={canOpen && phase !== 'opening' ? 'button' : undefined}
        tabIndex={canOpen && phase !== 'opening' ? 0 : undefined}
        onClick={canOpen && phase !== 'opening' ? skipToOpening : undefined}
        onKeyDown={
          canOpen && phase !== 'opening'
            ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  skipToOpening()
                }
              }
            : undefined
        }
        aria-label={
          error
            ? 'Failed to open the book'
            : showLoading
              ? loadLabel || 'Opening the book…'
              : 'The Noble Quran — touch to open'
        }
      >
        <div className="entrance-leather" aria-hidden="true" />
        <div
          className={`entrance-weave${built ? ' entrance-weave--on' : ''}`}
          style={weave}
          aria-hidden="true"
        />
        <GeneratedBorder
          border={ornament.border}
          sealTip={ornament.cornerSeal.tipRadius}
          layout={board.layout}
          width={board.w}
          height={board.h}
        />
        <MushafCoverFrame seal={ornament.cornerSeal} />
        <div className="entrance-content">
          <div className="entrance-air entrance-air--top" />
          <GeneratedRosette
            spec={ornament.medallion}
            built={built}
            className="entrance-medallion"
          />
          <div className="entrance-titles">
            <p ref={titleArRef} className="entrance-title-ar" lang="ar" dir="rtl">
              القرآن الكريم
            </p>
            <p ref={titleEnRef} className="entrance-title-en">
              The Noble Quran
            </p>
          </div>
          <div className="entrance-air entrance-air--mid" />
          {error ? (
            <div className="entrance-load">
              <p className="entrance-load-label">{error}</p>
              {onRetry && (
                <button type="button" className="entrance-load-retry" onClick={onRetry}>
                  Try again
                </button>
              )}
            </div>
          ) : showLoading ? (
            <div className="entrance-load" aria-live="polite">
              <p className="entrance-load-label">
                {loadLabel || 'Opening the book…'}
              </p>
              <div
                className={`entrance-load-track${showProgress ? '' : ' entrance-load-track--pulse'}`}
                aria-hidden="true"
              >
                <div
                  className="entrance-load-fill"
                  style={showProgress ? { width: `${progressPct}%` } : undefined}
                />
              </div>
            </div>
          ) : (
            <div className="entrance-dua" style={{ opacity: captionOn ? 1 : 0 }}>
              <p ref={duaRef} className="entrance-dua-ar" lang="ar" dir="rtl">
                {ISTIADHA_ARABIC}
              </p>
              <p className="entrance-dua-en">{ISTIADHA_ENGLISH}</p>
            </div>
          )}
          <div className="entrance-air entrance-air--bot" />
        </div>
      </div>
    </div>
  )
}
