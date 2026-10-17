import { appStore } from '../../store/appStore'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'

/** Android ContinuePill's resting shape, in the web's top continue area. */
export function ContinueRow({
  surahId,
  ayah,
  transliteration,
  arabic,
  onPrepare,
  label = 'Continue listening',
}: {
  surahId: number
  ayah: number
  transliteration: string
  arabic: string
  onPrepare: () => void
  label?: string
}) {
  return (
    <div className="continue-row">
      <button
        type="button"
        className="continue"
        aria-label={`${label}: ${transliteration}, ayah ${ayah || 1}`}
        onPointerEnter={onPrepare}
        onPointerDown={onPrepare}
        onFocus={onPrepare}
        onClick={() => appStore.openReading(surahId, ayah || 1)}
      >
        <span className="continue-ribbon" aria-hidden="true">
          <VerseBookmarkRibbon
            bookmarked={false}
            placeMarked
            focused={false}
            side="left"
            interactive={false}
            topInset={0}
            bottomGap={0}
            edgeInset={7.5}
            onToggle={() => false}
            decorative
          />
        </span>
        <span className="continue-number" aria-hidden="true">
          {surahId}
        </span>
        <span className="continue-copy">
          <span className="continue-label">Continue</span>
          <span className="continue-target">
            <span className="continue-name">{transliteration}</span>
            <span className="continue-ayah"> · Ayah {ayah || 1}</span>
          </span>
        </span>
        <span className="continue-ar" lang="ar" dir="rtl">
          {arabic}
        </span>
      </button>
    </div>
  )
}
