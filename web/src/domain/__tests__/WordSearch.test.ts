import { describe, expect, it } from 'vitest'
import {
  ayahHighlightSpans,
  conceptRelevance,
  englishTranslationHighlightSpans,
  filterSurahs,
  isWordSearchQuery,
  matchWordSearch,
  matchWordSearchAsync,
  normalizeArabicForSearch,
  parseAyahReference,
  parseSearchQuery,
  sameAyahGlossLine,
  sectionWordSearchHits,
  shouldRunWordSearch,
  spellingCorrection,
  windowAroundMatch,
  type WordSearchIndexEntry,
  type WordSearchHit,
} from '../WordSearch'

function entry(
  surahId: number,
  ayah: number,
  position: number,
  arabic: string,
  translation: string,
  transliteration = '',
  ayahText = arabic,
): WordSearchIndexEntry {
  const norm = normalizeArabicForSearch(arabic)
  return {
    surahId,
    ayahNumber: ayah,
    position,
    arabic,
    arabicNorm: norm,
    translation,
    translationLower: translation.toLowerCase(),
    transliteration,
    transliterationLower: transliteration.toLowerCase(),
    root: '',
    ayahText,
    ayahTranslation: '',
    surahNameTransliteration: `Surah${surahId}`,
    surahNameArabic: `س${surahId}`,
  }
}

const index = [
  entry(1, 1, 1, 'بِسۡمِ', 'In the name', "bis'mi", 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ'),
  entry(1, 1, 3, 'ٱلرَّحۡمَٰنِ', 'the Most Gracious', 'al-rahmani', 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ'),
  entry(1, 1, 4, 'ٱلرَّحِيمِ', 'the Most Merciful', 'al-rahimi', 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ'),
  entry(2, 163, 2, 'ٱلرَّحۡمَٰنُ', 'the Most Gracious', 'al-rahmanu'),
  entry(55, 1, 1, 'ٱلرَّحۡمَٰنُ', 'The Most Merciful', 'al-rahman'),
]

describe('normalizeArabicForSearch', () => {
  it('strips tashkeel and unifies alef', () => {
    expect(normalizeArabicForSearch('ٱلرَّحۡمَٰنِ')).toBe('الرحمن')
    expect(normalizeArabicForSearch('ٱللَّهِ')).toBe('الله')
    expect(normalizeArabicForSearch('بِسۡمِ')).toBe('بسم')
  })
})

describe('matchWordSearch', () => {
  it('matches English gloss case-insensitively', () => {
    const hits = matchWordSearch(index, 'merciful')
    expect(hits.map((h) => [h.surahId, h.position])).toEqual([
      [1, 4],
      [55, 1],
    ])
    expect(hits.every((hit) => hit.matchReason === 'Text match')).toBe(true)
  })

  it('matches Arabic without diacritics', () => {
    const hits = matchWordSearch(index, 'الرحمن')
    expect(hits.some((h) => h.surahId === 1 && h.position === 3)).toBe(true)
    expect(hits.some((h) => h.surahId === 2 && h.position === 2)).toBe(true)
    expect(hits.some((h) => h.surahId === 55 && h.position === 1)).toBe(true)
  })

  it('falls back to fuzzy matches for one edit and a transposition', () => {
    const corrected = matchWordSearch(index, 'mercifl')
    expect(corrected.map((h) => [h.surahId, h.position])).toEqual([
      [1, 4],
      [55, 1],
    ])
    expect(corrected.every((hit) => hit.matchReason === 'Spelling match')).toBe(true)
    expect(corrected.every((hit) => hit.matchTerms?.[0] === 'merciful')).toBe(true)
    expect(spellingCorrection(corrected)).toBe('merciful')
    expect(spellingCorrection(matchWordSearch(index, 'merciful'))).toBeNull()
    expect(matchWordSearch(index, 'mercifull')).toHaveLength(2)
    expect(matchWordSearch(index, 'mercfiul').map((h) => [h.surahId, h.position])).toEqual([
      [1, 4],
      [55, 1],
    ])
    expect(matchWordSearch(index, 'الرحمان').some((h) => h.surahId === 1)).toBe(true)
  })

  it('ranks semantic vocabulary and suppresses spelling neighbors', async () => {
    const semanticIndex = [
      { ...entry(7, 154, 2, 'سَكَتَ', '(was) calmed'), ayahTranslation: 'the anger subsided' },
      { ...entry(7, 44, 1, 'وَنَادَىٰ', 'will call out'), ayahTranslation: 'they will call out' },
      { ...entry(9, 26, 1, 'سَكِينَتَهُ', 'His tranquility'), ayahTranslation: 'His tranquility' },
    ]
    const thesaurus = new Map([
      ['calm', [{ text: 'tranquility', distance: 2 }]],
    ])
    const hits = matchWordSearch(semanticIndex, 'calm', 400, [], thesaurus)
    expect(hits.map((hit) => [hit.surahId, hit.ayahNumber])).toEqual([
      [7, 154],
      [9, 26],
    ])
    expect(hits[1]!.matchTerms).toEqual(['tranquility'])
    expect(hits[1]!.matchReason).toBe('Related · tranquility')
    expect(hits.some((hit) => hit.ayahNumber === 44)).toBe(false)
    expect(await matchWordSearchAsync(semanticIndex, 'calm', 400, () => false, [], thesaurus))
      .toEqual(hits)
  })

  it('uses quotes for literal-only search', () => {
    expect(matchWordSearch(index, '"mercifull"')).toEqual([])
    expect(matchWordSearch(index, '"merciful"')).toHaveLength(2)
    expect(matchWordSearch(index, '“merciful”')).toHaveLength(2)
    expect(matchWordSearch(index, '"mercy"')).toEqual([])
    expect(isWordSearchQuery('""')).toBe(false)
  })

  it('searches an exact quoted phrase across the ayah translation', () => {
    const phrase = {
      ...entry(1, 1, 1, 'بِسْمِ', 'In the name'),
      ayahTranslation: 'In the name of Allah, the Entirely Merciful.',
    }
    expect(matchWordSearch([phrase], '"name of Allah"')[0]!.position).toBe(0)
  })

  it('retrieves and labels concept vocabulary below literal matches', () => {
    const concept = {
      name: 'Divine Mercy',
      primaryTerms: ['mercy of Allah', 'divine compassion'],
      secondaryTerms: ['clemency', 'forgiveness'],
      category: 'Divine Attributes and Signs',
      domain: 'Aqeedah',
      ayahKeys: [1_001, 55_001],
    }
    const hits = matchWordSearch(index, 'clemency', 400, [concept])
    expect(hits.map((hit) => [hit.surahId, hit.ayahNumber])).toEqual([
      [1, 1],
      [55, 1],
    ])
    expect(hits.every((hit) => hit.position === 0 && hit.matchLabel === 'Divine Mercy')).toBe(true)
    expect(hits.every((hit) => hit.matchReason === 'Concept · Divine Mercy')).toBe(true)
    expect(spellingCorrection(hits)).toBeNull()
    const corrected = matchWordSearch(index, 'clemncy', 400, [concept])
    expect(corrected.every((hit) => hit.matchTerms?.[0] === 'clemency')).toBe(true)
    expect(spellingCorrection(corrected)).toBe('clemency')
    const corruption = {
      ...concept,
      name: 'Prohibition of Corruption on Earth',
      primaryTerms: ['do not corrupt the earth'],
      secondaryTerms: [],
    }
    expect(spellingCorrection(matchWordSearch(index, 'corrupy', 400, [corruption]))).toBe('corrupt')
    expect(matchWordSearch(index, '"clemency"', 400, [concept])).toEqual([])
    expect(
      conceptRelevance(concept, parseSearchQuery('show me verses about clemency')),
    ).toBeGreaterThan(0)

    const lexical = [...index, entry(60, 1, 1, 'رَحْمَة', 'clemency')]
    expect(matchWordSearch(lexical, 'clemency', 400, [concept])[0]!.surahId).toBe(60)
  })

  it('expands a matched Arabic root to related word forms', () => {
    const rooted = [
      { ...entry(2, 37, 1, 'فَتَابَ', 'so He turned'), root: 'توب' },
      { ...entry(9, 104, 2, 'ٱلتَّوَّٰبُ', 'the Oft-Returning'), root: 'توب' },
    ]
    const hits = matchWordSearch(rooted, 'turned')
    expect(hits.map((hit) => hit.surahId)).toEqual([2, 9])
    expect(hits.map((hit) => hit.matchReason)).toEqual(['Text match', 'Same Arabic root'])
    expect(hits[1]!.matchTerms).toEqual(['the Oft-Returning'])
  })

  it('ranks visible corrected-concept evidence ahead of broad associations', () => {
    const evidenceIndex = [
      {
        ...entry(2, 9, 1, 'يُخَادِعُونَ', 'They deceive'),
        ayahTranslation: 'They deceive themselves',
      },
      {
        ...entry(2, 11, 1, 'تُفْسِدُوا', 'cause corruption'),
        ayahTranslation: 'Do not cause corruption on earth',
      },
    ]
    const broad = {
      name: 'Diseases of the Heart',
      primaryTerms: ['corrupt heart'],
      secondaryTerms: [],
      category: 'Heart and Soul',
      domain: 'Tazkiyah',
      ayahKeys: [2_009],
    }
    const direct = {
      name: 'Prohibition of Corruption on Earth',
      primaryTerms: ['do not corrupt the earth'],
      secondaryTerms: [],
      category: 'Stewardship',
      domain: 'Ethics',
      ayahKeys: [2_011],
    }

    const hits = matchWordSearch(evidenceIndex, 'corrupy', 400, [broad, direct])

    expect(hits.map((hit) => hit.ayahNumber)).toEqual([11, 9])
    expect(hits[0]!.matchLabel).toBe('Prohibition of Corruption on Earth')
    expect(spellingCorrection(hits)).toBe('corrupt')
  })

  it('keeps exact matches ahead of fuzzy neighbors', () => {
    const neighbors = [entry(1, 1, 1, 'قَالَ', 'lone'), entry(2, 1, 1, 'حُبّ', 'love')]
    expect(matchWordSearch(neighbors, 'love', 1).map((h) => h.translation)).toEqual(['love'])
  })

  it('rejects short queries', () => {
    expect(matchWordSearch(index, 'a')).toEqual([])
    expect(isWordSearchQuery('a')).toBe(false)
    expect(isWordSearchQuery('ab')).toBe(true)
  })

  it('async match equals sync and stops early when cancelled mid-scan', async () => {
    const sync = matchWordSearch(index, 'merciful')
    expect(await matchWordSearchAsync(index, 'merciful')).toEqual(sync)

    const big: WordSearchIndexEntry[] = []
    for (let i = 0; i < 9_000; i++) {
      big.push(entry(1, 1, i + 1, 'و', 'and', 'wa'))
    }
    // Plant a late hit after the first yield boundary (CHUNK = 4000).
    big[5_000] = entry(2, 1, 1, 'ر', 'merciful', 'rahim')
    let yielded = false
    const hits = await matchWordSearchAsync(big, 'merciful', 400, () => {
      if (!yielded) {
        yielded = true
        return false
      }
      return true
    })
    // Cancelled at the second yield — never reaches the planted hit.
    expect(hits.some((h) => h.translation === 'merciful')).toBe(false)
    expect(await matchWordSearchAsync(index, 'mercifl')).toEqual(
      matchWordSearch(index, 'mercifl'),
    )
  })
})

describe('sectionWordSearchHits', () => {
  it('truncates until expanded', () => {
    const hits: WordSearchHit[] = Array.from({ length: 5 }, (_, i) => ({
      surahId: 2,
      ayahNumber: i + 1,
      position: 1,
      arabic: 'و',
      translation: 'and',
      transliteration: 'wa',
      ayahText: 'و',
      ayahTranslation: '',
      surahNameTransliteration: 'Al-Baqarah',
      surahNameArabic: 'البقرة',
    }))
    const collapsed = sectionWordSearchHits(hits, new Set(), 3)
    expect(collapsed).toHaveLength(1)
    expect(collapsed[0]!.hits).toHaveLength(3)
    expect(collapsed[0]!.totalCount).toBe(5)
    expect(collapsed[0]!.hiddenCount).toBe(2)

    const expanded = sectionWordSearchHits(hits, new Set([2]), 3)
    expect(expanded[0]!.hits).toHaveLength(5)
    expect(expanded[0]!.hiddenCount).toBe(0)
  })
})

describe('ayahHighlightSpans', () => {
  it('marks the word at position', () => {
    const text = 'بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ'
    const spans = ayahHighlightSpans(text, 3, 'ٱلرَّحۡمَٰنِ')
    expect(spans.filter((s) => s.highlighted).map((s) => s.text)).toEqual([
      'ٱلرَّحۡمَٰنِ',
    ])
    expect(spans.map((s) => s.text).join('')).toBe(text)
  })
})

describe('englishTranslationHighlightSpans', () => {
  it('prefers the typed query in the ayah translation', () => {
    const ayah =
      'In the name of Allah, the Entirely Merciful, the Especially Merciful.'
    const spans = englishTranslationHighlightSpans(
      ayah,
      'Merciful',
      'the Most Merciful',
    )
    expect(spans.filter((s) => s.highlighted).map((s) => s.text)).toEqual([
      'Merciful',
      'Merciful',
    ])
    expect(spans.map((s) => s.text).join('')).toBe(ayah)
  })

  it('falls back to a gloss token when the query is Arabic', () => {
    const ayah = 'And He is the Oft-Returning, the Merciful.'
    const spans = englishTranslationHighlightSpans(
      ayah,
      'التواب',
      '(is) the Oft-returning (to mercy)',
    )
    expect(
      spans.some(
        (s) => s.highlighted && s.text.toLowerCase() === 'oft-returning',
      ),
    ).toBe(true)
  })

  it('chooses the word that won typo fallback instead of a function word', () => {
    const spans = englishTranslationHighlightSpans(
      'And the companions of Paradise will call out',
      'calp',
      'And they will call out',
    )
    expect(spans.filter((span) => span.highlighted).map((span) => span.text)).toEqual([
      'call',
    ])
    expect(
      englishTranslationHighlightSpans(
        'their inscription was guidance',
        'calp',
        '(was) calmed',
      ).some((span) => span.highlighted),
    ).toBe(false)
    expect(
      englishTranslationHighlightSpans('They will answer', 'calp', 'will').some(
        (span) => span.highlighted,
      ),
    ).toBe(false)
  })

  it('uses every visible related concept word for semantic results', () => {
    const spans = englishTranslationHighlightSpans(
      'Peace and reconciliation brought tranquility and stillness.',
      'calm',
      '',
      'Peace and Reconciliation',
      ['tranquility', 'stillness'],
    )
    expect(spans.filter((span) => span.highlighted).map((span) => span.text)).toEqual([
      'Peace',
      'reconciliation',
      'tranquility',
      'stillness',
    ])
    expect(spans.some((span) => !span.highlighted && span.text.includes('and'))).toBe(true)
  })

  it('windows the snippet around a mid-ayah match', () => {
    const lead = Array.from({ length: 40 }, (_, i) => `w${i + 1}`).join(' ')
    const ayah = `${lead} resting place more words after that keep going`
    const spans = englishTranslationHighlightSpans(ayah, 'rest', 'a resting place')
    const text = spans.map((s) => s.text).join('')
    expect(text.toLowerCase()).toContain('resting')
    expect(
      spans.some((s) => s.highlighted && s.text.toLowerCase().startsWith('rest')),
    ).toBe(true)
    expect(text.includes('w1 ')).toBe(false)
  })
})

describe('matchWordSearch gloss-line fallback', () => {
  it('uses same-ayah glosses when SI translation lacks the query', () => {
    const si = '[He] who made for you the earth a bed [spread out]'
    const entries = [
      entry(2, 22, 4, 'ٱلۡأَرۡضَ', 'the earth', '', 'ٱلۡأَرۡضَ'),
      entry(2, 22, 5, 'فِرَٰشٗا', 'a resting place', '', 'فِرَٰشٗا'),
      entry(2, 22, 6, 'وَٱلسَّمَآءَ', 'and the sky', '', 'وَٱلسَّمَآءَ'),
    ].map((e) => ({ ...e, ayahTranslation: si }))
    const hits = matchWordSearch(entries, 'rest')
    expect(hits).toHaveLength(1)
    expect(hits[0]!.ayahTranslation.toLowerCase()).toContain('resting')
    expect(hits[0]!.ayahTranslation).toContain('the earth')
    expect(hits[0]!.ayahTranslation).toContain('and the sky')
    const spans = englishTranslationHighlightSpans(
      hits[0]!.ayahTranslation,
      'rest',
      hits[0]!.translation,
    )
    expect(
      spans.some((s) => s.highlighted && s.text.toLowerCase().startsWith('rest')),
    ).toBe(true)
  })

  it('coalesces adjacent shared phrase copies in the preview', () => {
    const entries = [
      entry(25, 70, 1, 'إِلَّا', 'Except'),
      entry(25, 70, 6, 'وَعَمِلَ', 'righteous deeds'),
      entry(25, 70, 7, 'صَٰلِحٗا', 'righteous deeds'),
      entry(25, 70, 9, 'يُبَدِّلُ', 'Allah will replace'),
      entry(25, 70, 10, 'ٱللَّهُ', 'Allah will replace'),
      entry(25, 70, 11, 'سَيِّـَٔاتِهِمۡ', 'their evil deeds'),
      entry(25, 70, 12, 'سَلَـٰمٰا', 'Peace'),
      entry(25, 70, 13, 'سَلَـٰمٰا', 'Peace'),
    ]
    expect(sameAyahGlossLine(entries, 2)).toBe(
      'Except righteous deeds Allah will replace their evil deeds Peace Peace',
    )
  })
})

describe('windowAroundMatch', () => {
  it('keeps neighbors and adds ellipsis', () => {
    const text = 'one two three four five six seven eight nine ten eleven twelve'
    expect(windowAroundMatch(text, 'seven', 2, 2)).toBe(
      '…five six seven eight nine…',
    )
  })
})

describe('shouldRunWordSearch / parseAyahReference', () => {
  it('skips ayah references', () => {
    expect(shouldRunWordSearch('2:255')).toBe(false)
    expect(parseAyahReference('2:255')).toEqual({ surah: 2, ayah: 255 })
    expect(shouldRunWordSearch('mercy')).toBe(true)
  })
})

describe('filterSurahs', () => {
  const surahs = [
    {
      id: 1,
      nameArabic: 'الفاتحة',
      nameTransliteration: 'Al-Fatihah',
      nameTranslation: 'The Opener',
      ayahCount: 7,
    },
    {
      id: 2,
      nameArabic: 'البقرة',
      nameTransliteration: 'Al-Baqarah',
      nameTranslation: 'The Cow',
      ayahCount: 286,
    },
  ]

  it('matches names and references', () => {
    expect(filterSurahs(surahs, 'baqara').surahs.map((s) => s.id)).toEqual([2])
    expect(filterSurahs(surahs, 'baqrah').surahs.map((s) => s.id)).toEqual([2])
    expect(filterSurahs(surahs, 'opner').surahs.map((s) => s.id)).toEqual([1])
    expect(filterSurahs(surahs, '"baqrah"').surahs).toEqual([])
    expect(filterSurahs(surahs, '2:255')).toEqual({
      surahs: [surahs[1]],
      ayahTarget: 255,
    })
  })
})
