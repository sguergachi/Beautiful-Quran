export interface RuntimeMushafWord {
  record_type: 'mushaf_word'
  record_key: string
  surah_id: number
  ayah_number: number
  position: number
  translation_en: string
  transliteration: string
  qcf_v2: string
  qcf_page: number
  qcf_line: number
  qcf_span_end: number
  ayah_page: number
}

export interface StoredQfResource {
  resourceGroup: string
  resourceId: number
  records: Record<string, unknown>[]
}

type TopologyRule = {
  canonicalStart: number
  canonicalCount: number
  qfStart: number
  qfCount: number
}

const TOPOLOGY_RULES = new Map<string, TopologyRule>([
  ['2:72', { canonicalStart: 4, canonicalCount: 2, qfStart: 4, qfCount: 1 }],
  ['2:181', { canonicalStart: 3, canonicalCount: 2, qfStart: 3, qfCount: 1 }],
  ['8:6', { canonicalStart: 4, canonicalCount: 2, qfStart: 4, qfCount: 1 }],
  ['13:37', { canonicalStart: 8, canonicalCount: 2, qfStart: 8, qfCount: 1 }],
  ['15:7', { canonicalStart: 1, canonicalCount: 1, qfStart: 1, qfCount: 2 }],
  ['27:20', { canonicalStart: 4, canonicalCount: 1, qfStart: 4, qfCount: 2 }],
  ['36:22', { canonicalStart: 1, canonicalCount: 1, qfStart: 1, qfCount: 2 }],
  ['37:130', { canonicalStart: 3, canonicalCount: 2, qfStart: 3, qfCount: 1 }],
  ['37:164', { canonicalStart: 1, canonicalCount: 1, qfStart: 1, qfCount: 2 }],
  ['41:47', { canonicalStart: 25, canonicalCount: 1, qfStart: 25, qfCount: 2 }],
])

/** Join QF's independently synced resources into one fully validated reader view. */
export function normalizeQfMushaf(
  canonical: Map<string, string[]>,
  resources: StoredQfResource[],
  expectedPages: readonly number[],
): RuntimeMushafWord[] {
  const mushaf = resource(resources, 'mushafs', 1).records
  const metadata = mushaf.filter((row) => row.record_type === 'mushaf')
  if (metadata.length !== 1 || Number(metadata[0]!.pages_count) !== expectedPages.length ||
      Number(metadata[0]!.lines_per_page) !== 15) throw new Error('Unexpected QF Mushaf layout')
  const pages = mushaf.filter((row) => row.record_type === 'mushaf_page')
  if (pages.length !== expectedPages.length ||
      new Set(pages.map((row) => Number(row.page_number))).size !== expectedPages.length ||
      expectedPages.some((page) => !pages.some((row) => Number(row.page_number) === page))) {
    throw new Error('QF Mushaf omitted a page')
  }

  const translations = uniqueWordText(resource(resources, 'word_by_word_translations', 59).records, 'translation')
  const transliterations = groupedWordText(resource(resources, 'word_by_word_transliterations', 60).records)
  const supplements = uniqueWordText(resource(resources, 'word_supplements', 1).records, 'supplement')
  const byVerse = new Map<number, Record<string, unknown>[]>()
  for (const row of mushaf.filter((value) => value.record_type === 'mushaf_word')) {
    const verseId = integer(row.verse_id, 'verse_id')
    const rows = byVerse.get(verseId) ?? []
    rows.push(row)
    byVerse.set(verseId, rows)
  }

  const verses = [...canonical.entries()].sort(([a], [b]) => compareVerseKeys(a, b))
  if (byVerse.size !== verses.length || verses.some((_, index) => !byVerse.has(index + 1))) {
    throw new Error('QF Mushaf verse coverage mismatch')
  }
  const output: RuntimeMushafWord[] = []
  verses.forEach(([verseKey, canonicalWords], index) => {
    const qfRows = byVerse.get(index + 1)!
    const words = qfRows.filter((row) => row.char_type_name === 'word')
      .sort((a, b) => Number(a.position_in_verse) - Number(b.position_in_verse))
    const ends = qfRows.filter((row) => row.char_type_name === 'end')
    if (words.some((word, position) => Number(word.position_in_verse) !== position + 1) || ends.length !== 1) {
      throw new Error(`Invalid QF word topology ${verseKey}`)
    }
    output.push(...mapVerse(
      verseKey, canonicalWords, words, String(ends[0]!.text),
      translations, transliterations, supplements,
    ))
  })
  const canonicalCount = [...canonical.values()].reduce((sum, words) => sum + words.length, 0)
  if (output.length !== canonicalCount || new Set(output.map((row) => row.record_key)).size !== output.length) {
    throw new Error('QF reader view is incomplete')
  }
  assertQcfV2Runs(output, expectedPages)
  return output
}

function mapVerse(
  verseKey: string,
  canonical: string[],
  qfWords: Record<string, unknown>[],
  endGlyph: string,
  translations: Map<number, string>,
  transliterations: Map<number, string[]>,
  supplements: Map<number, string>,
): RuntimeMushafWord[] {
  const rule = TOPOLOGY_RULES.get(verseKey)
  const expectedQfCount = canonical.length + (rule?.qfCount ?? 1) - (rule?.canonicalCount ?? 1)
  if (qfWords.length !== expectedQfCount) throw new Error(`QF topology changed for ${verseKey}`)
  const mapped = new Map<number, {
    glyph: string; page: number; line: number; spanEnd: number
    translation: string; transliteration: string
  }>()
  let canonicalPosition = 1
  let qfPosition = 1
  while (canonicalPosition <= canonical.length) {
    const atRule = rule?.canonicalStart === canonicalPosition && rule.qfStart === qfPosition ? rule : null
    const canonicalCount = atRule?.canonicalCount ?? 1
    const qfCount = atRule?.qfCount ?? 1
    const source = qfWords.slice(qfPosition - 1, qfPosition - 1 + qfCount)
    const page = integer(source[0]!.page_number, 'page_number')
    const line = integer(source[0]!.line_number, 'line_number')
    if (source.some((word) => Number(word.page_number) !== page || Number(word.line_number) !== line)) {
      throw new Error(`A joined QF word crosses a line at ${verseKey}`)
    }
    const ids = source.map((word) => integer(word.word_id, 'word_id'))
    mapped.set(canonicalPosition, {
      glyph: source.map((word) => string(word.text, 'text')).join(' '),
      page, line, spanEnd: canonicalPosition + canonicalCount - 1,
      translation: joinGloss(ids.map((id) => requiredMap(translations, id, 'translation'))),
      transliteration: joinGloss(ids.map((id) => supplements.get(id) ??
        single(transliterations.get(id), `QF transliteration is ambiguous or missing for word ${id}`))),
    })
    canonicalPosition += canonicalCount
    qfPosition += qfCount
  }
  if (qfPosition !== qfWords.length + 1) throw new Error(`QF topology ended early for ${verseKey}`)
  const lastOwner = Math.max(...mapped.keys())
  mapped.get(lastOwner)!.glyph += ` ${endGlyph}`
  const [surah, ayah] = verseKey.split(':').map(Number)
  const ayahPage = integer(qfWords[0]!.page_number, 'page_number')
  return canonical.map((_, index) => {
    const position = index + 1
    const value = mapped.get(position) ?? {
      glyph: '', page: 0, line: 0, spanEnd: position, translation: '', transliteration: '',
    }
    return {
      record_type: 'mushaf_word', record_key: `${verseKey}:${position}`,
      surah_id: surah!, ayah_number: ayah!, position,
      translation_en: value.translation, transliteration: value.transliteration,
      qcf_v2: value.glyph, qcf_page: value.page, qcf_line: value.line,
      qcf_span_end: value.spanEnd, ayah_page: ayahPage,
    }
  })
}

function groupedWordText(rows: Record<string, unknown>[]): Map<number, string[]> {
  const values = new Map<number, string[]>()
  for (const row of rows) {
    const id = integer(row.word_id, 'word_id')
    values.set(id, (values.get(id) ?? []).concat(string(row.text, 'text').trim()))
  }
  return values
}

function uniqueWordText(rows: Record<string, unknown>[], label: string): Map<number, string> {
  const grouped = groupedWordText(rows)
  const result = new Map<number, string>()
  for (const [id, values] of grouped) {
    if (values.length !== 1) throw new Error(`Duplicate QF ${label} word owner`)
    result.set(id, values[0]!)
  }
  return result
}

function joinGloss(parts: string[]) {
  return parts.filter(Boolean).filter((part, index, all) => index === 0 || part !== all[index - 1]).join(' ')
}

function resource(resources: StoredQfResource[], group: string, id: number) {
  const matches = resources.filter((value) => value.resourceGroup === group && value.resourceId === id)
  if (matches.length !== 1) throw new Error(`QF resource ${group}:${id} is missing`)
  return matches[0]!
}

function requiredMap(values: Map<number, string>, id: number, label: string) {
  const value = values.get(id)
  if (value == null) throw new Error(`QF ${label} is missing for word ${id}`)
  return value
}

function single(values: string[] | undefined, message: string) {
  if (values?.length !== 1) throw new Error(message)
  return values[0]!
}

function compareVerseKeys(first: string, second: string) {
  const [as, aa] = first.split(':').map(Number)
  const [bs, ba] = second.split(':').map(Number)
  return as! - bs! || aa! - ba!
}

function integer(value: unknown, name: string): number {
  if (!Number.isInteger(value)) throw new Error(`QF field ${name} is missing`)
  return Number(value)
}

function string(value: unknown, name: string): string {
  if (typeof value !== 'string') throw new Error(`QF field ${name} is missing`)
  return value
}

const QCF_V2_FIRST_CODEPOINT = 0xfc41

/** Proves that each glyph is in the contiguous run encoded by its page font. */
export function assertQcfV2Runs(records: RuntimeMushafWord[], expectedPages: readonly number[]) {
  const pages = new Map<number, RuntimeMushafWord[]>()
  for (const row of records) {
    if (!row.qcf_v2.trim()) continue
    pages.set(row.qcf_page, (pages.get(row.qcf_page) ?? []).concat(row))
  }
  for (const pageNumber of expectedPages) {
    const page = pages.get(pageNumber)
    if (!page) throw new Error(`QCF V2 page ${pageNumber} has no glyphs`)
    const codes = page
      .sort((a, b) => a.surah_id - b.surah_id || a.ayah_number - b.ayah_number || a.position - b.position)
      .flatMap((row) => [...row.qcf_v2].filter((character) => !/\s/u.test(character))
        .map((character) => character.codePointAt(0)!))
    codes.forEach((code, index) => {
      const expected = QCF_V2_FIRST_CODEPOINT + index
      if (code !== expected) throw new Error(
        `QCF V2 page ${pageNumber} glyph ${index} is U+${code.toString(16).toUpperCase()}, ` +
        `expected U+${expected.toString(16).toUpperCase()}`,
      )
    })
  }
}
