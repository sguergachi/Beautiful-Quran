import { appStore } from '../../store/appStore'
import { VERSE_GAP_CHOICES, type Settings } from '../../data/settings'
import { verseGapLabel } from '../../data/playbackPolicy'
import { BackChevron } from '../kit/BackChevron'
import { PaperSwitch } from '../kit/PaperSwitch'
import { PaperSegmented } from '../kit/PaperSegmented'
import type { BrushCircleParams } from '../kit/brushMark'

/** How recitation carries on between verses — Android's Playback page. */
export function PlaybackScreen({
  settings,
  brushParams,
  paintToken,
  onBack,
}: {
  settings: Settings
  brushParams: BrushCircleParams
  paintToken: number
  onBack: () => void
}) {
  return (
    <div className="playback-settings">
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
        <BackChevron />
      </button>
      <h1>Playback</h1>
      <p className="settings-caption reciters-caption">
        How recitation carries on between verses.
      </p>

      <section className="settings-section">
        <PaperSwitch
          label="Bismillah once when repeating"
          checked={settings.skipRepeatedBasmalah}
          onChange={(on) => appStore.updateSettings({ skipRepeatedBasmalah: on })}
        />
        <p className="settings-caption">
          Repeating a chapter opens with it the first time only. Another chapter starts afresh.
        </p>
      </section>

      <section className="settings-section">
        <h2>Pause between verses</h2>
        <PaperSegmented
          aria-label="Pause between verses"
          value={String(settings.verseGapSeconds)}
          brushParams={brushParams}
          paintToken={paintToken}
          options={VERSE_GAP_CHOICES.map((seconds) => ({
            value: String(seconds),
            label: verseGapLabel(seconds),
          }))}
          onChange={(v) => appStore.updateSettings({ verseGapSeconds: Number(v) })}
        />
        <p className="settings-caption">Silence after each verse, to repeat it or reflect.</p>
      </section>
    </div>
  )
}
