import { useLayoutEffect, useRef, type CSSProperties, type ReactNode } from 'react'
import type { PageNumberScript, Settings } from '../../data/settings'
import { showsPreviewAnnotation, showsPreviewWordGloss } from '../../data/customizePolicy'
import { runtimeMushafCache } from '../../data/runtimeMushaf'
import { QuranRepository } from '../../data/repository'
import { formatAyahNumberMark, mushafHeadFigures } from '../../util/digits'
import { symbolicAyahBarCount } from '../reader/ayahRailMath'
import { PageBreak } from '../reader/PageBreak'

// 56:76 ends page 536; 56:77 opens 537 — a real printed-page turn.
const SAMPLE_ARABIC_1 = 'وَإِنَّهُۥ لَقَسَمٞ لَّوۡ تَعۡلَمُونَ عَظِيمٌ'
const SAMPLE_ARABIC_2 = 'إِنَّهُۥ لَقُرۡءَانٞ كَرِيمٞ'
const SAMPLE_TRANSLIT = 'Wa-innahu la-qasam law taʿlamūna ʿaẓīm'
const SAMPLE_ENGLISH = 'And indeed, it is an oath - if you could know - [most] great.'
const SAMPLE_ENGLISH_2 = "Indeed, it is a noble Qur'an."
const SAMPLE_NOTE = 'The oath is the setting of the stars.'
const SAMPLE_PAGE = 536
const SAMPLE_AYAH_1 = 76
const SAMPLE_AYAH_2 = 77
const SAMPLE_WORDS = [
  { arabic: 'وَإِنَّهُۥ', gloss: 'indeed' },
  { arabic: 'لَقَسَمٞ', gloss: 'an oath' },
  { arabic: 'عَظِيمٌ', gloss: 'great' },
]

/**
 * 21:91–93 occupy page 330, lines 1–4. Both Mushaf miniatures set the same
 * paper, so switching View shows one page in either language.
 */
const MUSHAF_PAGE = 330
const MUSHAF_LINE_FIRST = 1
const MUSHAF_LINE_LAST = 4
const MUSHAF_SURAH = 21
const MUSHAF_SURAH_LATIN = 'Al-Anbya'
const MUSHAF_AYAH_FIRST = 91
const MUSHAF_AYAH_LAST = 93
/** 21:91–93 as the database carries them; the verse mark closes each sentence. */
const SAMPLE_ENGLISH_LEAF = [
  'And [mention] the one who guarded her chastity, so We blew into her [garment] through Our angel [Gabriel], and We made her and her son a sign for the worlds',
  'Indeed this, your religion, is one religion, and I am your Lord, so worship Me',
  'And [yet] they divided their affair among themselves, [but] all to Us will return',
]

const PREVIEW_RAIL_AYAHS = 7

type Mark = (ayah: number, arabic: boolean) => ReactNode

const mark: Mark = (ayah, arabic) => (
  // Isolated in its own direction, so ﴿N﴾ never flips inside English prose.
  <span className="ayah-mark reading-preview__mark" dir={arabic ? 'rtl' : 'ltr'}>
    {formatAyahNumberMark(ayah, arabic)}
  </span>
)

/**
 * Android `ReadingPreview`: a live miniature of the reader on a faded leaf
 * with a gold hairline. The leaf keeps one height whatever is chosen (the
 * tallest scroll page and both Mushaf leaves are laid underneath, unseen), and
 * the size dial grows the type inside it, clipping at the foot.
 */
export function ReadingPreview({ settings }: { settings: Settings }) {
  const { readingMode, readingLayout, verseNumberScript, pageNumberScript, ayahSelectorSide,
    fontScale, showWordGloss, showTranslation, showTransliteration, annotationsEnabled } = settings
  const mushaf = readingLayout === 'mushaf'
  const arabicMarks = verseNumberScript === 'arabic'
  const showRail = !mushaf

  return (
    <div
      className={`reading-preview${showRail ? ` reading-preview--rail-${ayahSelectorSide}` : ''}`}
      data-layout={readingLayout}
      style={{ '--preview-scale': mushaf ? 1 : fontScale } as CSSProperties}
    >
      <div className="reading-preview__stack">
        {/* Height lock: the tallest views, laid unseen at the base size. */}
        <div className="reading-preview__ghost" aria-hidden="true" style={{ '--preview-scale': 1 } as CSSProperties}>
          <ScrollBody
            mode="arabic_english"
            arabicMarks
            showGloss
            showTransliteration={false}
            showTranslation
            showNote
            pageNumberScript="both"
          />
        </div>
        <div className="reading-preview__ghost" aria-hidden="true">
          <MushafLeafPreview pageNumberScript="both" />
        </div>
        <div className="reading-preview__ghost" aria-hidden="true">
          <EnglishLeafPreview pageNumberScript="both" arabicMarks />
        </div>
        <div className="reading-preview__body">
          {mushaf ? (
            readingMode === 'english_only' ? (
              <EnglishLeafPreview pageNumberScript={pageNumberScript} arabicMarks={arabicMarks} />
            ) : (
              <MushafLeafPreview pageNumberScript={pageNumberScript} />
            )
          ) : (
            <ScrollBody
              mode={readingMode}
              arabicMarks={arabicMarks}
              showGloss={showsPreviewWordGloss(readingMode, showWordGloss)}
              showTransliteration={showTransliteration}
              showTranslation={showTranslation}
              showNote={showsPreviewAnnotation(readingLayout, annotationsEnabled)}
              pageNumberScript={pageNumberScript}
            />
          )}
        </div>
      </div>
      {showRail ? (
        <div className={`reading-preview__rail reading-preview__rail--${ayahSelectorSide}`} aria-hidden="true">
          {Array.from({ length: symbolicAyahBarCount(PREVIEW_RAIL_AYAHS) }, (_, index) => (
            <i key={index} data-focus={index === 0 ? 'true' : undefined} />
          ))}
        </div>
      ) : null}
    </div>
  )
}

function ScrollBody({
  mode,
  arabicMarks,
  showGloss,
  showTransliteration,
  showTranslation,
  showNote,
  pageNumberScript,
}: {
  mode: Settings['readingMode']
  arabicMarks: boolean
  showGloss: boolean
  showTransliteration: boolean
  showTranslation: boolean
  showNote: boolean
  pageNumberScript: PageNumberScript
}) {
  const folio = <PageBreak page={SAMPLE_PAGE} script={pageNumberScript} />
  if (mode === 'english_only') {
    return (
      <>
        <p className="reading-preview__lyric" dir="ltr">
          {SAMPLE_ENGLISH} {mark(SAMPLE_AYAH_1, arabicMarks)}
        </p>
        {folio}
        <p className="reading-preview__lyric" dir="ltr">
          {SAMPLE_ENGLISH_2} {mark(SAMPLE_AYAH_2, arabicMarks)}
        </p>
      </>
    )
  }
  const bilingual = mode === 'arabic_english'
  return (
    <>
      {showGloss ? (
        <div className="reading-preview__words" dir="rtl">
          {SAMPLE_WORDS.map((word) => (
            <span key={word.arabic} className="reading-preview__word">
              <span className="reading-preview__arabic">{word.arabic}</span>
              <span className="reading-preview__gloss" dir="ltr">{word.gloss}</span>
            </span>
          ))}
          {mark(SAMPLE_AYAH_1, arabicMarks)}
        </div>
      ) : (
        <p className="reading-preview__arabic" lang="ar" dir="rtl">
          {SAMPLE_ARABIC_1}{' '}{mark(SAMPLE_AYAH_1, arabicMarks)}
        </p>
      )}
      {bilingual && showTransliteration ? (
        <p className="reading-preview__translit" dir="ltr">{SAMPLE_TRANSLIT}</p>
      ) : null}
      {bilingual && showTranslation ? <p className="reading-preview__english">{SAMPLE_ENGLISH}</p> : null}
      {showNote ? (
        <p className="reading-preview__note">
          <span>{SAMPLE_NOTE}</span>
        </p>
      ) : null}
      {folio}
      {mode === 'arabic_only' ? (
        <p className="reading-preview__arabic" lang="ar" dir="rtl">
          {SAMPLE_ARABIC_2}{' '}{mark(SAMPLE_AYAH_2, arabicMarks)}
        </p>
      ) : null}
    </>
  )
}

/** Both Mushaf hands number their leaf in the same running head. */
function LeafHead({ pageNumberScript }: { pageNumberScript: PageNumberScript }) {
  const figures = mushafHeadFigures(MUSHAF_PAGE, pageNumberScript, MUSHAF_SURAH_LATIN)
  return (
    <div className="reading-preview__head" data-both={figures.right != null || undefined}>
      <span className="reading-preview__head-figure" lang={figures.leftLang ?? undefined}>
        {figures.left}
      </span>
      <span className="reading-preview__head-chapter">{figures.center}</span>
      {figures.right != null ? (
        <span className="reading-preview__head-figure" lang="ar">
          {figures.right}
        </span>
      ) : null}
    </div>
  )
}

type LeafToken = { key: string; text: string; mark: boolean }

/** Page 330's first lines from the page map, or null before it arrives. */
function mushafPreviewLines(): LeafToken[][] | null {
  try {
    const rows = runtimeMushafCache?.pageWords(MUSHAF_PAGE)
    if (!rows || rows.length === 0) return null
    const ayahs = QuranRepository.surahContent(MUSHAF_SURAH).ayahs
    const lines: LeafToken[][] = []
    for (let line = MUSHAF_LINE_FIRST; line <= MUSHAF_LINE_LAST; line++) {
      const words = rows.filter((row) => row.qcf_line === line)
      if (words.length === 0) return null
      lines.push(
        words.flatMap((row) => {
          const ayah = ayahs.find((item) => item.number === row.ayah_number)
          const word = ayah?.words.find((item) => item.position === row.position)
          const tokens: LeafToken[] = [
            { key: `${row.ayah_number}:${row.position}`, text: word?.arabic ?? '', mark: false },
          ]
          const last = ayah ? Math.max(...ayah.words.map((item) => item.position)) : -1
          if (row.position === last) {
            tokens.push({ key: `${row.ayah_number}:mark`, text: formatAyahNumberMark(row.ayah_number, true), mark: true })
          }
          return tokens
        }),
      )
    }
    return lines
  } catch {
    return null
  }
}

/** Before the page map arrives: the same verses, set as a justified block. */
function mushafFallbackText(): LeafToken[] {
  try {
    const ayahs = QuranRepository.surahContent(MUSHAF_SURAH).ayahs.filter(
      (ayah) => ayah.number >= MUSHAF_AYAH_FIRST && ayah.number <= MUSHAF_AYAH_LAST,
    )
    return ayahs.flatMap((ayah) => [
      { key: `${ayah.number}`, text: ayah.text, mark: false },
      { key: `${ayah.number}:mark`, text: formatAyahNumberMark(ayah.number, true), mark: true },
    ])
  } catch {
    return []
  }
}

/** The Arabic leaf: page 330's first four lines under the running head. */
function MushafLeafPreview({ pageNumberScript }: { pageNumberScript: PageNumberScript }) {
  const lines = mushafPreviewLines()
  const linesRef = useRef<HTMLDivElement>(null)
  // A printed line is never wrapped: scale the hand so the widest fits.
  useLayoutEffect(() => {
    const box = linesRef.current
    if (!box || !lines) return
    box.style.setProperty('--leaf-fit', '1')
    const width = box.clientWidth
    let widest = 0
    box.querySelectorAll<HTMLElement>('.reading-preview__qline').forEach((line) => {
      widest = Math.max(widest, line.scrollWidth)
    })
    if (widest > width && width > 0) box.style.setProperty('--leaf-fit', String(width / widest))
  })
  return (
    <div className="reading-preview__leaf">
      <LeafHead pageNumberScript={pageNumberScript} />
      {lines ? (
        <div className="reading-preview__qlines" dir="rtl" lang="ar" ref={linesRef}>
          {lines.map((line, index) => (
            <div key={index} className="reading-preview__qline">
              {line.map((token) => (
                <span key={token.key} className={token.mark ? 'reading-preview__qmark' : undefined}>
                  {token.text}
                </span>
              ))}
            </div>
          ))}
        </div>
      ) : (
        <p className="reading-preview__qblock" dir="rtl" lang="ar">
          {mushafFallbackText().map((token) => (
            <span key={token.key} className={token.mark ? 'reading-preview__qmark' : undefined}>
              {token.text}{' '}
            </span>
          ))}
        </p>
      )}
    </div>
  )
}

/** The English leaf: the same verses as one ragged paragraph in the book's hand. */
function EnglishLeafPreview({
  pageNumberScript,
  arabicMarks,
}: {
  pageNumberScript: PageNumberScript
  arabicMarks: boolean
}) {
  return (
    <div className="reading-preview__leaf">
      <LeafHead pageNumberScript={pageNumberScript} />
      <p className="reading-preview__leaf-english" dir="ltr">
        {SAMPLE_ENGLISH_LEAF.map((verse, index) => (
          <span key={index}>
            {index > 0 ? ' ' : null}
            {verse}
            {/* Narrow no-break space: the mark belongs to the verse it closes. */}
            {' '}
            {mark(MUSHAF_AYAH_FIRST + index, arabicMarks)}
          </span>
        ))}
      </p>
    </div>
  )
}
