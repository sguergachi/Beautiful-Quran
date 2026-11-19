import { useEffect, useState, type RefObject } from 'react'
import { ContextualFeatureTip } from '../theme/ContextualFeatureTip'
import { chapterRowKey } from './pinnedChapters'
import { rowPlacement } from './continuePlace'
import type { PinChapterLessonState } from './usePinChapterLesson'

/** The ribbon lane plus half the number column: the row's number. */
const NUMBER_CENTRE_X = 28 + 13

/**
 * Android `PinChapterTip`: the pinning lesson written around the live row of
 * the chapter just read. The row stays the real target (holding it pins);
 * the lesson takes the right side, level with the chapter, and Got it sits
 * at the foot of the guide.
 */
export function PinChapterTip({
  lesson,
  eligible,
  sheetRef,
  scrollerRef,
}: {
  lesson: PinChapterLessonState
  /** Chapters on top, no search, nothing pinned, no transport. */
  eligible: boolean
  sheetRef: RefObject<HTMLElement | null>
  scrollerRef: RefObject<HTMLElement | null>
}) {
  const { chapter, open, setOpen, rendered, setRendered, close, allowed } = lesson
  const [target, setTarget] = useState<{ x: number; y: number } | null>(null)
  const active = chapter != null && eligible

  const rowOf = (): HTMLElement | null =>
    chapter == null
      ? null
      : (scrollerRef.current?.querySelector<HTMLElement>(`[data-row-key="${chapterRowKey(chapter)}"]`)?.closest('li') ?? null)

  const measure = (): { visible: boolean; x: number; y: number } | null => {
    const row = rowOf()
    const scroller = scrollerRef.current
    const sheet = sheetRef.current
    if (!row || !scroller || !sheet) return null
    const r = row.getBoundingClientRect()
    const v = scroller.getBoundingClientRect()
    const field = scroller.querySelector('.search-row')?.getBoundingClientRect()
    const s = sheet.getBoundingClientRect()
    return {
      visible: rowPlacement(r.top, r.bottom, field ? field.bottom : v.top, v.bottom) === 'visible',
      x: r.left - s.left + NUMBER_CENTRE_X,
      y: r.top - s.top + r.height / 2,
    }
  }

  // The chapter may be nowhere near where the list was left: bring its row
  // into the upper part of the screen, then offer the lesson once it rests.
  useEffect(() => {
    if (!active || open || !allowed()) return
    let offer = 0
    const bring = window.setTimeout(() => {
      const place = measure()
      const scroller = scrollerRef.current
      const row = rowOf()
      if (place && !place.visible && scroller && row) {
        const inset = scroller.clientHeight * 0.22
        const top = scroller.scrollTop + row.getBoundingClientRect().top - scroller.getBoundingClientRect().top - inset
        scroller.scrollTo({ top, behavior: 'smooth' })
      }
      offer = window.setTimeout(() => {
        const settled = measure()
        if (!settled?.visible || !allowed()) return
        setTarget({ x: settled.x, y: settled.y })
        setOpen(true)
      }, 1100)
    }, 500)
    return () => {
      window.clearTimeout(bring)
      window.clearTimeout(offer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, open, chapter])

  // Ordinary page scrolling puts the lesson away, as does leaving Chapters.
  useEffect(() => {
    if (!open) return
    if (!eligible) {
      close()
      return
    }
    const scroller = scrollerRef.current
    const onScroll = () => close()
    scroller?.addEventListener('scroll', onScroll, { passive: true, once: true })
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      scroller?.removeEventListener('scroll', onScroll)
      window.removeEventListener('keydown', onKey)
    }
  }, [open, eligible, close, scrollerRef])

  if (!target || (!open && !rendered)) return null
  const sheet = sheetRef.current
  const width = sheet?.clientWidth ?? 0
  const height = sheet?.clientHeight ?? 0
  return (
    <div className="pin-chapter-tip">
      <ContextualFeatureTip
        visible={open}
        title="Keep it at the top"
        body="Press and hold this chapter to pin it."
        onDismiss={close}
        onRenderedChange={setRendered}
        spotlightSide="left"
        spotlightCenter={target}
        placement={{ bodyAngleDegrees: 0 }}
        actionCenter={{ x: Math.max(52, width - 68), y: Math.max(52, height - 72) }}
        guideWidthFraction={0.62}
        guideHeightPx={300}
        contentPadding={{ start: 18, end: 28 }}
        dismissPaperColor="var(--paper)"
        dismissInkColor="var(--ink)"
      />
    </div>
  )
}
