import { appStore } from '../../store/appStore'
import { FontSizeControl } from '../kit/FontSizeControl'
import { BackChevron } from '../kit/BackChevron'
import type {
  AyahSelectorSide,
  PageNumberScript,
  ReadingMode,
  VerseNumberScript,
  Settings,
  ThemeMode,
} from '../../data/settings'
import {
  applyReadingLayout,
  applyReadingMode,
  MUSHAF_VIEW_MODES,
  showsWordGlossChrome,
} from '../../data/customizePolicy'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSegmented } from '../kit/PaperSegmented'
import { PaperSwitch } from '../kit/PaperSwitch'
import type { BrushCheckParams } from '../kit/brushCheck'
import type { BrushCircleParams } from '../kit/brushMark'
import { ThemeSwatches } from './themeSwatches'
import { ReadingPreview } from './ReadingPreview'

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
        <BackChevron />
      </button>
      <h1>Customize</h1>

      <section className="settings-section">
        <h2>Preview</h2>
        <ReadingPreview settings={settings} />
      </section>
      </div>

      <div className="customize-scroll">
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

          <section className="settings-section">
            <h2>Pages on larger screens</h2>
            <PaperSegmented
              aria-label="Pages on larger screens"
              value={settings.pagePresentation}
              brushParams={brushParams}
              paintToken={paintToken}
              options={[{ value: 'facing', label: 'Facing pages' }, { value: 'single', label: 'Single page' }]}
              onChange={(v) => appStore.updateSettings({ pagePresentation: v === 'single' ? 'single' : 'facing' })}
            />
            <p className="settings-caption">A single page gives the text more room on a portrait tablet.</p>
          </section>

          <section className="settings-section">
            <h2>Layout</h2>
            <PaperSegmented
              aria-label="Layout"
              value={settings.readingLayout}
              brushParams={brushParams}
              paintToken={paintToken}
              options={LAYOUT_OPTIONS}
              onChange={(v) =>
                appStore.updateSettings(
                  applyReadingLayout(settings, v === 'mushaf' ? 'mushaf' : 'scroll'),
                )
              }
            />
          </section>

          {/* Android Customize: text size sits between Layout and View,
              and only where the reader sets its own size (Scroll). */}
          {settings.readingLayout === 'scroll' ? (
            <section className="settings-section">
              <h2>Text size</h2>
              <FontSizeControl
                scale={settings.fontScale}
                onChange={(fontScale) => appStore.updateSettings({ fontScale })}
              />
            </section>
          ) : null}

          <section className="settings-section">
            <h2>View</h2>
            <PaperSegmented
              aria-label="View"
              value={settings.readingMode}
              brushParams={brushParams}
              paintToken={paintToken}
              options={
                settings.readingLayout === 'mushaf'
                  ? VIEW_OPTIONS.filter((option) => MUSHAF_VIEW_MODES.includes(option.value))
                  : VIEW_OPTIONS
              }
              onChange={(v) => {
                if (
                  settings.readingLayout === 'mushaf' &&
                  !MUSHAF_VIEW_MODES.includes(v as ReadingMode)
                ) {
                  return
                }
                appStore.updateSettings(applyReadingMode(v as ReadingMode))
              }}
            />
          </section>

          {settings.readingLayout === 'scroll' && showsWordGlossChrome(settings.readingMode) ? (
            <section className="settings-section settings-section-toggles">
              <PaperSwitch
                id="setting-translit"
                label="Transliteration"
                checked={settings.showTransliteration}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(checked) => appStore.updateSettings({ showTransliteration: checked })}
              />
              <PaperSwitch
                id="setting-translation"
                label="Ayah translation"
                checked={settings.showTranslation}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(checked) => appStore.updateSettings({ showTranslation: checked })}
              />
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

        {settings.readingLayout === 'mushaf' && settings.readingMode === 'arabic_only' ? null : (
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
        )}

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

      </div>
    </div>
  )
}
