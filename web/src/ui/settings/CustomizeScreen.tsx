import { useSyncExternalStore, type ReactNode } from 'react'
import { appStore } from '../../store/appStore'
import { FontSizeControl } from '../kit/FontSizeControl'
import { BackChevron } from '../kit/BackChevron'
import type {
  AyahSelectorSide,
  EnglishLeafText,
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
  showsEnglishLeafChrome,
  showsScrollChrome,
  showsVerseNumberChrome,
  showsWordGlossChrome,
} from '../../data/customizePolicy'
import { BOOK_SPREAD_QUERY } from '../paper/bookSpread'
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

const ENGLISH_LEAF_OPTIONS: { value: EnglishLeafText; label: string }[] = [
  { value: 'translation', label: 'Translation' },
  { value: 'gloss', label: 'Word by word' },
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

function subscribeBookSpread(onChange: () => void): () => void {
  const query = window.matchMedia(BOOK_SPREAD_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

/** Facing pages only exist where the book spread can open. */
function useBookSpreadScreen(): boolean {
  return useSyncExternalStore(
    subscribeBookSpread,
    () => window.matchMedia(BOOK_SPREAD_QUERY).matches,
    () => false,
  )
}

/**
 * Android `CustomizeReveal`: a control joining or leaving the sheet. The paper
 * opens first and the ink arrives into it; leaving, the ink goes first and the
 * paper closes after. Rows below ride the same expansion.
 */
function CustomizeReveal({ visible, children }: { visible: boolean; children: ReactNode }) {
  return (
    <div className="customize-reveal" data-visible={visible} inert={!visible} aria-hidden={!visible || undefined}>
      <div className="customize-reveal-inner">{children}</div>
    </div>
  )
}

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
  const bookSpreadScreen = useBookSpreadScreen()
  const layout = settings.readingLayout
  const scroll = showsScrollChrome(layout)
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
            <h2>Layout</h2>
            <PaperSegmented
              aria-label="Layout"
              value={layout}
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
          <CustomizeReveal visible={scroll}>
            <section className="settings-section">
              <h2>Text size</h2>
              <FontSizeControl
                scale={settings.fontScale}
                onChange={(fontScale) => appStore.updateSettings({ fontScale })}
              />
            </section>
          </CustomizeReveal>

          <section className="settings-section">
            <h2>View</h2>
            <PaperSegmented
              aria-label="View"
              value={settings.readingMode}
              brushParams={brushParams}
              paintToken={paintToken}
              options={
                layout === 'mushaf'
                  ? VIEW_OPTIONS.filter((option) => MUSHAF_VIEW_MODES.includes(option.value))
                  : VIEW_OPTIONS
              }
              onChange={(v) => {
                if (layout === 'mushaf' && !MUSHAF_VIEW_MODES.includes(v as ReadingMode)) {
                  return
                }
                appStore.updateSettings(applyReadingMode(v as ReadingMode))
              }}
            />
          </section>

          {/* Which English the leaf is set from: only the English leaf asks. */}
          <CustomizeReveal visible={showsEnglishLeafChrome(layout, settings.readingMode)}>
            <section className="settings-section">
              <h2>English</h2>
              <PaperSegmented
                aria-label="English"
                value={settings.englishLeafText}
                brushParams={brushParams}
                paintToken={paintToken}
                options={ENGLISH_LEAF_OPTIONS}
                onChange={(englishLeafText) => appStore.updateSettings({ englishLeafText })}
              />
            </section>
          </CustomizeReveal>

          {/* The scroll layout's toggles: 20px stand-off, then even 12px rows. */}
          <CustomizeReveal visible={scroll}>
            <div className="customize-toggles">
              <CustomizeReveal visible={scroll && settings.readingMode === 'arabic_english'}>
                <div className="customize-toggle-group">
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
                </div>
              </CustomizeReveal>
              <CustomizeReveal visible={scroll && showsWordGlossChrome(settings.readingMode)}>
                <PaperSwitch
                  id="setting-gloss"
                  label="Word-by-word translation"
                  checked={settings.showWordGloss}
                  checkParams={checkParams}
                  paintToken={checkPaintToken}
                  onChange={(checked) => appStore.updateSettings({ showWordGloss: checked })}
                />
              </CustomizeReveal>
              <PaperSwitch
                id="setting-annotations"
                label="Verse annotations"
                checked={settings.annotationsEnabled}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(checked) => appStore.updateSettings({ annotationsEnabled: checked })}
              />
            </div>
          </CustomizeReveal>

          <CustomizeReveal visible={showsVerseNumberChrome(layout, settings.readingMode)}>
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
          </CustomizeReveal>

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

          <CustomizeReveal visible={scroll}>
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
          </CustomizeReveal>

          {/* Web-only: facing pages exist only where the book spread can open,
              so the choice is offered there alone, after the reading controls. */}
          {bookSpreadScreen ? (
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
          ) : null}

          <section className="settings-section">
            <h2>Sound</h2>
            <PaperSwitch
              id="setting-sound-effects"
              label="Sound effects"
              checked={settings.soundEffectsEnabled}
              checkParams={checkParams}
              paintToken={checkPaintToken}
              onChange={(checked) => appStore.updateSettings({ soundEffectsEnabled: checked })}
            />
          </section>
      </div>
    </div>
  )
}
