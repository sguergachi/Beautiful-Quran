import type { SurahContent } from '../../data/models'
import type { RuntimeMushafWord } from '../../data/runtimeMushaf'
import { buildMushafPage, pageAyahs } from '../../domain/mushafPage'

function buildLeafModel(page: number, rows: readonly RuntimeMushafWord[], contentOf: (id: number) => SurahContent) {
  const verses = new Map<string, SurahContent['ayahs'][number]>()
  const lastPositions = new Map<string, number>()
  const placements = rows.map((row) => {
    const key = `${row.surah_id}:${row.ayah_number}`
    let ayah = verses.get(key)
    if (!ayah) {
      ayah = contentOf(row.surah_id).ayahs.find((item) => item.number === row.ayah_number)
      if (ayah) {
        verses.set(key, ayah)
        lastPositions.set(key, Math.max(0, ...ayah.words.map((word) => word.position)))
      }
    }
    return {
      surahId: row.surah_id,
      ayah: row.ayah_number,
      position: row.position,
      line: row.qcf_line,
      arabic: ayah?.words.find((word) => word.position === row.position)?.arabic ?? '',
    }
  })
  return {
    leaf: buildMushafPage(page, placements),
    headSurah: rows[0] ? contentOf(rows[0].surah_id).surah : null,
    englishAyahs: pageAyahs(placements).map((item) => ({
      ...item,
      translation: verses.get(`${item.surahId}:${item.ayah}`)?.translation,
    })),
    lastPositions,
  }
}

const models = new WeakMap<readonly RuntimeMushafWord[], ReturnType<typeof buildLeafModel>>()

/** Page rows are immutable until a cache refresh; all copies share their model. */
export function mushafLeafModel(page: number, rows: readonly RuntimeMushafWord[], contentOf: (id: number) => SurahContent) {
  let model = models.get(rows)
  if (!model) {
    model = buildLeafModel(page, rows, contentOf)
    models.set(rows, model)
  }
  return model
}
