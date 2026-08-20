import { appStore } from '../../store/appStore'
import type {
  AyahSelectorSide,
  PageNumberScript,
  ReadingMode,
  Settings,
  ThemeMode,
  VerseNumberScript,
} from '../../data/settings'
import {
  applyReadingMode,
  showsPreviewWordGloss,
  showsWordGlossChrome,
} from '../../data/customizePolicy'
import { formatAyahNumberMark } from '../../util/digits'
import { symbolicAyahBarCount } from '../reader/ayahRailMath'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSegmented } from '../kit/PaperSegmented'
import { PaperSwitch } from '../kit/PaperSwitch'
import { PageBreak } from '../reader/PageBreak'
import type { BrushCheckParams } from '../kit/brushCheck'
import type { BrushCircleParams } from '../kit/brushMark'
import { ThemeSwatches } from './themeSwatches'

const VIEW_OPTIONS = [
  { value: 'arabic_only' as const, label: 'Arabic' },
  { value: 'english_only' as const, label: 'English' },
  { value: 'arabic_english' as const, label: 'Both' },
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
          verseNumberScript={settings.verseNumberScript}
          pageNumberScript={settings.pageNumberScript}
          ayahSelectorSide={settings.ayahSelectorSide}
          showWordGloss={settings.showWordGloss}
        />
      </section>
      </div>

      <div className="customize-scroll">
          <section className="settings-section">
            <h2>View</h2>
            <PaperSegmented
              aria-label="View"
              value={settings.readingMode}
              brushParams={brushParams}
              paintToken={paintToken}
              options={VIEW_OPTIONS}
              onChange={(v) =>
                appStore.updateSettings(applyReadingMode(v as ReadingMode))
              }
            />
          </section>

          {showsWordGlossChrome(settings.readingMode) ? (
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
  verseNumberScript,
  pageNumberScript,
  ayahSelectorSide = 'left',
  showWordGloss = false,
}: {
  readingMode: ReadingMode
  verseNumberScript: VerseNumberScript
  pageNumberScript: PageNumberScript
  ayahSelectorSide?: AyahSelectorSide
  showWordGloss?: boolean
}) {
  const arabicOnly = readingMode === 'arabic_only'
  const englishOnly = readingMode === 'english_only'
  const showGloss = showsPreviewWordGloss(readingMode, showWordGloss)
  const arabicMarks = verseNumberScript === 'arabic'
  const mark1 = formatAyahNumberMark(SAMPLE_AYAH_1, arabicMarks)
  const mark2 = formatAyahNumberMark(SAMPLE_AYAH_2, arabicMarks)
  const markClass = arabicMarks ? 'ayah-mark' : 'ayah-mark ayah-mark--ltr'
  const markDir = arabicMarks ? undefined : ('ltr' as const)
  const railBars = symbolicAyahBarCount(PREVIEW_RAIL_AYAHS)

  return (
    <div
      className={`reading-preview reading-preview--rail-${ayahSelectorSide}`}
    >
      <div
        className={`reading-preview__rail reading-preview__rail--${ayahSelectorSide}`}
        aria-hidden="true"
      >
        {Array.from({ length: railBars }, (_, index) => (
          <i key={index} data-focus={index === 0 ? 'true' : undefined} />
        ))}
      </div>
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
        <div className="reading-preview__live">
          {englishOnly ? (
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
