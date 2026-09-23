import { describe, expect, it } from 'vitest'
import type { Reciter, SurahContent } from '../../data/models'
import { BASMALAH_PLAYLIST_AYAH } from '../../domain/Basmalah'
import { buildPlaylist } from '../playlistPlan'

const reciter: Reciter = {
  id: 1,
  slug: 'Alafasy_128kbps',
  name: 'Mishary Rashid Alafasy',
  style: 'Murattal',
  hasTimings: true,
}

function content(surahId: number): SurahContent {
  return {
    surah: {
      id: surahId,
      nameArabic: '',
      nameTransliteration: 'Test',
      nameTranslation: '',
      revelationPlace: '',
      ayahCount: 3,
    },
    ayahs: [1, 2, 3].map((number) => ({
      surahId,
      number,
      text: '',
      translation: '',
      page: 1,
      words: [],
    })),
  }
}

describe('buildPlaylist', () => {
  it('prepends a basmalah clip for an eligible chapter start', () => {
    const playlist = buildPlaylist(content(2), reciter)

    expect(playlist.map((item) => item.ayah)).toEqual([
      BASMALAH_PLAYLIST_AYAH,
      1,
      2,
      3,
    ])
    expect(playlist[0]?.url).toContain('/001001.mp3')
  })

  it('does not add a separate basmalah to Al-Fatihah or At-Tawbah', () => {
    expect(buildPlaylist(content(1), reciter).map((item) => item.ayah)).toEqual([1, 2, 3])
    expect(buildPlaylist(content(9), reciter).map((item) => item.ayah)).toEqual([1, 2, 3])
  })

  it.each([
    'Yasser_Ad-Dussary_128kbps',
    'Abdul_Basit_Mujawwad_128kbps',
    'Husary_Muallim_128kbps',
    'Minshawy_Mujawwad_192kbps',
    'Mohammad_al_Tablaway_128kbps',
  ])('uses the universally available Al-Fatihah clip for %s', (slug) => {
    expect(buildPlaylist(content(2), { ...reciter, slug })[0]?.url).toContain(
      `/${slug}/001001.mp3`,
    )
  })

  it('starts a mid-surah playlist at the requested ayah without a preface', () => {
    const playlist = buildPlaylist(content(2), reciter, 2)

    expect(playlist.map((item) => item.ayah)).toEqual([2, 3])
    expect(playlist[0]?.url).toContain('/002002.mp3')
  })

  it('clamps non-positive starts to the first ayah', () => {
    expect(buildPlaylist(content(2), reciter, 0).map((item) => item.ayah)).toEqual([
      BASMALAH_PLAYLIST_AYAH,
      1,
      2,
      3,
    ])
  })
})
