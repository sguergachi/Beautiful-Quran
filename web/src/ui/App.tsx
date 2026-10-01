import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { appStore, shallowEqual, useAppSelector } from '../store/appStore'
import { hasReaderOpen } from './paper/stack'
import { HomeScreen } from './home/HomeScreen'
import { BookmarksScreen } from './bookmarks/BookmarksScreen'
import { ReaderScreen } from './reader/ReaderScreen'
import { SettingsScreen } from './settings/SettingsScreen'
import { EntranceCover } from './entrance/EntranceCover'
import { BOOKMARKS_LAYER, COVER_LAYER, READER_LAYER, type StackLayer } from './paper/stack'
import { OrnamentsLab } from './lab/OrnamentsLab'
import { bookmarkSwipeDestination, spreadLayers, useBookSpread } from './paper/bookSpread'
import { BookSpread } from './paper/BookSpread'
import { unlockPageTurnSounds } from './paper/pageTurnSounds'

/** True while the URL hash routes to the Ornaments Lab (`#lab`). */
function useLabRoute(): boolean {
  const [isLab, setIsLab] = useState(() => location.hash.startsWith('#lab'))
  useEffect(() => {
    const onHash = () => setIsLab(location.hash.startsWith('#lab'))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  return isLab
}

export function resolveTheme(mode: string): string {
  if (mode === 'light') return 'light'
  if (mode === 'dark') return 'dark'
  if (mode === 'royal_green') return 'royal_green'
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches
  return dark ? 'dark' : 'light'
}

function retryBoot() {
  void (async () => {
    try {
      if ('serviceWorker' in navigator) {
        const regs = await navigator.serviceWorker.getRegistrations()
        await Promise.all(regs.map((r) => r.unregister()))
      }
      if (window.caches) {
        const keys = await caches.keys()
        await Promise.all(keys.map((k) => caches.delete(k)))
      }
    } catch {
      /* still reload */
    }
    location.reload()
  })()
}

export function App() {
  // Shell only — ignore karaoke word ticks so the whole paper stack does not
  // reconcile on every active-word boundary.
  const state = useAppSelector(
    (s) => ({
      ready: s.ready,
      error: s.error,
      loadLabel: s.loadLabel,
      loadProgress: s.loadProgress,
      stackLayer: s.stackLayer,
      sheet: s.sheet,
      content: s.content,
      readerOpenRevision: s.readerOpenRevision,
      bookmarks: s.bookmarks,
      settings: s.settings,
    }),
    shallowEqual,
  )
  // Once per page load — mirrors Android rememberSaveable entranceDone.
  const [entranceDone, setEntranceDone] = useState(false)
  const isLab = useLabRoute()
  const swipeStart = useRef<{ x: number; y: number; pointerId: number; layer: StackLayer } | null>(null)
  const stack = state.stackLayer
  const hasReader = hasReaderOpen(state.content, state.sheet)
  const spread = useBookSpread()
  // Facing leaves: Mushaf layout in a spread, once a chapter is open.
  const leaves =
    spread && state.content != null && state.settings.readingLayout === 'mushaf'
  const pageLayers = spreadLayers(spread, stack, state.content != null, leaves)

  useEffect(() => {
    void appStore.init()
  }, [])

  useEffect(() => {
    const resolved = resolveTheme(state.settings.themeMode)
    if (resolved === 'light') document.documentElement.removeAttribute('data-theme')
    else document.documentElement.setAttribute('data-theme', resolved)
    if (state.settings.colorSystem === 'legacy') {
      document.documentElement.dataset.colors = 'legacy'
    } else {
      delete document.documentElement.dataset.colors
    }

    // Cover owns theme-color while the leather is up.
    if (!entranceDone) return
    const meta = document.querySelector('meta[name="theme-color"]')
    if (meta) {
      meta.setAttribute(
        'content',
        resolved === 'light' ? '#FAF3E8' : resolved === 'royal_green' ? '#062C24' : state.settings.colorSystem === 'legacy' ? '#0A0B0C' : '#0C0B09',
      )
    }
  }, [state.settings.themeMode, state.settings.colorSystem, entranceDone])

  // In a spread the chapter list stays on screen, so the row that opened a
  // chapter would keep keyboard focus and swallow the reader's keys (Space,
  // arrows). Opening hands the keyboard to the page being read.
  const openSurahId = state.content?.surah.id
  useEffect(() => {
    if (!spread || openSurahId == null) return
    const focused = document.activeElement
    if (focused instanceof HTMLElement && focused.classList.contains('surah-row')) {
      focused.blur()
    }
  }, [spread, openSurahId, state.readerOpenRevision])

  useEffect(() => {
    window.addEventListener('pointerdown', unlockPageTurnSounds, true)
    window.addEventListener('keydown', unlockPageTurnSounds, true)
    return () => {
      window.removeEventListener('pointerdown', unlockPageTurnSounds, true)
      window.removeEventListener('keydown', unlockPageTurnSounds, true)
    }
  }, [])

  // Escape peels one sheet back through the paper stack (cover handles its own).
  useEffect(() => {
    if (!entranceDone) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      appStore.goBack()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [entranceDone])

  // The Ornaments Lab is a standalone dev tool — it needs no database, so
  // it renders over everything the moment the hash routes to it.
  if (isLab) return <OrnamentsLab />

  // The cover *is* the loading screen — show the shell underneath only once
  // the book is ready so the open reveals chapters, not an empty page.
  const showStack = state.ready

  const beginBookmarkSwipe = (event: ReactPointerEvent<HTMLDivElement>) => {
    const sheet = (event.target as Element).closest('.sheet')
    const layer = sheet?.getAttribute('data-name') === 'home' ? pageLayers.home
      : sheet?.getAttribute('data-name') === 'bookmarks' ? stack : null
    if (layer !== COVER_LAYER && layer !== BOOKMARKS_LAYER) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    swipeStart.current = { x: event.clientX, y: event.clientY, pointerId: event.pointerId, layer }
  }

  const finishBookmarkSwipe = (event: ReactPointerEvent<HTMLDivElement>) => {
    const start = swipeStart.current
    swipeStart.current = null
    if (!start || start.pointerId !== event.pointerId) return
    const dx = event.clientX - start.x
    const dy = event.clientY - start.y
    if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.35) return
    const destination = bookmarkSwipeDestination(start.layer, dx, state.bookmarks.length > 0)
    if (destination != null) appStore.revealLayer(destination)
  }

  return (
    <div
      className="app-shell"
      data-stack={stack}
      data-has-reader={hasReader}
      data-spread={spread ? 'true' : undefined}
      data-leaves={leaves ? 'true' : undefined}
      data-booting={showStack ? undefined : 'true'}
      onPointerDown={beginBookmarkSwipe}
      onPointerUp={finishBookmarkSwipe}
      onPointerCancel={() => { swipeStart.current = null }}
    >
      {showStack && (
        <>
          {/* Chapter boundaries get fresh focus/rail geometry. Carrying the
              previous chapter's dial state into the first peel frame makes
              the rail visibly jump before the initial focus settles. */}
          <ReaderScreen
            key={state.content?.surah.id ?? 'empty-reader'}
            stackLayer={pageLayers.reader}
          />
          {spread ? <BookSpread titlePage={state.content == null} versoCovered={!leaves || stack !== READER_LAYER} /> : null}
          <BookmarksScreen stackLayer={stack} />
          <HomeScreen stackLayer={pageLayers.home} />
          <SettingsScreen stackLayer={stack} hasReader={hasReader} />
        </>
      )}
      {!entranceDone && (
        <EntranceCover
          ready={state.ready}
          loadLabel={state.loadLabel}
          loadProgress={state.loadProgress}
          error={state.error}
          onRetry={state.error ? retryBoot : undefined}
          onFinished={() => setEntranceDone(true)}
        />
      )}
    </div>
  )
}
