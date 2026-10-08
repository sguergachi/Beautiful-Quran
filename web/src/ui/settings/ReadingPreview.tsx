import type { CSSProperties } from 'react'
import type { Settings } from '../../data/settings'
import { showsPreviewWordGloss } from '../../data/customizePolicy'
import { formatAyahNumberMark } from '../../util/digits'
import { symbolicAyahBarCount } from '../reader/ayahRailMath'
import { PageBreak } from '../reader/PageBreak'

// A real verse and its printed folio, shared by the two preview layouts.
const SAMPLE_ARABIC_1 = 'وَإِنَّهُۥ لَقَسَمٞ لَّوۡ تَعۡلَمُونَ عَظِيمٌ'
const SAMPLE_ENGLISH =
  'And indeed, it is an oath - if you could know - [most] great.'
const SAMPLE_PAGE = 536
const SAMPLE_AYAH_1 = 76
const SAMPLE_WORDS = [
  { arabic: 'وَإِنَّهُۥ', gloss: 'indeed', transliteration: 'wa-innahu' },
  { arabic: 'لَقَسَمٞ', gloss: 'an oath', transliteration: 'laqasamun' },
  { arabic: 'لَّوۡ', gloss: 'if', transliteration: 'law' },
  { arabic: 'تَعۡلَمُونَ', gloss: 'you knew', transliteration: 'taʿlamūna' },
  { arabic: 'عَظِيمٌ', gloss: 'great', transliteration: 'ʿaẓīmun' },
]

const PREVIEW_RAIL_AYAHS = 7

export function ReadingPreview({ settings }: { settings: Settings }) {
  const { readingMode, readingLayout, verseNumberScript, pageNumberScript, ayahSelectorSide,
    fontScale, showWordGloss, showTranslation, showTransliteration } = settings
  const mushaf = readingLayout === 'mushaf'
  const arabicOnly = readingMode === 'arabic_only'
  const englishOnly = readingMode === 'english_only'
  const bilingual = !arabicOnly && !englishOnly && !mushaf
  const showGloss = bilingual && showsPreviewWordGloss(readingMode, showWordGloss)
  const arabicMarks = mushaf ? !englishOnly : verseNumberScript === 'arabic'
  const markClass = arabicMarks ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'
  const mark = (ayah: number) => <span className={markClass} dir={arabicMarks ? undefined : 'ltr'}>{formatAyahNumberMark(ayah, arabicMarks)}</span>

  return (
    <div
      className={`reading-preview reading-preview--rail-${ayahSelectorSide}`}
      data-layout={readingLayout}
      style={{ '--preview-scale': mushaf ? 1 : fontScale } as CSSProperties}
    >
      {!mushaf ? (
        <div className={`reading-preview__rail reading-preview__rail--${ayahSelectorSide}`} aria-hidden="true">
          {Array.from({ length: symbolicAyahBarCount(PREVIEW_RAIL_AYAHS) }, (_, index) => <i key={index} data-focus={index === 0 ? 'true' : undefined} />)}
        </div>
      ) : <div className="reading-preview__head">Al-Wāqiʿah · الواقعة</div>}
      {englishOnly ? (
        <p className="reading-preview__english reading-preview__english--lyric" dir="ltr">{SAMPLE_ENGLISH} {mark(SAMPLE_AYAH_1)}</p>
      ) : bilingual && (showGloss || showTransliteration) ? (
        <div className="reading-preview__words" dir="rtl">
          {SAMPLE_WORDS.map((word) => (
            <span key={word.arabic} className="reading-preview__word">
              <span className="reading-preview__arabic">{word.arabic}</span>
              {showTransliteration ? <span className="reading-preview__translit" dir="ltr">{word.transliteration}</span> : null}
              {showGloss ? <span className="reading-preview__gloss" dir="ltr">{word.gloss}</span> : null}
            </span>
          ))}
          {mark(SAMPLE_AYAH_1)}
        </div>
      ) : <p className="reading-preview__arabic" lang="ar" dir="rtl">{SAMPLE_ARABIC_1} {mark(SAMPLE_AYAH_1)}</p>}
      {bilingual && showTranslation ? <p className="reading-preview__english">{SAMPLE_ENGLISH}</p> : null}
      {mushaf ? <PageBreak page={SAMPLE_PAGE} script={englishOnly ? 'english' : pageNumberScript} /> : null}
    </div>
  )
}
