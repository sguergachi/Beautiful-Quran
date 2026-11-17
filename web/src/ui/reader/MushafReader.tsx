import { WordMotionProvider } from '../../render/WordMotionContext'
import type { WordMotion } from '../../render/useWordWash'
import { suppliedWords } from '../../render/suppliedWords'
import {
  Fragment,
  memo,
  useCallback,
  useEffect,
  useMemo,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type Ref,
} from 'react'
import { createPortal } from 'react-dom'
import { QuranRepository } from '../../data/repository'
import { runtimeMushafCache } from '../../data/runtimeMushaf'
import { readingAppearanceKey } from '../../data/customizePolicy'
import {
  MUSHAF_LINES_PER_PAGE,
  MUSHAF_OPENING_PAGES,
  MUSHAF_PAGE_COUNT,
  mushafFacingPages,
  mushafTokenEndsAyah,
} from '../../domain/mushafPage'
import { formatAyahNumberMark, mushafHeadFigures } from '../../util/digits'
import type { PageNumberScript } from '../../data/settings'
import { appStore, useAppSelector } from '../../store/appStore'
import {
  CHAPTERS_PLACE,
  bookRest,
  bookRightShare,
  restBook,
  setBookAir,
  setBookPlace,
  turnMs,
  useBookSpread,
  useTurningLeafSlot,
  useVersoLeafSlot,
} from '../paper/bookSpread'
import { COVER_LAYER, READER_LAYER, underlyingLayer } from '../paper/stack'
import { LeafPicture, TurningLeaf } from './TurningLeaf'
import { finishPageTurn, requestPageTurn, type PageTurnQueue } from './pageTurnQueue'
import {
  MUSHAF_MAX_CONDENSE,
  MUSHAF_MIN_LEADING,
  MUSHAF_MIN_GAP,
  mushafFit,
  mushafLeafFit,
  mushafLineGaps,
  mushafMeasure,
  naturalLineWidth,
  solveLine,
} from './mushafFit'
import { mushafLeafModel } from './mushafLeafModel'
import { MushafChapterBand } from './MushafChapterBand'
import { BASMALAH_UTHMANI } from '../../domain/Basmalah'
import { pileTurnSchedule, playFlip, warmPageTurnSounds } from '../paper/pageTurnSounds'
import { clearPagePictures, hasPagePicture, warmPageTurn } from '../paper/pageTurn'
import { InkEngine, InkState, getTuning, type InkWord } from './InkEngine'
import { MUSHAF_STILL_INK, mushafMarkWaits, mushafTokenInk, type MushafInk } from './mushafInk'
import { pageReadingPlace, type MushafToken } from '../../domain/mushafPage'
import type { AyahRef } from '../../share/gather'
import { HafsWord } from '../../render/HafsWord'
import { BASMALAH_PLAYLIST_AYAH } from '../../domain/Basmalah'
import { createWheelTurn, isSidewaysWheel } from './wheelTurn'
import { LEAF_TURN_MS } from '../paper/bookSpread'
import type { TurnDrive } from '../paper/pageTurn'
import { ReturnToAyahButton } from './ReturnToAyahButton'
import { mushafLeaf, playMushafTarget, type MushafPlayTarget } from './mushafLeafState'
import type { DialLabel } from './mushafDial'
import { mushafReturnWay } from './mushafDial'
import {
  MUSHAF_FOLLOW_TURN_MS,
  juzOf,
  mushafLeadsTurn,
  mushafTurnLeadDelayMs,
  mushafVoicePage,
  type PageMap,
} from './mushafFollow'
import './mushaf.css'

/** The share of the block between two pages: the pile a turn between them carries. */
function pileBetween(from: number, to: number): number {
  return Math.abs(to - from) / (MUSHAF_PAGE_COUNT - 1)
}

/** The Chapters sheet as it lies on the left-hand page, for its picture on a turning pile. */
function chaptersSheet(): Element | null {
  return document.querySelector('.app-shell > .sheet[data-name="home"]')
}

/** Runs [work] when the browser has nothing else to do; returns what calls it off. */
function whenIdle(work: () => void): () => void {
  if ('requestIdleCallback' in window) {
    const id = window.requestIdleCallback(work, { timeout: 1500 })
    return () => window.cancelIdleCallback(id)
  }
  const id = setTimeout(work, 200)
  return () => clearTimeout(id)
}

/** Told apart in a kept picture's key: one reader's revisions mean nothing to the next. */
let readers = 0

/** A page whose picture is wanted ahead of its turn. */
interface WarmPage {
  page: number
  side: 'recto' | 'verso' | undefined
  fit: number
  key: string
}

/** Whether [page] carries any word of the verse that owns the voice. */
function pageHoldsVoice(page: number, surahId: number, ink: MushafInk): boolean {
  if (!ink.reciting || !runtimeMushafCache) return false
  const rows = runtimeMushafCache.pageWords(page) ?? []
  // The basmalah lead-in is recited over the leaf that opens the chapter.
  const voiced = ink.inkAyah === BASMALAH_PLAYLIST_AYAH ? 1 : ink.inkAyah
  return rows.some((row) => row.surah_id === surahId &&
    (row.ayah_number === voiced || row.ayah_number === ink.leadAyah))
}

/** Playback highlight when it exists; otherwise the ayah the sheet was opened on. */
function followedAyah(activeAyah: number | null, openAyah: number): number {
  return activeAyah != null && activeAyah > 0 ? activeAyah : Math.max(1, openAyah)
}

/**
 * One Madinah leaf. The 604 page boundaries come from the Quran Foundation
 * page map once it is on this device. The words are Hafs: the per-page QCF
 * faces are not shipped to the browser.
 */
export function MushafReader({
  ownsKeyboard,
  activeSurahId,
  activeAyah,
  openAyah,
  openRevision,
  english,
  pageNumberScript,
  glyphWiden,
  ink,
  onPlayWord,
  onHoldWord,
  onReadingPlace,
  searchTarget,
}: {
  glyphWiden: number
  ink: MushafInk
  pageNumberScript: PageNumberScript
  ownsKeyboard: boolean
  activeSurahId: number
  activeAyah: number | null
  openAyah: number
  openRevision: number
  english: boolean
  onReadingPlace: (place: AyahRef) => void
  searchTarget: AyahRef | null
  onPlayWord: (surahId: number, ayah: number, position: number) => void
  /** [side] is the page of a spread the word stands on. */
  onHoldWord: (surahId: number, ayah: number, position: number, side?: 'recto' | 'verso') => void
}) {
  const targetAyah = followedAyah(activeAyah, openAyah)
  // A chapter or verse just opened starts at its own leaf. After that the
  // leaf is the hand's, or the voice's while follow is on (Android
  // `MushafPager`): a hand-turned leaf stays where it was turned.
  const openKey = `${activeSurahId}:${openRevision}`
  const [manualPage, setManualPage] = useState<number | null>(null)
  const [manualFor, setManualFor] = useState(openKey)
  if (manualFor !== openKey) {
    setManualFor(openKey)
    setManualPage(null)
  }
  const followEnabled = useAppSelector((state) => state.followEnabled)
  const [ready, setReady] = useState(() => runtimeMushafCache?.layoutReady() ?? false)
  const settings = useAppSelector((state) => state.settings)
  const spread = useBookSpread(settings.pagePresentation)
  const versoSlot = useVersoLeafSlot()
  const turnSlot = useTurningLeafSlot()
  // Two facing leaves on a desktop spread; one leaf everywhere else.
  const facing = spread && versoSlot != null
  const rectoRef = useRef<HTMLDivElement>(null)
  const [versoBox, setVersoBox] = useState<CSSProperties>({})
  const [fitRevision, setFitRevision] = useState(0)
  const [fontsReady, setFontsReady] = useState(() => !document.fonts || document.fonts.status === 'loaded')
  const [fits, setFits] = useState<Record<number, number>>({})
  const reportFit = useCallback((page: number, fit: number) => {
    setFits((current) => current[page] != null && Math.abs(current[page] - fit) < 0.002
      ? current : { ...current, [page]: fit })
  }, [])
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const changed = () => setReduced(query.matches)
    query.addEventListener('change', changed)
    return () => query.removeEventListener('change', changed)
  }, [])

  useEffect(() => {
    warmPageTurnSounds()
    warmPageTurn()
  }, [])

  useEffect(() => {
    if (!runtimeMushafCache) return
    return runtimeMushafCache.subscribe(() => {
      // The page map may have changed under the pictures kept of its pages.
      clearPagePictures()
      setReady(runtimeMushafCache.layoutReady())
    })
  }, [])
  // Every setting except the saved reading/listening positions still gives
  // a kept picture a new key. Remembering a turn must not discard its pictures.
  const appearance = useMemo(() => readingAppearanceKey(settings), [settings])
  const look = useRef({ appearance, revision: 0 })
  if (look.current.appearance !== appearance) look.current = { appearance, revision: look.current.revision + 1 }
  const [reader] = useState(() => ++readers)

  const map: PageMap | null = ready && runtimeMushafCache ? runtimeMushafCache : null
  const opened = map ? map.pageOfAyah(activeSurahId, Math.max(1, openAyah)) : null
  // Where the voice is, by word: a verse the book carried over turns the
  // leaf mid-verse rather than at the next verse.
  const voicePage = map
    ? mushafVoicePage(map, activeSurahId, {
        reciting: ink.reciting,
        inkAyah: ink.inkAyah,
        activeWord: ink.activeWord,
        activeAyah,
      })
    : null
  // The turn is begun inside the leaf's last word, 500 ms before it ends.
  const [lead, setLead] = useState<{ from: number; to: number } | null>(null)
  const voiceWord = ink.activeWord
  useEffect(() => {
    if (!map || !followEnabled || voicePage == null || !voiceWord) return
    if (!mushafLeadsTurn(map, voicePage, MUSHAF_PAGE_COUNT, activeSurahId, voiceWord)) return
    const timer = window.setTimeout(
      () => setLead({ from: voicePage, to: voicePage + 1 }),
      mushafTurnLeadDelayMs(voiceWord.durationMs, ink.speed),
    )
    return () => window.clearTimeout(timer)
    // The same activation of the same word; anything newer re-aims.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, followEnabled, voicePage, voiceWord?.ayah, voiceWord?.wordPosition, voiceWord?.activation, ink.speed, activeSurahId])
  const followPage = voicePage == null ? null : lead && lead.from === voicePage ? lead.to : voicePage
  const following = followEnabled && followPage != null
  // Following keeps the hand's page with it, so a pause leaves the leaf where
  // the voice stopped rather than where the chapter was opened.
  if (following && manualPage !== followPage) setManualPage(followPage)
  const page = following ? followPage : manualPage ?? opened ?? 1
  // A hand on the leaf takes it from the voice; the return pill gives it back.
  const handTurn = (next: number) => {
    setManualPage(next)
    if (voicePage != null && appStore.getSnapshot().followEnabled) appStore.setFollowEnabled(false)
  }
  const handTurnRef = useRef(handTurn)
  handTurnRef.current = handTurn
  useEffect(() => {
    if (!ready || !searchTarget) return
    const target = runtimeMushafCache?.pageOfAyah(searchTarget.surahId, searchTarget.ayah)
    if (target != null) handTurnRef.current(target)
  }, [ready, searchTarget])

  const stackLayer = useAppSelector((state) => state.stackLayer)
  const bookLayer = useAppSelector((state) =>
    underlyingLayer(state.stackLayer, state.content != null, state.settingsOrigin),
  )
  const turn = (delta: number) => {
    // The leaves under Chapters are not being read.
    if (spread && stackLayer <= COVER_LAYER) return
    // A spread turns both of its leaves at once.
    const from = facing ? mushafFacingPages(page).right : page
    const next = Math.min(MUSHAF_PAGE_COUNT, Math.max(1, from + delta * (facing ? 2 : 1)))
    if (next !== from) handTurn(next)
  }

  // The facing leaf hangs under the verso sheets, outside this one. Give it
  // the recto leaf's own box so the two pages' lines sit on one grid.
  useLayoutEffect(() => {
    const leaf = rectoRef.current
    const sheet = leaf?.closest('.sheet')
    if (!leaf || !sheet) return
    let size = ''
    let disposed = false
    const measure = () => {
      if (disposed) return
      const box = leaf.getBoundingClientRect()
      const page = sheet.getBoundingClientRect()
      const nextSize = `${box.width}:${box.height}`
      if (size !== nextSize) {
        size = nextSize
        setFits({})
        setFitRevision((revision) => revision + 1)
      }
      setVersoBox({
        top: box.top - page.top,
        left: box.left - page.left,
        width: box.width,
        height: box.height,
      })
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(leaf)
    observer.observe(sheet)
    void document.fonts?.ready.then(() => {
      if (!disposed) {
        setFontsReady(true)
        setFits({})
        setFitRevision((revision) => revision + 1)
      }
    })
    return () => { disposed = true; observer.disconnect() }
  }, [facing, ready, english])

  // The listeners below are bound once and read what they need through
  // refs. Bound with no dependencies they were torn down and re-added on
  // every render, and a leaf renders on every word the voice reaches.
  const turnRef = useRef(turn)
  turnRef.current = turn
  const ownsKeyboardRef = useRef(ownsKeyboard)
  ownsKeyboardRef.current = ownsKeyboard

  // Pages run right to left: the left arrow goes on, the right arrow back.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!ownsKeyboardRef.current || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      // Only fields that use the arrows themselves keep them. A focused
      // button does not — on a phone the chapter row that opened this
      // reader still holds focus behind it, and would swallow every turn.
      const target = event.target
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || target.closest('input, textarea, select, [role="slider"]'))
      ) {
        return
      }
      event.preventDefault()
      turnRef.current(event.key === 'ArrowLeft' ? 1 : -1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Two fingers swept sideways turn a leaf. Taken from the browser, which
  // would otherwise go back or forward in its history on the same sweep, so
  // the listener cannot be passive. It is bound to the two pages the leaves
  // lie on, not the window: there it held up every scroll in the app
  // (Chapters, Settings) until the handler had run.
  useEffect(() => {
    if (!ready) return
    const wheelTurn = createWheelTurn()
    const onWheel = (event: Event) => {
      const wheel = event as WheelEvent
      if (!ownsKeyboardRef.current || wheel.ctrlKey || !isSidewaysWheel(wheel.deltaX, wheel.deltaY)) return
      const target = wheel.target
      if (!(target instanceof Element) || !target.closest('.mushaf, .mushaf-turn')) return
      wheel.preventDefault()
      const delta = wheelTurn(wheel.deltaX, wheel.deltaY, wheel.timeStamp)
      if (delta !== 0) turnRef.current(delta)
    }
    const pages = [rectoRef.current?.closest('.sheet'), facing ? versoSlot : null]
      .filter((el): el is HTMLElement => el instanceof HTMLElement)
    for (const el of pages) el.addEventListener('wheel', onWheel, { passive: false })
    return () => { for (const el of pages) el.removeEventListener('wheel', onWheel) }
  }, [ready, facing, versoSlot])

  const place = facing ? mushafFacingPages(page).right : page
  // On facing leaves Chapters stands at the start of the book, opposite its
  // first page: while it shows, the book is open there, and the place being
  // read is kept for the way back. Going to Chapters turns every leaf read
  // back onto the left, and coming away turns the leaves before the place.
  const atChapters = facing && bookLayer <= COVER_LAYER
  const shown = atChapters ? CHAPTERS_PLACE : place
  // A reader is built anew for each chapter, but the book was already
  // somewhere: it starts there and turns what lies between (bookRest).
  const [found] = useState(() => (facing && ready ? bookRest() : null))
  const [queue, setQueue] = useState<PageTurnQueue>({
    settled: found?.place ?? shown,
    flight: null,
    queued: null,
  })
  const footing = `${ready}:${facing}:${english}:${pageNumberScript}`
  const [settledFooting, setSettledFooting] = useState(footing)
  const [motion, setMotion] = useState<{
    flight: NonNullable<PageTurnQueue['flight']>
    /** Share of the block this turn carries, and how long that takes. */
    wad: number
    ms: number
    fit: number
    fromFit: number
    initiatedAt: number
    props: typeof leafProps
    box: CSSProperties
    /** The side of the pile that is Chapters: it lifts with it, or lands as it. */
    chapters: 'face' | 'back' | null
    /** A leaf under a finger: the hand sets how far it has turned. */
    drive?: TurnDrive
  } | null>(null)
  // Both open pages wait for the voice together, or neither does: verses to
  // come on the verso must not stand in full ink beside a dimmed recto.
  const leafLive = (leaf: number, voice: MushafInk = ink) => {
    if (!facing) return pageHoldsVoice(leaf, activeSurahId, voice)
    const pair = mushafFacingPages(leaf)
    return pageHoldsVoice(pair.right, activeSurahId, voice) ||
      pageHoldsVoice(pair.left, activeSurahId, voice)
  }
  const leafProps = { fitRevision, facing, activeSurahId, activeAyah, english, pageNumberScript, glyphWiden, ink, onPlayWord, onHoldWord, onTurn: facing ? turn : undefined, onFit: reportFit }
  // Nothing turns under a sheet laid over the leaves (Settings, the word
  // viewer): a leaf in the air would cross it. Under Chapters the leaves do
  // turn, since that is how the book gets there. A phone has one sheet, and
  // a reader that is not on top is out of sight.
  const covered = facing ? !ownsKeyboard && stackLayer >= READER_LAYER : !ownsKeyboard
  const reset = settledFooting !== footing || reduced || covered
  // Chapters is the face of the pile that leaves it.
  const wasAtChapters = useRef(found ? found.layer <= COVER_LAYER : atChapters)
  const offChapters = useRef(false)
  useLayoutEffect(() => {
    offChapters.current = wasAtChapters.current && !atChapters && shown !== CHAPTERS_PLACE
    wasAtChapters.current = atChapters
    if (reset) {
      setSettledFooting(footing)
      setQueue({ settled: shown, flight: null, queued: null })
      setMotion(null)
    } else {
      setQueue((current) => requestPageTurn(current, shown))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shown, footing, reset])

  const destination = queue.flight?.to ?? queue.settled
  const pair = facing ? mushafFacingPages(destination) : { right: destination, left: destination }
  const preparedFit = mushafFit(destination, facing, fits)
  // A reader built for this chapter has measured neither spread: the leaf it
  // lifts off is set in the hand that page was shown in.
  const departure = queue.flight ? mushafFit(queue.flight.from, facing, fits) : undefined
  const fitReady = fontsReady && preparedFit != null && (!queue.flight || departure != null)
  useLayoutEffect(() => {
    if (reset || !queue.flight || motion || !fitReady) return
    const wad = facing ? pileBetween(queue.flight.from, queue.flight.to) : 0
    setMotion({
      flight: queue.flight,
      wad,
      // The voice's own turn is paced, not flung (Android MushafFollowTurnSpec).
      ms: !facing && following ? MUSHAF_FOLLOW_TURN_MS : turnMs(wad),
      fit: preparedFit!,
      fromFit: mushafFit(queue.flight.from, facing, fits) ?? 1,
      initiatedAt: performance.now(),
      props: leafProps,
      box: versoBox,
      chapters: !facing ? null
        : atChapters && queue.flight.to === CHAPTERS_PLACE ? 'back'
        : offChapters.current && queue.flight.from === CHAPTERS_PLACE ? 'face'
        : null,
    })
    offChapters.current = false
    // The page, ink and geometry in the air stay frozen until it lands.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queue.flight, fitReady, motion, reset])
  const landedFlight = useRef<PageTurnQueue['flight']>(null)
  const cancelSoundRef = useRef<(() => void) | null>(null)
  useEffect(() => () => cancelSoundRef.current?.(), [])
  const endTurn = useCallback(() => {
    landedFlight.current = motion?.flight ?? null
    if (motion?.drive?.release === 'cancel') {
      // Put back: the leaf lies where it was lifted from.
      const flight = motion.flight
      setQueue((current) => current.flight === flight
        ? { settled: flight.from, flight: null, queued: null } : current)
    } else {
      setQueue((current) => finishPageTurn(current, motion?.flight ?? null))
    }
    setMotion((current) => current === motion ? null : current)
  }, [motion])
  useEffect(() => {
    if (!motion || reset) return
    // Leaves turn silently, as on Android; only the pile that lands as
    // Chapters (or lifts with it) is a turn of the paper stack.
    let cancelSound: (() => void) | null = null
    if (motion.chapters) {
      cancelSoundRef.current?.()
      cancelSound = playFlip(pileTurnSchedule(motion.ms), motion.initiatedAt)
      cancelSoundRef.current = cancelSound
    }
    // A leaf under a finger ends when the hand lets it go.
    const timer = motion.drive ? 0 : window.setTimeout(endTurn, motion.ms + 250)
    return () => {
      window.clearTimeout(timer)
      // A normal landing lets the drop ring out; cancellation cuts its stems.
      if (cancelSound && landedFlight.current !== motion.flight) cancelSound()
    }
  }, [motion, reset, endTurn])

  const turning = motion != null && !reset
  const settled = reset ? shown : queue.settled
  useEffect(() => {
    if (!ready || turning || covered || atChapters) return
    const pages = facing ? [settled, settled + 1] : [settled]
    const verses = pages.flatMap((page) => runtimeMushafCache?.pageWords(page) ?? [])
      .map((row) => ({ surahId: row.surah_id, ayah: row.ayah_number }))
    const reading = pageReadingPlace(verses, searchTarget ?? { surahId: activeSurahId, ayah: targetAyah })
    if (reading) onReadingPlace(reading)
  }, [ready, turning, covered, atChapters, facing, settled, activeSurahId, targetAyah, searchTarget, onReadingPlace])
  const landing = turning ? motion.flight.to : settled
  const forward = landing > settled
  const fit = turning ? motion.fit : (mushafFit(settled, facing, fits) ?? 1)

  // The piles under the open pages are the leaves that lie there. A leaf
  // leaves its pile when it is lifted and joins the other when it lands, so
  // the piles follow the place the book last settled on, and the leaves in
  // the air are told apart: nothing is added to a pile that was not turned
  // onto it.
  useLayoutEffect(() => {
    if (!facing) return
    setBookPlace(settled)
    return () => setBookPlace(null)
  }, [facing, settled])
  useLayoutEffect(() => {
    if (facing) restBook({ place: settled })
  }, [facing, settled])
  const airFrom = turning ? bookRightShare(motion.flight.from) : null
  const airTo = turning ? bookRightShare(motion.flight.to) : null
  useLayoutEffect(() => {
    if (!facing || airFrom == null || airTo == null) return
    setBookAir({ from: airFrom, to: airTo })
    return () => setBookAir(null)
  }, [facing, airFrom, airTo])

  // What a page's picture is kept under: everything a leaf that is not being
  // recited is drawn from. A leaf that carries the voice changes word by
  // word and has no key: its picture is taken as it lifts.
  const shotKey = (leaf: number, side: 'recto' | 'verso' | undefined, leafFit: number, live: boolean) =>
    live || !fontsReady ? undefined : [
      leaf,
      side ?? 'single',
      leafFit.toFixed(5),
      fitRevision,
      english ? `en:${activeSurahId}:${activeAyah}` : 'ar',
      pageNumberScript,
      glyphWiden,
      look.current.revision,
      reader,
    ].join('|')
  const air = useMemo(() => {
    if (!motion) return null
    const from = mushafFacingPages(motion.flight.from)
    const to = mushafFacingPages(motion.flight.to)
    const on = motion.flight.to > motion.flight.from
    const props = { ...motion.props, onFit: noFit, still: true }
    const facePage = facing ? on ? from.left : from.right : Math.min(motion.flight.from, motion.flight.to)
    const backPage = on ? to.right : to.left
    const faceFit = facing || on ? motion.fromFit : motion.fit
    const faceSide = facing ? on ? 'verso' : 'recto' : undefined
    const faceLive = leafLive(facePage, props.ink)
    const backLive = leafLive(backPage, props.ink)
    // The Chapters sheet is pictured as it lies, not built again: it is the
    // pile's face as the pile leaves it and its underside as it arrives.
    const chapters = motion.chapters
    return {
      face: chapters === 'face' ? null : <MushafLeaf page={facePage} live={faceLive} fit={faceFit}
        side={faceSide} style={facing ? motion.box : undefined} {...props} />,
      faceKey: chapters === 'face' ? undefined : shotKey(facePage, faceSide, faceFit, faceLive),
      faceFrom: chapters === 'face' ? chaptersSheet : undefined,
      back: facing ? chapters === 'back' ? true : <MushafLeaf page={backPage} live={backLive} fit={motion.fit} side={on ? 'recto' : 'verso'} style={motion.box} {...props} /> : undefined,
      backKey: facing && chapters !== 'back' ? shotKey(backPage, on ? 'recto' : 'verso', motion.fit, backLive) : undefined,
      backFrom: chapters === 'back' ? chaptersSheet : undefined,
    }
    // Frozen with the motion, like the leaf it describes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [motion, facing])

  // Pictures taken ahead of the turn that will want them, while nothing is
  // happening: the two pages that are open (either may lift, for a page turn
  // or to go to Chapters or Settings), and the page each neighbouring turn
  // would land as. Taken on the click they held the leaf still for a tenth
  // of a second before it moved.
  // (Also under Chapters or Settings, where the reader does not own the
  // keys: a setting changed there should not cost the turn back. Never
  // while the voice is reading: a picture takes a frame or two, and the
  // word being washed would stand still for them.)
  const still = ready && fontsReady && !reduced && settledFooting === footing &&
    !queue.flight && !motion && !ink.reciting
  const wanted: { page: number; side: 'recto' | 'verso' | undefined; pair: number }[] = []
  if (still && facing) {
    const here = mushafFacingPages(settled)
    wanted.push({ page: here.right, side: 'recto', pair: here.right }, { page: here.left, side: 'verso', pair: here.right })
    if (here.left < MUSHAF_PAGE_COUNT) wanted.push({ page: here.left + 1, side: 'recto', pair: here.left + 1 })
    if (here.right > 1) wanted.push({ page: here.right - 1, side: 'verso', pair: here.right - 2 })
  } else if (still) {
    wanted.push({ page: settled, side: undefined, pair: settled })
    if (settled > 1) wanted.push({ page: settled - 1, side: undefined, pair: settled - 1 })
  }
  // A neighbour's hand is not known until its leaves have been measured.
  const unmeasured = [...new Set(wanted.flatMap((item) => (
    mushafFit(item.pair, facing, fits) == null ? facing ? [item.pair, item.pair + 1] : [item.pair] : []
  )))].filter((leaf) => fits[leaf] == null && leaf >= 1 && leaf <= MUSHAF_PAGE_COUNT)
  const nextWarm = wanted.reduce<WarmPage | null>((found, item) => {
    if (found) return found
    const leafFit = mushafFit(item.pair, facing, fits)
    if (leafFit == null) return null
    const key = shotKey(item.page, item.side, leafFit, leafLive(item.page))
    return key && !hasPagePicture(key) ? { page: item.page, side: item.side, fit: leafFit, key } : null
  }, null)
  const [warm, setWarm] = useState<WarmPage | null>(null)
  const [measuring, setMeasuring] = useState<number[]>([])
  const [, setWarmed] = useState(0)
  const nextKey = nextWarm?.key ?? null
  const unmeasuredKey = unmeasured.join(',')
  useEffect(() => {
    if (!still) {
      setWarm(null)
      setMeasuring((current) => (current.length ? [] : current))
      return
    }
    if (!nextWarm && unmeasured.length === 0) return
    return whenIdle(() => {
      if (nextWarm) setWarm(nextWarm)
      else setMeasuring(unmeasured)
    })
    // The next picture and the leaves to measure are named by their keys.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [still, nextKey, unmeasuredKey])
  const kept = useCallback(() => {
    setWarm(null)
    setWarmed((count) => count + 1)
  }, [])
  const warming = still && warm ? (
    <LeafPicture at={warm.side ?? 'single'} shotKey={warm.key} onKept={kept}>
      <MushafLeaf page={warm.page} side={warm.side} fit={warm.fit} style={facing ? versoBox : undefined}
        {...leafProps} live={false} onFit={noFit} still />
    </LeafPicture>
  ) : null

  // Two incoming leaves are measured once, before the leaf lifts; a
  // neighbour's, while nothing is happening.
  const probing = !reset && queue.flight && !motion
    ? [...new Set([
        pair.right,
        pair.left,
        queue.flight.from,
        ...(facing ? [mushafFacingPages(queue.flight.from).left] : []),
      ])]
    : still ? measuring : []
  const probes = probing.some((page) => fits[page] == null) ? (
    <div className="mushaf-fit-probes" inert aria-hidden="true">
      {probing.filter((page) => fits[page] == null).map((page) => (
        <MushafLeaf key={page} page={page} fit={1} {...leafProps} still />
      ))}
    </div>
  ) : null

  // What the dial and the transport read of this leaf, and ask of it.
  const chapterPages = useMemo(
    () => map ? Array.from({ length: 114 }, (_, index) => map.pageOfAyah(index + 1, 1) ?? 1) : [],
    // The page map is one object; [ready] says when its pages arrived.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [map, ready],
  )
  const leafPage = facing ? mushafFacingPages(page).right : page
  useEffect(() => {
    mushafLeaf.publish({
      ready: map != null,
      page: leafPage,
      pageCount: MUSHAF_PAGE_COUNT,
      rightToLeft: !english,
      chapterPages,
      reciting: voicePage != null,
    })
  })
  // A scrub names a chapter; Play on its leaf then starts that chapter.
  const seekSurah = useRef<number | null>(null)
  const leafApi = {
    seek(target: number, surahId?: number) {
      seekSurah.current = surahId ?? null
      handTurn(Math.min(MUSHAF_PAGE_COUNT, Math.max(1, target)))
    },
    label(target: number, surahId?: number): DialLabel | null {
      const rows = map?.pageWords(target) ?? []
      if (rows.length === 0) return null
      const id = surahId != null && rows.some((row) => row.surah_id === surahId) ? surahId : rows[0]!.surah_id
      const ayahs = rows.filter((row) => row.surah_id === id).map((row) => row.ayah_number)
      const chapter = appStore.getSnapshot().surahs.find((surah) => surah.id === id)?.nameTransliteration ?? ''
      return { number: id, chapter, fromAyah: Math.min(...ayahs), toAyah: Math.max(...ayahs) }
    },
    playTarget(): MushafPlayTarget | null {
      const rows = map?.pageWords(leafPage) ?? []
      const scrubbed = seekSurah.current
      seekSurah.current = null
      if (rows.length === 0) return null
      const player = appStore.getSnapshot().player
      const held = player.nowPlaying?.surahId === activeSurahId ? Math.max(1, player.nowPlaying.ayah) : null
      const heldHere = held != null && rows.some((row) => row.surah_id === activeSurahId && row.ayah_number === held)
      if (scrubbed != null && !(heldHere && scrubbed === activeSurahId)) {
        const first = rows.find((row) => row.surah_id === scrubbed)
        if (first) return { surahId: first.surah_id, ayah: first.ayah_number, position: first.position }
      }
      // The leaf a chapter was opened on is that request's: its verse.
      if (manualPage == null && opened === page && openAyah > 0 &&
        rows.some((row) => row.surah_id === activeSurahId && row.ayah_number === openAyah)) {
        return heldHere ? null : { surahId: activeSurahId, ayah: openAyah }
      }
      if (heldHere) return null
      const first = rows[0]!
      return { surahId: first.surah_id, ayah: first.ayah_number, position: first.position }
    },
  }
  const leafApiRef = useRef(leafApi)
  leafApiRef.current = leafApi
  useEffect(() => mushafLeaf.register({
    seek: (target, surahId) => leafApiRef.current.seek(target, surahId),
    label: (target, surahId) => leafApiRef.current.label(target, surahId),
    playTarget: () => leafApiRef.current.playTarget(),
  }), [])

  // A single leaf follows the finger (Android's HorizontalPager): it lifts
  // with the hand, and is turned or put back when the hand lets it go.
  const drag = useRef<{
    id: number
    x0: number
    y0: number
    axis: 'x' | 'y' | null
    to: number
    sign: number
    drive: TurnDrive | null
    width: number
    lastX: number
    lastT: number
    vx: number
  } | null>(null)
  const swallowClick = useRef(false)
  const dragState = { page, turning, flight: queue.flight, fit, fits, reduced, leafProps, versoBox, english }
  const dragNow = useRef(dragState)
  dragNow.current = dragState
  const leafDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (facing || (event.pointerType === 'mouse' && event.button !== 0)) return
    drag.current = {
      id: event.pointerId, x0: event.clientX, y0: event.clientY, axis: null, to: 0, sign: 1, drive: null,
      width: event.currentTarget.clientWidth || window.innerWidth,
      lastX: event.clientX, lastT: event.timeStamp, vx: 0,
    }
  }
  const leafMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const held = drag.current
    if (!held || held.id !== event.pointerId) return
    const dx = event.clientX - held.x0
    const dy = event.clientY - held.y0
    const dt = event.timeStamp - held.lastT
    if (dt > 0) held.vx = (event.clientX - held.lastX) / dt
    held.lastX = event.clientX
    held.lastT = event.timeStamp
    if (!held.axis) {
      if (Math.hypot(dx, dy) < 8) return
      held.axis = Math.abs(dx) > Math.abs(dy) * 1.1 ? 'x' : 'y'
      if (held.axis === 'y') return
      const now = dragNow.current
      // Bound at the right (Arabic), a finger moving right lifts the leaf
      // on; bound at the left (English), a finger moving left does.
      const forward = now.english ? dx < 0 : dx > 0
      const to = now.page + (forward ? 1 : -1)
      if (to < 1 || to > MUSHAF_PAGE_COUNT || now.turning || now.flight) {
        held.axis = 'y'
        return
      }
      held.to = to
      held.sign = Math.sign(dx)
      event.currentTarget.setPointerCapture(event.pointerId)
      if (now.reduced) return
      const drive: TurnDrive = { t: 0, release: null }
      held.drive = drive
      const flight = { from: now.page, to }
      setQueue({ settled: now.page, flight, queued: null })
      setMotion({
        flight, wad: 0, ms: LEAF_TURN_MS,
        fit: mushafFit(to, false, now.fits) ?? now.fit, fromFit: now.fit,
        initiatedAt: performance.now(), props: now.leafProps, box: now.versoBox, chapters: null, drive,
      })
    }
    if (held.axis !== 'x' || !held.drive) return
    // A short pull of resistance first: paper has weight.
    const resist = Math.min(14, held.width * 0.04)
    const travel = Math.max(0, dx * held.sign - resist)
    held.drive.t = Math.min(1, travel / (held.width * 0.9))
  }
  const leafUp = (event: ReactPointerEvent<HTMLDivElement>, cancelled = false) => {
    const held = drag.current
    if (!held || held.id !== event.pointerId) return
    drag.current = null
    if (held.axis !== 'x') return
    swallowClick.current = true
    window.setTimeout(() => { swallowClick.current = false }, 0)
    const dx = (event.clientX - held.x0) * held.sign
    const fling = (held.vx * held.sign * 1000) / held.width
    // Past 15% of the leaf, or flung at 0.30 leaf-widths a second.
    const commit = !cancelled && fling > -0.3 && (dx >= held.width * 0.15 || fling >= 0.3)
    if (held.drive) held.drive.release = commit ? 'commit' : 'cancel'
    if (commit) handTurnRef.current(held.to)
  }

  const returnWay = followPage != null && !followEnabled
    ? mushafReturnWay(facing ? mushafFacingPages(page).right : page, facing ? mushafFacingPages(followPage).right : followPage, !english)
    : null
  const returnPill = returnWay ? (
    <div className="mushaf-return" data-way={returnWay}>
      <ReturnToAyahButton pointUp={false} onClick={() => appStore.setFollowEnabled(true)} />
    </div>
  ) : null

  if (!ready || !runtimeMushafCache) {
    // Blank paper while the page map arrives, as Android's leaf waits.
    return <div className="mushaf-wait" aria-busy="true" />
  }

  if (!facing) {
    // One leaf, bound at its right edge (an English book's at its left).
    // Going on, the old leaf lifts away over the new one; going back, the
    // earlier leaf comes down onto the one being left.
    const under = turning ? Math.max(settled, landing) : settled
    return (
      <div
        className="mushaf-host"
        onPointerDown={leafDown}
        onPointerMove={leafMove}
        onPointerUp={(event) => leafUp(event)}
        onPointerCancel={(event) => leafUp(event, true)}
        onClickCapture={(event) => {
          if (!swallowClick.current) return
          swallowClick.current = false
          event.stopPropagation()
          event.preventDefault()
        }}
      >
        <MushafLeaf page={under} fit={turning && !forward ? motion.fromFit : fit} leafRef={rectoRef} {...leafProps}
          live={leafLive(under)} onFit={turning ? noFit : reportFit} />
        <span className="mushaf-feather mushaf-feather--left" aria-hidden="true" />
        <span className="mushaf-feather mushaf-feather--right" aria-hidden="true" />
        {probes}
        {warming}
        {turning ? (
          <TurningLeaf
            key={`${settled}:${landing}`}
            single
            hinge={english ? 'left' : 'right'}
            dir={forward ? 'on' : 'back'}
            face={air?.face}
            faceKey={air?.faceKey}
            drive={motion.drive}
            onEnd={endTurn}
          />
        ) : null}
        {returnPill}
      </div>
    )
  }

  const now = mushafFacingPages(landing)
  const was = mushafFacingPages(settled)
  // The leaf between the two spreads carries the old spread's inner page on
  // its face and the new spread's on its back. What it uncovers is already
  // the new page; what it has yet to cover is still the old one.
  const rectoPage = turning && forward ? was.right : now.right
  const versoPage = turning && !forward ? was.left : now.left
  const live = leafLive(rectoPage)
  return (
    <WordMotionProvider>
      <MushafLeaf page={rectoPage} side="recto" fit={turning && forward ? motion.fromFit : fit} leafRef={rectoRef} {...leafProps}
        live={live} onFit={turning ? noFit : reportFit} />
      {probes}
      <button
        type="button"
        className="mushaf-turn mushaf-turn--back"
        aria-label="Previous pages"
        disabled={now.right <= 1}
        onClick={() => turn(-1)}
      >
        <span aria-hidden="true">›</span>
      </button>
      {versoSlot
        ? createPortal(
            <>
              <MushafLeaf page={versoPage} side="verso" fit={turning && !forward ? motion.fromFit : fit} style={versoBox} {...leafProps}
                live={live} onFit={turning ? noFit : reportFit} />
              <button
                type="button"
                className="mushaf-turn mushaf-turn--forward"
                aria-label="Next pages"
                disabled={now.left >= MUSHAF_PAGE_COUNT}
                onClick={() => turn(1)}
              >
                <span aria-hidden="true">‹</span>
              </button>
            </>,
            versoSlot,
          )
        : null}
      {warming && turnSlot ? createPortal(warming, turnSlot) : null}
      {turning && turnSlot
        ? createPortal(
            <TurningLeaf
              key={`${settled}:${landing}`}
              hinge={forward ? 'right' : 'left'}
              dir={forward ? 'on' : 'back'}
              wad={motion.wad}
              ms={motion.ms}
              face={air?.face}
              faceKey={air?.faceKey}
              faceFrom={air?.faceFrom}
              back={air?.back}
              backKey={air?.backKey}
              backFrom={air?.backFrom}
              onEnd={endTurn}
            />,
            turnSlot,
          )
        : null}
      {returnPill}
    </WordMotionProvider>
  )
}

function noFit() {}

/**
 * A verse mark plays from its verse (Android `onMushafAyahClick`): another
 * chapter's mark loads that chapter. Gathering keeps its own meaning.
 */
function playFromMark(surahId: number, ayah: number) {
  if (appStore.getSnapshot().gathering) {
    appStore.onMarkTap(surahId, ayah)
    return
  }
  appStore.setFollowEnabled(true)
  playMushafTarget({ surahId, ayah })
}

/**
 * The opening basmalah: tapped, it recites; recited, it is washed in from
 * the right like any line of scripture (Android `MushafBasmalahLine`).
 */
function MushafBasmalah({ surahId, washing, waiting, speed }: {
  surahId: number
  washing: boolean
  waiting: boolean
  speed: number
}) {
  const segments = washing ? appStore.basmalahSegments() : null
  const spanMs = segments && segments.length
    ? Math.max(400, segments[segments.length - 1]!.endMs - segments[0]!.startMs)
    : 2_400
  return (
    <button
      type="button"
      className="mushaf-basmalah"
      lang="ar"
      data-state={washing ? InkState.Active : waiting ? InkState.Upcoming : InkState.Plain}
      aria-label="Recite the basmalah"
      style={{ ['--basmalah-ms' as string]: `${Math.round(spanMs / Math.max(0.1, speed))}ms` }}
      onClick={(event) => {
        event.stopPropagation()
        if (appStore.getSnapshot().gathering) return
        appStore.setFollowEnabled(true)
        playMushafTarget({ surahId, ayah: 1 })
      }}
    >
      <span>{BASMALAH_UTHMANI}</span>
    </button>
  )
}

/** Android `leafDescription`: page, chapter and juzʾ of the leaf. */
function leafDescription(page: number, chapter: string | null, rows: readonly { surah_id: number; ayah_number: number }[]) {
  const first = rows[0]
  const juz = first ? `, Juz ${juzOf(first.surah_id, first.ayah_number)}` : ''
  return `Mushaf page ${page}${chapter ? `, ${chapter}` : ''}${juz}`
}

/** Running head with the folio in-head: Western figure at the left, the chapter
    centred, the Arabic-Indic figure at the right; one script puts its figure
    at the left and the chapter at the right. Android `MushafRunningHead`. */
function MushafHead({
  page,
  script,
  chapter,
}: {
  page: number
  script: PageNumberScript
  chapter: string
}) {
  const figures = mushafHeadFigures(page, script, chapter)
  return (
    <div className="mushaf-head" data-both={figures.right != null || undefined}>
      <span
        className="mushaf-head-figure"
        lang={figures.leftLang ?? undefined}
      >
        {figures.left}
      </span>
      <span className="mushaf-head-chapter">{figures.center}</span>
      {figures.right != null ? (
        <span className="mushaf-head-figure" lang="ar">
          {figures.right}
        </span>
      ) : null}
    </div>
  )
}

/** One printed page: running head and fifteen lines (or its translation). */
function MushafLeaf({
  page,
  fitRevision,
  facing,
  side,
  style,
  leafRef,
  activeSurahId,
  activeAyah,
  english,
  pageNumberScript,
  glyphWiden,
  ink,
  live = false,
  still = false,
  onPlayWord,
  onHoldWord,
  onTurn,
  fit,
  onFit,
}: {
  page: number
  fitRevision: number
  /** Extra chapter furniture sets the same row pitch on both leaves. */
  facing: boolean
  /** The hand both facing leaves are set in: the tighter page's fit. */
  fit: number
  onFit: (page: number, fit: number) => void
  /** Which page of a desktop spread this leaf is; absent on a single leaf. */
  side?: 'recto' | 'verso'
  style?: CSSProperties
  leafRef?: Ref<HTMLDivElement>
  activeSurahId: number
  /** The English leaf's verse highlight; Arabic words take [ink] instead. */
  activeAyah: number | null
  english: boolean
  pageNumberScript: PageNumberScript
  /** Most a loose line's letters may be widened, as a fraction (0.06 = 6%). */
  glyphWiden: number
  ink: MushafInk
  /** This leaf (or the one facing it) carries the voice: see [mushafTokenInk]. */
  live?: boolean
  /** A picture of the page — in the air, or a fit probe. Ink stands as it is,
   * and no word starts a wash of its own. */
  still?: boolean
  onPlayWord: (surahId: number, ayah: number, position: number) => void
  onHoldWord: (surahId: number, ayah: number, position: number, side?: 'recto' | 'verso') => void
  /** Facing leaves turn on a swipe; a single leaf follows the finger instead. */
  onTurn?: (delta: number) => void
}) {
  const drag = useRef<{ x: number; y: number } | null>(null)
  const linesRef = useRef<HTMLDivElement>(null)
  const blockRef = useRef<HTMLDivElement>(null)
  // Words are memoised on their ink, so they reach the handlers through refs
  // and a stale word never plays with an old gathering state.
  const playRef = useRef(onPlayWord)
  playRef.current = onPlayWord
  const holdRef = useRef(onHoldWord)
  holdRef.current = onHoldWord
  // The two opening leaves carry a few short lines, set in the middle of the
  // well; every other leaf hangs its fifteen lines from the head.
  const opening = page <= MUSHAF_OPENING_PAGES

  // Hafs stands in for the page's own face, so a printed line sets wider or
  // narrower than the page. Measure every line at full size, word spaces at
  // their minimum, and report how the widest compares with the page.
  useLayoutEffect(() => {
    const lines = linesRef.current
    // A leaf in the air is a picture at the size already found. Measuring
    // it again forces a layout as the turn starts.
    if (onFit === noFit) return
    if (english) { onFit(page, 1); return }
    if (!lines) return
    let disposed = false
    const measure = () => {
      if (disposed) return
      const leaf = lines.closest('.mushaf')
      if (!(leaf instanceof HTMLElement)) return
      // Widths are read at the scale already applied and divided back out:
      // every length in a line is in em, so they scale with the type exactly.
      // Rewriting the scale to measure at full size re-laid the block and
      // let the measurement chase its own result.
      const scale = Number(lines.style.getPropertyValue('--mushaf-fit')) || 1
      const textLines = Array.from(lines.querySelectorAll<HTMLElement>('.mushaf-line'))
      const gap = parseFloat(getComputedStyle(lines).fontSize) * MUSHAF_MIN_GAP
      // A line already set narrow or wide carries its scale into every
      // item's box; divide it back out. The widest line may be condensed,
      // so it is measured as it would be set at the limit.
      const widest = opening
        ? 0
        : Math.max(0, ...textLines.map((line) => {
            const set = Number((line as HTMLElement).style.getPropertyValue('--line-widen')) || 1
            return naturalLineWidth(
              Array.from(line.children, (item) => item.getBoundingClientRect().width / set),
              gap,
              MUSHAF_MAX_CONDENSE,
            )
          })) / scale
      const box = getComputedStyle(leaf)
      const available = leaf.clientWidth - parseFloat(box.paddingLeft) - parseFloat(box.paddingRight)
      // The grid has already reserved the running head and real controls.
      // This well belongs entirely to the printed slots, including banners.
      const block = lines.parentElement
      const slots = Number(lines.style.getPropertyValue('--mushaf-slots')) || MUSHAF_LINES_PER_PAGE
      const pitch = block ? block.clientHeight / slots : 0
      const baseEm = parseFloat(getComputedStyle(lines).fontSize) / scale
      const heightFit = pitch > 0 && baseEm > 0 ? pitch / (MUSHAF_MIN_LEADING * baseEm) : 1
      onFit(page, mushafLeafFit(available, widest, heightFit))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(lines)
    // The block is sized by its own lines, so it does not change when the
    // page around it does. Watch the page as well.
    const leaf = lines.closest('.mushaf')
    if (leaf) observer.observe(leaf)
    void document.fonts?.ready.then(measure)
    return () => { disposed = true; observer.disconnect() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, english, side, facing, fitRevision, onFit === noFit])

  // Once the hand and the measure are settled, set each line: widen the
  // letters of a loose line toward the target word space, and centre a line
  // too short to justify. Written straight to the lines — it is derived
  // from layout, and cloned as it stands into a turning leaf.
  useLayoutEffect(() => {
    const lines = linesRef.current
    const block = blockRef.current
    if (!lines || english) {
      block?.style.removeProperty('width')
      return
    }
    const em = parseFloat(getComputedStyle(lines).fontSize)
    const textLines = Array.from(lines.querySelectorAll<HTMLElement>('.mushaf-line'))
    for (const line of textLines) {
      line.removeAttribute('data-widen')
      line.removeAttribute('data-short')
      line.style.removeProperty('--line-widen')
    }
    if (opening) block?.style.removeProperty('width')
    // Read every line before writing any, so the page is laid out once.
    const set = textLines.map((line) => ({
      ink: Array.from(line.children).reduce((sum, item) => sum + item.getBoundingClientRect().width, 0),
      gaps: line.children.length - 1,
      markJoins: Array.from(line.children).slice(1).map((item, index) =>
        item.matches('.mushaf-mark') || line.children[index]!.matches('.mushaf-mark')),
    }))
    const leaf = lines.closest('.mushaf')
    if (!opening && block && leaf instanceof HTMLElement) {
      const box = getComputedStyle(leaf)
      const available = leaf.clientWidth - parseFloat(box.paddingLeft) - parseFloat(box.paddingRight)
      block.style.width = `${mushafMeasure(set, em, available)}px`
    }
    // The block's floor (min-width) may hold it wider than asked.
    const measure = lines.clientWidth
    const settings = set.map(({ ink, gaps }) => opening
      ? { widen: 1, short: true } : solveLine(ink, gaps, measure, em, glyphWiden))
    settings.forEach((setting, index) => {
      const line = textLines[index]
      if (!line) return
      const { wordGap, markGap } = mushafLineGaps(
        set[index]!.markJoins, em,
        setting.short ? undefined : measure / setting.widen - set[index]!.ink,
        opening ? MUSHAF_MIN_GAP : undefined,
      )
      line.style.setProperty('--word-gap', `${wordGap.toFixed(4)}px`)
      line.style.setProperty('--mark-gap', `${markGap.toFixed(4)}px`)
      if (setting.short) line.setAttribute('data-short', '')
      else if (Math.abs(setting.widen - 1) > 0.001) {
        line.setAttribute('data-widen', '')
        line.style.setProperty('--line-widen', setting.widen.toFixed(4))
      }
    })
  }, [page, english, side, fit, fitRevision, glyphWiden, opening])

  if (!runtimeMushafCache) return null
  const rows = runtimeMushafCache.pageWords(page) ?? []
  const { leaf, headSurah, englishAyahs, lastPositions, verseWords } = mushafLeafModel(page, rows, QuranRepository.surahContent)
  const peerPage = page % 2 === 1 ? page + 1 : page - 1
  const peerRows = facing ? runtimeMushafCache.pageWords(peerPage) : null
  const peerSlots = peerRows ? mushafLeafModel(peerPage, peerRows, QuranRepository.surahContent).leaf.lines.length : 0
  // The two opening leaves carry a few short lines, set in the middle of the
  // well; every other leaf hangs its fifteen lines from the head.
  const lines = opening ? leaf.lines.filter((line) => line.tokens.length > 0 || line.chapter || line.basmalah) : leaf.lines
  const firstToken = lines.find((line) => line.tokens.length > 0)?.tokens[0]
  const isLive = live && !english
  const tuning = getTuning()

  return (
    <div
      className="mushaf"
      role="group"
      aria-roledescription="page"
      aria-label={leafDescription(page, headSurah?.nameTransliteration ?? null, rows)}
      dir={english ? 'ltr' : 'rtl'}
      data-side={side}
      data-word-group=""
      style={style}
      ref={leafRef}
      onPointerDown={(event) => {
        drag.current = { x: event.clientX, y: event.clientY }
      }}
      onPointerUp={(event) => {
        const start = drag.current
        drag.current = null
        if (!start) return
        const dx = event.clientX - start.x
        const dy = event.clientY - start.y
        if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy)) return
        // The next leaf is to the left, so a finger moving right turns forward.
        onTurn?.(dx > 0 ? 1 : -1)
      }}
      onPointerCancel={() => {
        drag.current = null
      }}
    >
      <MushafHead
        page={page}
        script={english ? 'english' : pageNumberScript}
        chapter={headSurah?.nameTransliteration ?? ''}
      />
      <div className="mushaf-block" ref={blockRef}>
      {english ? (
        <div className="mushaf-english">
          {englishAyahs.map((item) => {
            const active = item.surahId === activeSurahId && item.ayah === activeAyah
            return (
              <Fragment key={`${item.surahId}:${item.ayah}`}>
              {item.ayah === 1 ? (
                <div className="mushaf-english-chapter">
                  <MushafChapterBand surah={QuranRepository.surahContent(item.surahId).surah} latin />
                </div>
              ) : null}
              <p
                className="mushaf-english-ayah"
                data-active={active || undefined}
              >
                {/* A span, not a button: a button is one box and cannot break
                    across lines, which pushed the verse number onto a line
                    of its own beneath every verse. */}
                <span
                  role="button"
                  tabIndex={0}
                  className="mushaf-english-text"
                  onClick={() => onPlayWord(item.surahId, item.ayah, 1)}
                  onKeyDown={(event) => {
                    if (event.key !== 'Enter' && event.key !== ' ') return
                    event.preventDefault()
                    onPlayWord(item.surahId, item.ayah, 1)
                  }}
                >
                  {suppliedWords(item.translation ?? '')}
                </span>
                {/* No-break space: the number never starts a line alone. */}
                {'\u00A0'}
                <button
                  type="button"
                  className="mushaf-mark"
                  aria-label={`Recite ayah ${item.ayah}`}
                  onClick={(event) => {
                    event.stopPropagation()
                    playFromMark(item.surahId, item.ayah)
                  }}
                >
                  {formatAyahNumberMark(item.ayah, false)}
                </button>
              </p>
              </Fragment>
            )
          })}
        </div>
      ) : (
        <WordMotionProvider>
        <div
          className="mushaf-lines"
          data-opening={opening || undefined}
          ref={linesRef}
          style={{
            ['--mushaf-fit' as string]: String(fit),
            ['--mushaf-slots' as string]: String(Math.max(MUSHAF_LINES_PER_PAGE, leaf.lines.length, peerSlots)),
            ['--recess-ms' as string]: `${tuning.recessMs}ms`,
            ['--ayah-mark-fade-ms' as string]: `${tuning.ayahMarkFadeMs}ms`,
            ['--upcoming-alpha' as string]: String(tuning.upcomingAlpha),
          }}
        >
          {lines.map((line) => line.chapter ? (
            <div className="mushaf-chapter-slot" key={line.number}>
              <MushafChapterBand surah={QuranRepository.surahContent(line.chapter).surah} fit={fit} />
            </div>
          ) : line.basmalah ? (
            <MushafBasmalah
              key={line.number}
              surahId={line.basmalah}
              washing={isLive && !still && line.basmalah === activeSurahId && ink.inkAyah === BASMALAH_PLAYLIST_AYAH}
              waiting={isLive && line.basmalah === activeSurahId && ink.inkAyah == null}
              speed={ink.speed}
            />
          ) : (
            <p key={line.number} className="mushaf-line" lang="ar">
              {line.tokens.map((token) => {
                const endsAyah = mushafTokenEndsAyah(
                  token.position,
                  lastPositions.get(`${token.surahId}:${token.ayah}`) ?? 0,
                )
                const voiced = mushafTokenInk(token, activeSurahId, ink, isLive)
                // A picture never starts a wash: the word in hand stands inked.
                const wordInk = still && voiced.state === InkState.Active
                  ? MUSHAF_STILL_INK
                  : voiced
                const owner = wordInk.state === InkState.Active
                const { surahId, ayah, position } = token
                // Word and mark are separate items of the line, so the
                // mark-specific spacing can fall on both sides of a mark.
                return (
                  <Fragment key={`${surahId}:${ayah}:${position}`}>
                    {still ? (
                      // A picture is drawn from this (pagePicture): the
                      // word alone, and its paper while it waits.
                      <span className="hafs-word" data-state={wordInk.state} lang="ar">
                        <span className="hafs-shell">
                          <span className="word-ink-slot">
                            <span className="hafs-glyph">{token.arabic}</span>
                          </span>
                          {wordInk.state === InkState.Upcoming ? (
                            <span className="ink-paper-cover" style={{ opacity: 1 - tuning.upcomingAlpha }} />
                          ) : null}
                        </span>
                      </span>
                    ) : (
                      <MushafWord
                        token={token}
                        motion={{ key: `${surahId}:${ayah}:${position}`, timing: owner ? ink.activeWord : null,
                          isAyahFinal: position === lastPositions.get(`${surahId}:${ayah}`),
                          previousArabic: verseWords.get(`${surahId}:${ayah}`)?.find(w => w.position === position - 1)?.arabic,
                          nextArabic: verseWords.get(`${surahId}:${ayah}`)?.find(w => w.position === position + 1)?.arabic,
                          nextIsAyahFinal: position + 1 === lastPositions.get(`${surahId}:${ayah}`) }}
                        tabIndex={token === firstToken ? 0 : -1}
                        ink={wordInk}
                        sweepMs={owner ? InkEngine.sweepMs(ink.activeWord, ink.speed) : null}
                        activation={owner ? (ink.activeWord?.activation ?? 0) : 0}
                        onPlay={() => playRef.current(surahId, ayah, position)}
                        onHold={() => holdRef.current(surahId, ayah, position, side)}
                      />
                    )}
                    {endsAyah ? (
                      <button
                        type="button"
                        className="mushaf-mark"
                        data-state={mushafMarkWaits(token, activeSurahId, ink, isLive) ? InkState.Upcoming : InkState.Plain}
                        aria-label={`Recite ayah ${token.ayah}`}
                        onClick={(event) => {
                          event.stopPropagation()
                          playFromMark(token.surahId, token.ayah)
                        }}
                      >
                        {formatAyahNumberMark(token.ayah, true)}
                      </button>
                    ) : null}
                  </Fragment>
                )
              })}
            </p>
          ))}
        </div>
        </WordMotionProvider>
      )}
      </div>
    </div>
  )
}

/**
 * A word of the leaf, inked by the scroll reader's own Hafs renderer: full
 * glyph under a paper cover, the directional wash with its faded leading
 * edge on entry, glint and orange repeat. Memoised on its ink, since a leaf
 * re-renders on every word the voice reaches and carries some 150 words.
 */
const MushafWord = memo(function MushafWord({
  token,
  motion,
  ink,
  sweepMs,
  activation,
  onPlay,
  onHold,
  tabIndex,
}: {
  token: MushafToken
  motion: WordMotion
  ink: InkWord
  sweepMs: number | null
  activation: number
  tabIndex: number
  onPlay: () => void
  onHold: () => void
}) {
  const word = useMemo(
    () => ({ position: token.position, arabic: token.arabic, translation: '', transliteration: '' }),
    [token.position, token.arabic],
  )
  return (
    <HafsWord
      word={word}
      ink={ink}
      sweepMs={sweepMs}
      motion={motion}
      activation={activation}
      tabIndex={tabIndex}
      onPlay={onPlay}
      onHold={onHold}
      onContextMenu={(event) => {
        event.preventDefault()
        onHold()
      }}
    />
  )
}, (prev, next) =>
  prev.token === next.token &&
  prev.tabIndex === next.tabIndex &&
  prev.ink.state === next.ink.state &&
  prev.ink.repeat === next.ink.repeat &&
  prev.sweepMs === next.sweepMs &&
  prev.activation === next.activation &&
  prev.motion.timing === next.motion.timing &&
  prev.motion.previousArabic === next.motion.previousArabic &&
  prev.motion.nextArabic === next.motion.nextArabic &&
  prev.motion.isAyahFinal === next.motion.isAyahFinal)
