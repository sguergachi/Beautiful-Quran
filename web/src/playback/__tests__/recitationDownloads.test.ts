import { describe, expect, it } from 'vitest'
import { audioUrl, type Reciter, type Surah } from '../../data/models'
import {
  buildReciterDownloads,
  chapterActionLabel,
  chapterAudioUrls,
  chapterFactLine,
  chapterOffersDelete,
  chapterProgressFactLine,
  downloadPercent,
  formatUsage,
  keepsSharedBasmalahAfterDeleting,
  parseEveryayahUrl,
  reciterDownloadLabel,
  reciterHeaderAction,
} from '../recitationDownloads'

const reciter: Reciter = { id: 1, slug: 'Alafasy_128kbps', name: 'Mishary', style: 'Murattal', hasTimings: true }
const fatihah: Surah = { id: 1, nameArabic: '', nameTransliteration: 'Al-Fatihah', nameTranslation: '', revelationPlace: '', ayahCount: 7 }
const ikhlas: Surah = { id: 112, nameArabic: '', nameTransliteration: 'Al-Ikhlas', nameTranslation: '', revelationPlace: '', ayahCount: 4 }
const MB = 1024 * 1024

describe('recitation download model (Android RecitationDownloads parity)', () => {
  it('formats usage as stored then cached', () => {
    expect(formatUsage({ listenBytes: 0, keepBytes: 0 })).toBe('0 MB stored · 0 MB cached')
    expect(formatUsage({ listenBytes: 3 * MB, keepBytes: 10 * MB })).toBe('13 MB stored · 3 MB cached')
    expect(formatUsage({ listenBytes: 10, keepBytes: 0 })).toBe('< 1 MB stored · < 1 MB cached')
  })

  it('parses everyayah urls', () => {
    expect(parseEveryayahUrl(audioUrl(reciter, 112, 3))).toEqual({ slug: 'Alafasy_128kbps', surah: 112, ayah: 3 })
    expect(parseEveryayahUrl('https://example.com/a.mp3')).toBeNull()
  })

  it('plays the basmalah preface before every chapter but Al-Fatihah and At-Tawbah', () => {
    expect(chapterAudioUrls(reciter, ikhlas)).toHaveLength(5)
    expect(chapterAudioUrls(reciter, ikhlas)[0]).toBe(audioUrl(reciter, 1, 1))
    expect(chapterAudioUrls(reciter, fatihah)).toHaveLength(7)
  })

  it('counts Al-Fatihah 1:1 only when Al-Fatihah itself was downloaded', () => {
    const kept = new Map([[audioUrl(reciter, 1, 1), MB], [audioUrl(reciter, 112, 1), 2 * MB]])
    const [row] = buildReciterDownloads([reciter], [fatihah, ikhlas], kept, new Set(['1-112']))
    expect(row!.chapters[0]!.cached).toBe(0)
    expect(row!.chapters[1]!).toMatchObject({ cached: 1, bytes: 2 * MB })
    const [owned] = buildReciterDownloads([reciter], [fatihah], kept, new Set(['1-1']))
    expect(owned!.chapters[0]!.cached).toBe(1)
  })

  it('keeps the shared basmalah while another preface chapter still needs it', () => {
    expect(keepsSharedBasmalahAfterDeleting(new Set(['1-112', '1-113']), 1, 112)).toBe(true)
    expect(keepsSharedBasmalahAfterDeleting(new Set(['1-112']), 1, 112)).toBe(false)
    expect(keepsSharedBasmalahAfterDeleting(new Set(['1-112', '1-9']), 1, 112)).toBe(false)
    expect(keepsSharedBasmalahAfterDeleting(new Set(['2-112']), 1, 112)).toBe(false)
  })

  it('names chapter verbs and facts as Android does', () => {
    const empty = { surah: ikhlas, cached: 0, bytes: 0 }
    const partial = { surah: ikhlas, cached: 2, bytes: 2 * MB }
    const done = { surah: ikhlas, cached: 4, bytes: 4 * MB }
    expect(chapterActionLabel(empty, false, false, false)).toBe('Download')
    expect(chapterActionLabel(partial, false, false, false)).toBe('Resume')
    expect(chapterActionLabel(done, false, false, false)).toBe('Delete')
    expect(chapterActionLabel(empty, true, false, false)).toBe('Pause')
    expect(chapterOffersDelete(partial, false, false, false)).toBe(true)
    expect(chapterOffersDelete(done, false, false, false)).toBe(false)
    expect(chapterFactLine(empty, false, false)).toBe('4 verses')
    expect(chapterFactLine(partial, false, false)).toBe('4 verses · 2 of 4 · 2 MB')
    expect(chapterFactLine(done, false, false)).toBe('4 verses · 4 MB')
    expect(chapterFactLine(empty, true, false)).toBe('4 verses · Waiting')
    expect(chapterProgressFactLine(1, 4, downloadPercent(1, 4))).toBe('1/4 verses · 25%')
  })

  it('labels reciter headers', () => {
    const none = { reciter, chapters: [{ surah: ikhlas, cached: 0, bytes: 0 }] }
    const some = { reciter, chapters: [{ surah: ikhlas, cached: 4, bytes: 4 * MB }, { surah: fatihah, cached: 0, bytes: 0 }] }
    expect(reciterDownloadLabel(none)).toBe('Nothing downloaded')
    expect(reciterDownloadLabel(some)).toBe('1 of 2 · 4 MB')
    const base = { expanded: false, busy: false, paused: false, hasDownloadable: true, hasBytes: false, confirming: false, hasResumable: false }
    expect(reciterHeaderAction(base)).toBeNull()
    expect(reciterHeaderAction({ ...base, expanded: true })).toBe('Download all')
    expect(reciterHeaderAction({ ...base, busy: true })).toBe('Pause')
    expect(reciterHeaderAction({ ...base, hasBytes: true })).toBe('Delete')
    expect(reciterHeaderAction({ ...base, hasBytes: true, confirming: true })).toBeNull()
  })
})
