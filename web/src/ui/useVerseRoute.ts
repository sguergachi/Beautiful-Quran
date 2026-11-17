import { useEffect, useRef } from 'react'
import { appStore } from '../store/appStore'
import { player } from '../playback/player'
import type { AyahRef } from '../share/gather'
import { parseVerseHash, verseHash } from './verseRoute'
import { COVER_LAYER, READER_LAYER } from './paper/stack'

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
      // History navigation leaves the word sheet and must not resume its old
      // playback snapshot over the arriving page.
      if (state.rootViewer) appStore.closeRootViewer(false)
      if (state.gathering) appStore.exitGather()
      // The chapter already open is only uncovered: the voice reading it
      // carries on, as Android's Back never stops it.
      if (target && state.content?.surah.id === target.surahId) {
        appStore.revealLayer(READER_LAYER)
        linkedOpen.current()
        return
      }
      player.pause()
      if (target) {
        appStore.openReading(target.surahId, target.ayah)
        lastRevision.current = appStore.getSnapshot().readerOpenRevision
        linkedOpen.current()
      } else if (state.content) appStore.setSheet('home')
    }
    read()
    // Back/Forward emits popstate; a hand-edited hash emits hashchange too.
    const changed = (event: Event) => {
      if (location.hash === lastHash.current) return
      lastHash.current = location.hash
      // Back from a sheet over Chapters peels that sheet (Android Back),
      // whatever verse the previous entry names.
      const state = appStore.getSnapshot()
      if (event.type === 'popstate' && (state.stackLayer > COVER_LAYER || state.rootViewer)) {
        appStore.goBack()
        return
      }
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
