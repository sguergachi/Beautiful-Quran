import { useCallback, useEffect, useRef, type RefObject } from 'react'
import { animate, type AnimationPlaybackControls } from 'motion'
import { appStore } from '../../store/appStore'
import {
  EMPTY_PULL,
  nextChapterPullOffset,
  nextInviteAlpha,
  pillInkMix,
  previousChapterPullOffset,
  PREVIOUS_ENTER_MS,
  PREVIOUS_ENTER_PX,
  PREVIOUS_EXIT_MS,
  PREVIOUS_EXIT_PX,
  settlePull,
  stepPull,
  type PullState,
} from './chapterPull'

/** Android `chapterAdvanceEasing` CubicBezierEasing(0.22, 1, 0.36, 1). */
const CHAPTER_EASE: [number, number, number, number] = [0.22, 1, 0.36, 1]
/** Spring(DampingRatioMediumBouncy 0.5, StiffnessMediumLow 400) for an unfill. */
const UNFILL_SPRING = { type: 'spring' as const, stiffness: 400, damping: 20 }
/** A wheel burst ends after this much quiet; the burst decides "began at edge". */
const WHEEL_BURST_GAP_MS = 220
const WHEEL_SETTLE_MS = 200

const smooth = (t: number) => t * t * (3 - 2 * t)

function reducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function armHaptic(): void {
  try {
    if (reducedMotion()) return
    navigator.vibrate?.(10)
  } catch {
    /* capability */
  }
}

function frames(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
}

type Options = {
  scrollRef: RefObject<HTMLElement | null>
  enabled: boolean
  surahId: number
  nextSurahId: number | null
  previousSurahId: number | null
  /** The next-chapter ceremony is running (host owns the page). */
  advancing: boolean
  onAdvanceNext: (nextId: number) => void
}

/**
 * Pull past either end of a chapter to open its neighbour (Android
 * chapterPullConnection). Touch overscroll and accumulated wheel overscroll
 * both feed [stepPull]; the result is painted as CSS variables on the scroll's
 * parent so pulling never re-renders the reader:
 * `--prev-reveal` / `--prev-pull` drive the PREVIOUS band above the page,
 * `--next-rubber` / `--next-pull` / `--next-invite` the footer's Continue pill.
 */
export function useChapterPull(options: Options): () => void {
  const openPreviousRef = useRef<() => void>(() => {})
  const optsRef = useRef(options)
  optsRef.current = options
  const stateRef = useRef<PullState>(EMPTY_PULL)
  const exitingRef = useRef(false)
  const unfillRef = useRef<AnimationPlaybackControls[]>([])

  useEffect(() => {
    const scroll = options.scrollRef.current
    const host = scroll?.parentElement
    if (!options.enabled || !scroll || !host) return
    let mounted = true
    let gesture: { lastY: number; beganAtTop: boolean; beganAtBottom: boolean } | null = null
    let wheelBurst: { beganAtTop: boolean; beganAtBottom: boolean } | null = null
    let lastWheelT = -Infinity
    let wheelSettle = 0

    const atTop = () => scroll.scrollTop <= 0.5
    const atBottom = () =>
      scroll.scrollTop + scroll.clientHeight >= scroll.scrollHeight - 1 &&
      scroll.querySelector('.next-chapter') != null

    const paint = () => {
      const s = stateRef.current
      if (!exitingRef.current) {
        host.style.setProperty('--prev-reveal', `${previousChapterPullOffset(s.previous)}px`)
        host.style.setProperty('--prev-pull', String(s.previous))
        host.style.setProperty('--prev-ink', String(pillInkMix(s.previous)))
      }
      host.style.setProperty('--next-rubber', `${nextChapterPullOffset(s.next)}px`)
      host.style.setProperty('--next-pull', String(s.next))
      host.style.setProperty('--next-ink', String(pillInkMix(s.next)))
      const invite = nextInviteAlpha(s.next)
      host.style.setProperty('--next-invite', String(invite))
      // Android: the pill reads disabled (0.45) once the invite fades past 0.45.
      host.style.setProperty('--next-pill-alpha', invite > 0.45 ? '1' : '0.45')
      host.toggleAttribute('data-chapter-pull', s.next > 0 || s.previous > 0 || exitingRef.current)
    }

    const stopUnfill = () => {
      for (const c of unfillRef.current) c.stop()
      unfillRef.current = []
    }

    const unfill = () => {
      stopUnfill()
      const from = stateRef.current
      const run = (key: 'next' | 'previous') => {
        if (from[key] <= 0) return
        unfillRef.current.push(
          animate(from[key], 0, {
            ...UNFILL_SPRING,
            onUpdate: (v) => {
              stateRef.current = { ...stateRef.current, [key]: Math.max(0, v) }
              paint()
            },
            onComplete: () => {
              stateRef.current = {
                ...stateRef.current,
                [key]: 0,
                [key === 'next' ? 'nextArmed' : 'previousArmed']: false,
              }
              paint()
            },
          }),
        )
      }
      if (reducedMotion()) {
        stateRef.current = EMPTY_PULL
        paint()
        return
      }
      run('next')
      run('previous')
    }

    const openPrevious = async (prevId: number) => {
      exitingRef.current = true
      const startReveal = previousChapterPullOffset(stateRef.current.previous)
      host.style.setProperty('--prev-pull', '1')
      host.style.setProperty('--prev-ink', '1')
      paint()
      appStore.prepareSurah(prevId)
      if (!reducedMotion()) {
        // 1) The page continues down from the held pose and fades.
        await new Promise<void>((resolve) => {
          animate(0, 1, {
            duration: PREVIOUS_EXIT_MS / 1000,
            ease: CHAPTER_EASE,
            onUpdate: (e) => {
              host.style.setProperty('--prev-reveal', `${startReveal + (PREVIOUS_EXIT_PX - startReveal) * e}px`)
              host.style.setProperty('--prev-exit', String(1 - e))
              scroll.style.opacity = String(1 - smooth(e))
            },
            onComplete: () => resolve(),
          })
        })
      }
      appStore.openSurah(prevId, 1)
      if (reducedMotion()) return
      // 2) The previous chapter eases downward into place from above.
      await frames()
      const incoming = document.querySelector<HTMLElement>('.sheet[data-name="reader"] .scroll')
      if (!incoming || incoming === scroll) return
      incoming.style.opacity = '0'
      incoming.style.transform = `translate3d(0, ${-PREVIOUS_ENTER_PX}px, 0)`
      await new Promise<void>((resolve) => {
        animate(0, 1, {
          duration: PREVIOUS_ENTER_MS / 1000,
          ease: CHAPTER_EASE,
          onUpdate: (e) => {
            const u = smooth(e)
            incoming.style.opacity = String(u)
            incoming.style.transform = `translate3d(0, ${-PREVIOUS_ENTER_PX * (1 - u)}px, 0)`
          },
          onComplete: () => resolve(),
        })
      })
      incoming.style.opacity = ''
      incoming.style.transform = ''
    }

    openPreviousRef.current = () => {
      const id = optsRef.current.previousSurahId
      if (id != null && !exitingRef.current && !optsRef.current.advancing) void openPrevious(id)
    }

    const settle = () => {
      if (exitingRef.current) return
      const o = optsRef.current
      if (o.advancing) return
      switch (settlePull(stateRef.current)) {
        case 'next':
          if (o.nextSurahId != null) {
            // The ceremony starts from the stretched pose (no snap back).
            o.onAdvanceNext(o.nextSurahId)
            window.setTimeout(() => {
              if (mounted && !optsRef.current.advancing && stateRef.current.next > 0) unfill()
            }, 600)
          } else unfill()
          break
        case 'previous':
          if (o.previousSurahId != null) void openPrevious(o.previousSurahId)
          else unfill()
          break
        case 'unfill':
          unfill()
          break
        default:
          break
      }
    }

    const apply = (dy: number, beganAtTop: boolean, beganAtBottom: boolean): boolean => {
      const o = optsRef.current
      if (o.advancing || exitingRef.current) return false
      const step = stepPull(stateRef.current, {
        dy,
        hasNext: o.nextSurahId != null,
        hasPrevious: o.previousSurahId != null,
        beganAtTop,
        beganAtBottom,
        atTop: atTop(),
        atBottom: atBottom(),
      })
      if (!step.consumed) return false
      stateRef.current = step.state
      if (step.armed) armHaptic()
      paint()
      return true
    }

    const onTouchStart = (e: TouchEvent) => {
      if (e.touches.length !== 1 || exitingRef.current) {
        gesture = null
        return
      }
      stopUnfill()
      gesture = { lastY: e.touches[0].clientY, beganAtTop: atTop(), beganAtBottom: atBottom() }
    }
    const onTouchMove = (e: TouchEvent) => {
      if (!gesture) return
      if (e.touches.length !== 1) {
        gesture = null
        return
      }
      const y = e.touches[0].clientY
      const dy = y - gesture.lastY
      gesture.lastY = y
      if (dy === 0) return
      if (apply(dy, gesture.beganAtTop, gesture.beganAtBottom) && e.cancelable) e.preventDefault()
    }
    const onTouchEnd = (e: TouchEvent) => {
      if (!gesture || e.touches.length > 0) return
      gesture = null
      settle()
    }
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return
      if (e.timeStamp - lastWheelT > WHEEL_BURST_GAP_MS || !wheelBurst) {
        wheelBurst = { beganAtTop: atTop(), beganAtBottom: atBottom() }
      }
      lastWheelT = e.timeStamp
      const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? scroll.clientHeight : 1
      const dy = -e.deltaY * unit
      if (!apply(dy, wheelBurst.beganAtTop, wheelBurst.beganAtBottom)) return
      e.preventDefault()
      stopUnfill()
      window.clearTimeout(wheelSettle)
      wheelSettle = window.setTimeout(() => {
        wheelBurst = null
        settle()
      }, WHEEL_SETTLE_MS)
    }

    scroll.addEventListener('touchstart', onTouchStart, { passive: true })
    scroll.addEventListener('touchmove', onTouchMove, { passive: false })
    scroll.addEventListener('touchend', onTouchEnd, { passive: true })
    scroll.addEventListener('touchcancel', onTouchEnd, { passive: true })
    scroll.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      mounted = false
      openPreviousRef.current = () => {}
      window.clearTimeout(wheelSettle)
      stopUnfill()
      scroll.removeEventListener('touchstart', onTouchStart)
      scroll.removeEventListener('touchmove', onTouchMove)
      scroll.removeEventListener('touchend', onTouchEnd)
      scroll.removeEventListener('touchcancel', onTouchEnd)
      scroll.removeEventListener('wheel', onWheel)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options.enabled, options.surahId])

  return useCallback(() => openPreviousRef.current(), [])
}
