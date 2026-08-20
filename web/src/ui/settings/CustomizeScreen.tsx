import { appStore } from '../../store/appStore'
import type {
  AyahSelectorSide,
  PageNumberScript,
  ReadingLayout,
  ReadingMode,
  Settings,
  ThemeMode,
  VerseNumberScript,
} from '../../data/settings'
import {
  applyReadingLayout,
  applyReadingMode,
  showsPreviewAyahRail,
  showsPreviewWordGloss,
  showsScrollChrome,
  showsWordGlossChrome,
} from '../../data/customizePolicy'
import { formatAyahNumberMark, formatMushafAyahMark } from '../../util/digits'
import { symbolicAyahBarCount } from '../reader/ayahRailMath'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSegmented } from '../kit/PaperSegmented'
import { PaperSwitch } from '../kit/PaperSwitch'
import { MushafFolio } from '../reader/MushafFolio'
import { PageBreak } from '../reader/PageBreak'
import type { BrushCheckParams } from '../kit/brushCheck'
import type { BrushCircleParams } from '../kit/brushMark'
import { ThemeSwatches } from './themeSwatches'

const VIEW_OPTIONS = [
  { value: 'arabic_only' as const, label: 'Arabic' },
  { value: 'english_only' as const, label: 'English' },
  { value: 'arabic_english' as const, label: 'Both' },
]

const LAYOUT_OPTIONS = [
  { value: 'scroll' as const, label: 'Scroll' },
  { value: 'mushaf' as const, label: 'Mushaf' },
]

const VERSE_OPTIONS = [
  { value: 'arabic' as const, label: 'Arabic' },
  { value: 'english' as const, label: 'English' },
]

const PAGE_OPTIONS = [
  { value: 'both' as const, label: 'Both' },
  { value: 'arabic' as const, label: 'Arabic' },
  { value: 'english' as const, label: 'English' },
]

const SELECTOR_OPTIONS = [
  { value: 'left' as const, label: 'Left side' },
  { value: 'right' as const, label: 'Right side' },
]

const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Paper' },
  { value: 'dark', label: 'Nightfall' },
  { value: 'royal_green', label: 'Royal green' },
]

const MUSHAF_HEAD = 'سُورَةُ الأنبياء'
const MUSHAF_LINE_1 = 'وَٱلَّتِيٓ أَحۡصَنَتۡ فَرۡجَهَا فَنَفَخۡنَا فِيهَا مِن رُّوحِنَا'
const MUSHAF_LINE_2 = 'وَجَعَلۡنَٰهَا وَٱبۡنَهَآ ءَايَةٗ لِّلۡعَٰلَمِينَ إِنَّ هَٰذِهِۦٓ'
const MUSHAF_LINE_3 = 'أُمَّتُكُمۡ أُمَّةٗ وَٰحِدَةٗ وَأَنَا۠ رَبُّكُمۡ فَٱعۡبُدُونِ'
const MUSHAF_PAGE = 330
const MUSHAF_AYAH_1 = 91
const MUSHAF_AYAH_2 = 92
// 56:76 ends page 536; 56:77 opens 537 — a real printed-page turn.
const SAMPLE_ARABIC_1 = 'وَإِنَّهُۥ لَقَسَمٞ لَّوۡ تَعۡلَمُونَ عَظِيمٌ'
const SAMPLE_ARABIC_2 = 'إِنَّهُۥ لَقُرۡءَانٞ كَرِيمٞ'
const SAMPLE_ENGLISH =
  'And indeed, it is an oath - if you could know - [most] great.'
const SAMPLE_ENGLISH_2 = "Indeed, it is a noble Qur'an."
const SAMPLE_PAGE = 536
const SAMPLE_AYAH_1 = 76
const SAMPLE_AYAH_2 = 77
const SAMPLE_WORDS = [
  { arabic: 'وَإِنَّهُۥ', gloss: 'indeed' },
  { arabic: 'لَقَسَمٞ', gloss: 'an oath' },
  { arabic: 'عَظِيمٌ', gloss: 'great' },
]

export function CustomizeScreen({
  settings,
  brushParams,
  paintToken,
  checkParams,
  checkPaintToken,
  onBack,
}: {
  settings: Settings
  brushParams: BrushCircleParams
  paintToken: number
  checkParams?: BrushCheckParams
  checkPaintToken?: number
  onBack: () => void
}) {
  return (
    <div className="customize">
      <div className="customize-sticky">
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
        <svg
          className="settings-back-icon"
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M15.5 5.5 L9 12 l6.5 6.5"
            stroke="currentColor"
            strokeWidth="1.75"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
      <h1>Customize</h1>

      <section className="settings-section">
        <h2>Preview</h2>
        <ReadingPreview
          readingMode={settings.readingMode}
          readingLayout={settings.readingLayout}
          verseNumberScript={settings.verseNumberScript}
          pageNumberScript={settings.pageNumberScript}
          ayahSelectorSide={settings.ayahSelectorSide}
          showWordGloss={settings.showWordGloss}
        />
      </section>
      </div>

      <div className="customize-scroll">
      <section className="settings-section">
        <h2>Layout</h2>
        <PaperSegmented
          aria-label="Layout"
          value={settings.readingLayout}
          brushParams={brushParams}
          paintToken={paintToken}
          options={LAYOUT_OPTIONS}
          onChange={(v) =>
            appStore.updateSettings(applyReadingLayout(settings, v as ReadingLayout))
          }
        />
        <p className="settings-caption">Mushaf is a printed Arabic page.</p>
      </section>

      {showsScrollChrome(settings.readingLayout) ? (
        <>
          <section className="settings-section">
            <h2>View</h2>
            <PaperSegmented
              aria-label="View"
              value={settings.readingMode}
              brushParams={brushParams}
              paintToken={paintToken}
              options={VIEW_OPTIONS}
              onChange={(v) =>
                appStore.updateSettings(applyReadingMode(settings, v as ReadingMode))
              }
            />
          </section>

          {showsWordGlossChrome(settings.readingLayout, settings.readingMode) ? (
            <section className="settings-section settings-section-toggles">
              <PaperSwitch
                id="setting-gloss"
                label="Word-by-word translation"
                checked={settings.showWordGloss}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(checked) =>
                  appStore.updateSettings({ showWordGloss: checked })
                }
              />
            </section>
          ) : null}

          <section className="settings-section">
            <h2>Ayah selector</h2>
            <PaperSegmented
              aria-label="Ayah selector side"
              value={settings.ayahSelectorSide}
              brushParams={brushParams}
              paintToken={paintToken}
              options={SELECTOR_OPTIONS}
              onChange={(v) =>
                appStore.updateSettings({
                  ayahSelectorSide: v as AyahSelectorSide,
                })
              }
            />
          </section>
        </>
      ) : null}

      <section className="settings-section">
        <h2>Verse numbers</h2>
        <PaperSegmented
          aria-label="Verse numbers"
          value={settings.verseNumberScript}
          brushParams={brushParams}
          paintToken={paintToken}
          options={VERSE_OPTIONS}
          onChange={(v) =>
            appStore.updateSettings({ verseNumberScript: v as VerseNumberScript })
          }
        />
      </section>

      <section className="settings-section">
        <h2>Page numbers</h2>
        <PaperSegmented
          aria-label="Page numbers"
          value={settings.pageNumberScript}
          brushParams={brushParams}
          paintToken={paintToken}
          options={PAGE_OPTIONS}
          onChange={(v) =>
            appStore.updateSettings({ pageNumberScript: v as PageNumberScript })
          }
        />
      </section>

      <section className="settings-section">
        <h2>Theme</h2>
        <PaperChoiceList
          aria-label="Theme"
          value={settings.themeMode}
          options={THEME_OPTIONS.map((opt) => ({
            ...opt,
            trailing: <ThemeSwatches mode={opt.value} />,
          }))}
          onChange={(v) =>
            appStore.updateSettings({ themeMode: v as ThemeMode })
          }
        />
      </section>
      </div>
    </div>
  )
}

const PREVIEW_RAIL_AYAHS = 7

function ReadingPreview({
  readingMode,
  readingLayout,
  verseNumberScript,
  pageNumberScript,
  ayahSelectorSide = 'left',
  showWordGloss = false,
}: {
  readingMode: ReadingMode
  readingLayout: ReadingLayout
  verseNumberScript: VerseNumberScript
  pageNumberScript: PageNumberScript
  ayahSelectorSide?: AyahSelectorSide
  showWordGloss?: boolean
}) {
  const arabicOnly = readingLayout === 'mushaf' || readingMode === 'arabic_only'
  const englishOnly = readingLayout === 'scroll' && readingMode === 'english_only'
  const showGloss = showsPreviewWordGloss(readingLayout, readingMode, showWordGloss)
  const showRail = showsPreviewAyahRail(readingLayout)
  const arabicMarks = verseNumberScript === 'arabic'
  const mark1 = formatAyahNumberMark(SAMPLE_AYAH_1, arabicMarks)
  const mark2 = formatAyahNumberMark(SAMPLE_AYAH_2, arabicMarks)
  const markClass = arabicMarks ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'
  const markDir = arabicMarks ? undefined : ('ltr' as const)
  const mushafMark91 = formatMushafAyahMark(MUSHAF_AYAH_1, arabicMarks)
  const mushafMark92 = formatMushafAyahMark(MUSHAF_AYAH_2, arabicMarks)
  const mushafSizerMark91 = formatMushafAyahMark(MUSHAF_AYAH_1, true)
  const mushafSizerMark92 = formatMushafAyahMark(MUSHAF_AYAH_2, true)
  const mushafMarkClass = arabicMarks ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'
  const railBars = symbolicAyahBarCount(PREVIEW_RAIL_AYAHS)

  return (
    <div
      className={[
        'reading-preview',
        readingLayout === 'mushaf' ? 'reading-preview--mushaf' : '',
        showRail ? `reading-preview--rail-${ayahSelectorSide}` : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {showRail ? (
        <div
          className={`reading-preview__rail reading-preview__rail--${ayahSelectorSide}`}
          aria-hidden="true"
        >
          {Array.from({ length: railBars }, (_, index) => (
            <i key={index} data-focus={index === 0 ? 'true' : undefined} />
          ))}
        </div>
      ) : null}
      <div className="reading-preview__lock">
        <div className="reading-preview__sizer" aria-hidden="true">
          <div className="reading-preview__words" dir="rtl">
            {SAMPLE_WORDS.map((word) => (
              <span key={word.arabic} className="reading-preview__word">
                <span className="reading-preview__arabic">{word.arabic}</span>
                <span className="reading-preview__gloss">{word.gloss}</span>
              </span>
            ))}
            <span className="ayah-mark">{formatAyahNumberMark(SAMPLE_AYAH_1, true)}</span>
          </div>
          <p className="reading-preview__english">{SAMPLE_ENGLISH}</p>
          <PageBreak page={SAMPLE_PAGE} script="both" />
        </div>
        <div className="reading-preview__sizer reading-preview__sizer--mushaf" aria-hidden="true">
          <p className="reading-preview__head">{MUSHAF_HEAD}</p>
          <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
            {MUSHAF_LINE_1}{' '}
            <span className="ayah-mark">{mushafSizerMark91}</span>
          </p>
          <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
            {MUSHAF_LINE_2}
          </p>
          <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
            {MUSHAF_LINE_3}{' '}
            <span className="ayah-mark">{mushafSizerMark92}</span>
          </p>
          <MushafFolio page={MUSHAF_PAGE} script="both" />
        </div>
        <div
          className={[
            'reading-preview__live',
            readingLayout === 'mushaf' ? 'reading-preview__live--mushaf' : '',
          ]
            .filter(Boolean)
            .join(' ')}
        >
          {readingLayout === 'mushaf' ? (
            <>
              <p className="reading-preview__head">{MUSHAF_HEAD}</p>
              <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
                {MUSHAF_LINE_1}{' '}
                <span className={mushafMarkClass} dir={markDir}>
                  {mushafMark91}
                </span>
              </p>
              <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
                {MUSHAF_LINE_2}
              </p>
              <p className="reading-preview__arabic reading-preview__arabic--mushaf" dir="rtl">
                {MUSHAF_LINE_3}{' '}
                <span className={mushafMarkClass} dir={markDir}>
                  {mushafMark92}
                </span>
              </p>
              <MushafFolio page={MUSHAF_PAGE} script={pageNumberScript} />
            </>
          ) : englishOnly ? (
            <>
              <p className="reading-preview__english reading-preview__english--lyric" dir="ltr">
                {SAMPLE_ENGLISH}{' '}
                <span className={markClass} dir={markDir}>
                  {mark1}
                </span>
              </p>
              <PageBreak page={SAMPLE_PAGE} script={pageNumberScript} />
              <p className="reading-preview__english reading-preview__english--lyric" dir="ltr">
                {SAMPLE_ENGLISH_2}{' '}
                <span className={markClass} dir={markDir}>
                  {mark2}
                </span>
              </p>
            </>
          ) : (
            <>
              {showGloss ? (
                <div className="reading-preview__words" dir="rtl">
                  {SAMPLE_WORDS.map((word) => (
                    <span key={word.arabic} className="reading-preview__word">
                      <span className="reading-preview__arabic">{word.arabic}</span>
                      <span className="reading-preview__gloss">{word.gloss}</span>
                    </span>
                  ))}
                  <span className={markClass} dir={markDir}>
                    {mark1}
                  </span>
                </div>
              ) : (
                <p className="reading-preview__arabic" dir="rtl">
                  {SAMPLE_ARABIC_1}{' '}
                  <span className={markClass} dir={markDir}>
                    {mark1}
                  </span>
                </p>
              )}
              {!arabicOnly ? (
                <p className="reading-preview__english">{SAMPLE_ENGLISH}</p>
              ) : null}
              <PageBreak page={SAMPLE_PAGE} script={pageNumberScript} />
              {arabicOnly ? (
                <p className="reading-preview__arabic" dir="rtl">
                  {SAMPLE_ARABIC_2}{' '}
                  <span className={markClass} dir={markDir}>
                    {mark2}
                  </span>
                </p>
              ) : null}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
