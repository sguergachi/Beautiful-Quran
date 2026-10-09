import { useEffect, useRef } from 'react'
import { appStore } from '../store/appStore'
import { player } from '../playback/player'
import type { AyahRef } from '../share/gather'
import { parseVerseHash, verseHash } from './verseRoute'

/** Explicit opens add history; settled scroll/page movement updates the current entry. */
export function useVerseRoute(ready: boolean, place: AyahRef | null, revision: number, onLinkedOpen: () => void) {
  const lastRevision = useRef(revision)
  const lastHash = useRef(location.hash)
  const linkedOpen = useRef(onLinkedOpen)
  linkedOpen.current = onLinkedOpen
  useEffect(() => {
    if (!ready) return
    const read = () => {
      const state = appStore.getSnapshot()
      const target = parseVerseHash(location.hash, (id) => state.surahs.find((surah) => surah.id === id)?.ayahCount)
      if (!target && location.hash !== '') return
      player.pause()
      if (state.gathering) appStore.exitGather()
      if (target) {
        appStore.openReading(target.surahId, target.ayah)
        lastRevision.current = appStore.getSnapshot().readerOpenRevision
        linkedOpen.current()
      } else if (state.content) appStore.setSheet('home')
    }
    read()
    // Back/Forward emits popstate; a hand-edited hash emits hashchange too.
    const changed = () => {
      if (location.hash === lastHash.current) return
      lastHash.current = location.hash
      read()
    }
    window.addEventListener('popstate', changed)
    window.addEventListener('hashchange', changed)
    return () => {
      window.removeEventListener('popstate', changed)
      window.removeEventListener('hashchange', changed)
    }
  }, [ready])

  useEffect(() => {
    if (!ready || !place || location.hash.startsWith('#lab')) return
    const hash = verseHash(place)
    if (location.hash !== hash) {
      if (revision !== lastRevision.current) history.pushState(null, '', hash)
      else history.replaceState(null, '', hash)
    }
    lastRevision.current = revision
    lastHash.current = location.hash
  }, [ready, place, revision])
}
