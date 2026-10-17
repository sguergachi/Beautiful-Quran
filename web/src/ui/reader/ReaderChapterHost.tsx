import { useCallback, useEffect, useRef, useState } from 'react'
import { appStore } from '../../store/appStore'
import type { Surah } from '../../data/models'
import type { StackLayer } from '../paper/stack'
import { ReaderScreen } from './ReaderScreen'

/** Metadata that survives the keyed reader while its opening moves between chapters. */
export interface ChapterTransition {
  toId: number
  title: Surah | null
}

const ease = (t: number) => t * t * (3 - 2 * t)

/** Abort cancels the pending frame as well as every continuation after it. */
function frames(ms: number, signal: AbortSignal, paint: (progress: number) => void) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason)
      return
    }
    const start = performance.now()
    let frame: number
    const abort = () => {
      cancelAnimationFrame(frame)
      reject(signal.reason)
    }
    const tick = (now: number) => {
      const progress = ms === 0 ? 1 : Math.min(1, (now - start) / ms)
      paint(ease(progress))
      if (progress < 1) frame = requestAnimationFrame(tick)
      else {
        signal.removeEventListener('abort', abort)
        resolve()
      }
    }
    signal.addEventListener('abort', abort, { once: true })
    frame = requestAnimationFrame(tick)
  })
}

/** Keeps the chapter ceremony alive while each reader gets fresh focus/rail state. */
export function ReaderChapterHost({ chapterId, ...props }: {
  chapterId: number | undefined
  stackLayer: StackLayer
  playbackPinned: boolean
}) {
  const [transition, setTransition] = useState<ChapterTransition | null>(null)
  const running = useRef<AbortController | null>(null)
  useEffect(() => () => running.current?.abort(), [])

  const advance = useCallback(async (nextId: number, scroll: HTMLElement, pinTitle: boolean) => {
    if (running.current) return
    const origin = appStore.getSnapshot()
    if (!origin.content || nextId !== origin.content.surah.id + 1) return
    appStore.prepareSurah(nextId)
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      appStore.openSurah(nextId, 1)
      return
    }
    const controller = new AbortController()
    const { signal } = controller
    running.current = controller
    setTransition({ toId: nextId, title: pinTitle ? origin.content.surah : null })
    appStore.setFollowEnabled(false)
    let revision = origin.readerOpenRevision
    let layer = origin.stackLayer
    let handingOff = false
    // A later navigation owns the page immediately, including a same-chapter
    // bookmark. Never let a stale animation navigate over that newer intent.
    const unsubscribe = appStore.subscribe(() => {
      const current = appStore.getSnapshot()
      if (!handingOff && (
          current.readerOpenRevision !== revision ||
          current.stackLayer !== layer || current.rootViewer != null ||
          current.settings.readingLayout !== 'scroll'
      )) controller.abort()
    })
    const cancelOnResize = () => controller.abort()
    window.addEventListener('resize', cancelOnResize)
    const opening = scroll.querySelector<HTMLElement>('.next-chapter-opening')
    const footer = scroll.querySelector<HTMLElement>('.next-chapter')
    let flyer: HTMLElement | null = null
    let header: HTMLElement | null = null
    try {
      await frames(0, signal, () => {})
      const page = scroll.getBoundingClientRect()
      const box = opening?.getBoundingClientRect()
      const top = box?.top ?? page.bottom - 200
      const travel = top - (page.top + 8)
      const lift = Math.max(travel, window.innerHeight * 0.55, 320)
      if (opening) {
        flyer = opening.cloneNode(true) as HTMLElement
        flyer.classList.add('chapter-fly-header')
        flyer.setAttribute('aria-hidden', 'true')
        // The clone leaves the book's theme/font cascade. Carry its source
        // geometry and palette across the page-to-body portal.
        const styles = getComputedStyle(opening)
        for (const name of styles) {
          if (name.startsWith('--')) flyer.style.setProperty(name, styles.getPropertyValue(name))
        }
        Object.assign(flyer.style, {
          position: 'fixed', top: `${top}px`, left: `${box!.left}px`,
          width: `${box!.width}px`, margin: '0', zIndex: '40',
          pointerEvents: 'none', opacity: '1', transform: 'translate3d(0,0,0)',
          willChange: 'transform, opacity', transition: 'none',
        })
        document.body.appendChild(flyer)
        opening.style.opacity = '0'
      }
      scroll.style.willChange = 'transform, opacity'
      await frames(780, signal, progress => {
        const morph = ease(Math.min(1, progress / 0.35))
        footer?.style.setProperty('--invite', String(Math.max(0, 1 - morph / 0.85)))
        for (const node of [footer, flyer]) {
          node?.style.setProperty('--rosette-scale', String(40 / 52 + (1 - 40 / 52) * morph))
          node?.style.setProperty('--rosette-alpha', String(0.88 + 0.12 * morph))
        }
        if (flyer) flyer.style.transform = `translate3d(0, ${-travel * progress}px, 0)`
        const exit = Math.min(1, progress / 0.28)
        const exited = 1 - (1 - exit) * (1 - exit)
        scroll.style.transform = `translate3d(0, ${-lift * exited}px, 0)`
        scroll.style.opacity = String(1 - exited)
      })
      handingOff = true
      appStore.openSurah(nextId, 1)
      revision = appStore.getSnapshot().readerOpenRevision
      layer = appStore.getSnapshot().stackLayer
      handingOff = false
      await frames(0, signal, () => {})
      const incoming = document.querySelector<HTMLElement>('.sheet[data-name="reader"]')
      header = incoming?.querySelector<HTMLElement>('.scroll > .surah-header') ?? null
      if (header) header.style.opacity = '0'
      await frames(320, signal, progress => {
        if (header) header.style.opacity = String(progress)
        if (flyer) flyer.style.opacity = String(1 - progress)
      })
      flyer?.remove()
      // Paint inherited variables on the new sheet; no React work per frame.
      await frames(640, signal, progress => {
        incoming?.style.setProperty('--verse-reveal', String(progress))
        incoming?.style.setProperty('--verse-fade', String(ease(Math.max(0, (progress - 0.08) / 0.92))))
      })
    } catch (error) {
      if (!signal.aborted) throw error
    } finally {
      unsubscribe()
      window.removeEventListener('resize', cancelOnResize)
      flyer?.remove()
      if (opening) opening.style.opacity = ''
      if (header) header.style.opacity = ''
      scroll.style.transform = ''
      scroll.style.opacity = ''
      scroll.style.willChange = ''
      for (const name of ['--invite', '--rosette-scale', '--rosette-alpha']) {
        footer?.style.removeProperty(name)
      }
      const current = appStore.getSnapshot()
      if (current.readerOpenRevision === origin.readerOpenRevision) {
        appStore.setFollowEnabled(origin.followEnabled)
      }
      if (running.current === controller) {
        running.current = null
        setTransition(null)
      }
    }
  }, [])

  return (
    <ReaderScreen
      key={chapterId ?? 'empty-reader'}
      {...props}
      chapterTransition={transition}
      onAdvanceChapter={advance}
    />
  )
}
