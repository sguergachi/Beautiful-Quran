import { describe, expect, it } from 'vitest'
import type { RootViewerState } from '../../../store/appStore'
import {
  initialRootSections,
  relatedRootForms,
  rootOccurrenceSections,
  type RootOccurrenceSection,
} from '../rootViewerSections'

function occurrence(surahId: number, ayahNumber: number): RootViewerState['occurrences'][number] {
  return {
    surahId,
    ayahNumber,
    position: 1,
    arabic: 'كتب',
    translation: 'wrote',
    surahNameTransliteration: `Chapter ${surahId}`,
  }
}

describe('rootOccurrenceSections', () => {
  it('groups occurrences in Quran order', () => {
    const sections = rootOccurrenceSections([
      ...Array.from({ length: 7 }, (_, index) => occurrence(2, index + 1)),
      ...Array.from({ length: 3 }, (_, index) => occurrence(3, index + 1)),
    ])

    expect(sections.map((section) => section.surahId)).toEqual([2, 3])
    expect(sections.map((section) => section.occurrences.length)).toEqual([7, 3])
  })
})

describe('initialRootSections', () => {
  const sections: RootOccurrenceSection[] = Array.from({ length: 10 }, (_, index) => ({
    surahId: index + 1,
    surahName: `Chapter ${index + 1}`,
    occurrences: [occurrence(index + 1, 1)],
  }))

  it('keeps the normal first eight when current is already present', () => {
    expect(initialRootSections(sections, 3).map((section) => section.surahId)).toEqual([1, 2, 3, 4, 5, 6, 7, 8])
  })

  it('substitutes a later current chapter without duplication', () => {
    expect(initialRootSections(sections, 10).map((section) => section.surahId)).toEqual([1, 2, 3, 4, 5, 6, 7, 10])
  })
})

describe('relatedRootForms', () => {
  it('excludes only the exact current lemma and POS', () => {
    const forms = [
      { lemma: 'كتب', pos: 'N', occurrenceCount: 10, gloss: 'Book' },
      { lemma: 'كتب', pos: 'V', occurrenceCount: 6, gloss: 'prescribed' },
      { lemma: 'كاتب', pos: 'N', occurrenceCount: 3, gloss: 'scribe' },
    ]

    expect(relatedRootForms(forms, 'كتب', 'N')).toEqual(forms.slice(1))
  })
})
