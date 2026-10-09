import { appStore } from '../../store/appStore'

/** Continue listening, on the green wash. Android `ContinueRow`. */
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
        onPointerEnter={onPrepare}
        onPointerDown={onPrepare}
        onFocus={onPrepare}
        onClick={() => appStore.openReading(surahId, ayah || 1)}
      >
        <span className="continue-copy">
          <span className="continue-label">{label}</span>
          <span className="continue-target">
            {transliteration}
            {ayah > 0 ? ` · Ayah ${ayah}` : ''}
          </span>
        </span>
        <span className="continue-ar" lang="ar" dir="rtl">
          {arabic}
        </span>
      </button>
    </div>
  )
}
