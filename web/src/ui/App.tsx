import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { appStore, shallowEqual, useAppSelector } from '../store/appStore'
import { hasReaderOpen } from './paper/stack'
import { HomeScreen } from './home/HomeScreen'
import { BookmarksScreen } from './bookmarks/BookmarksScreen'
import { ReaderScreen } from './reader/ReaderScreen'
import { SettingsScreen } from './settings/SettingsScreen'
import { EntranceCover } from './entrance/EntranceCover'
import { BOOKMARKS_LAYER, COVER_LAYER } from './paper/stack'
import { OrnamentsLab } from './lab/OrnamentsLab'
import { syncSheetMotion } from './paper/sheetMotion'

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
      bookmarks: s.bookmarks,
      settings: s.settings,
    }),
    shallowEqual,
  )
  // Once per page load — mirrors Android rememberSaveable entranceDone.
  const [entranceDone, setEntranceDone] = useState(false)
  const isLab = useLabRoute()
  const swipeStart = useRef<{ x: number; y: number; pointerId: number } | null>(null)
  const stack = state.stackLayer
  const hasReader = hasReaderOpen(state.content, state.sheet)

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

  useLayoutEffect(() => {
    syncSheetMotion({
      hasReader,
      stack,
      openSurahId: state.content?.surah.id ?? 0,
    })
  }, [hasReader, stack, state.content?.surah.id])

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
    if (stack !== COVER_LAYER && stack !== BOOKMARKS_LAYER) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    swipeStart.current = { x: event.clientX, y: event.clientY, pointerId: event.pointerId }
  }

  const finishBookmarkSwipe = (event: ReactPointerEvent<HTMLDivElement>) => {
    const start = swipeStart.current
    swipeStart.current = null
    if (!start || start.pointerId !== event.pointerId) return
    const dx = event.clientX - start.x
    const dy = event.clientY - start.y
    if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.35) return
    if (stack === COVER_LAYER && dx > 0 && state.bookmarks.length > 0) {
      appStore.revealLayer(BOOKMARKS_LAYER)
    } else if (stack === BOOKMARKS_LAYER && dx < 0) {
      appStore.revealLayer(COVER_LAYER)
    }
  }

  return (
    <div
      className="app-shell"
      data-stack={stack}
      data-has-reader={hasReader}
      data-booting={showStack ? undefined : 'true'}
      onPointerDown={beginBookmarkSwipe}
      onPointerUp={finishBookmarkSwipe}
      onPointerCancel={() => { swipeStart.current = null }}
    >
      {showStack && (
        <>
          <BookmarksScreen stackLayer={stack} />
          <HomeScreen stackLayer={stack} />
          {/* Chapter boundaries get fresh focus/rail geometry. Carrying the
              previous chapter's dial state into the first peel frame makes
              the rail visibly jump before the initial focus settles. */}
          <ReaderScreen
            key={state.content?.surah.id ?? 'empty-reader'}
            stackLayer={stack}
          />
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
