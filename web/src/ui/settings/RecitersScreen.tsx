import type { Reciter } from '../../data/models'
import { BackChevron } from '../kit/BackChevron'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { paperToggleHaptic } from '../kit/paperHaptics'

type ReciterChoicesProps = {
  reciters: Reciter[]
  selectedId: number
  onSelect: (id: number) => void
  favorites?: number[]
  onToggleFavorite?: (id: number) => void
}

/** Android's voice rows: a nuqta selects; a separate star keeps a favorite. */
export function ReciterChoices({ reciters, selectedId, onSelect, favorites, onToggleFavorite }: ReciterChoicesProps) {
  return (
    <PaperChoiceList
      aria-label="Reciter"
      value={String(selectedId)}
      options={reciters.map((reciter) => {
        const style = reciter.style !== 'Murattal' ? reciter.style : undefined
        const favorite = favorites?.includes(reciter.id) ?? false
        const name = style ? `${reciter.name} (${style})` : reciter.name
        return {
          value: String(reciter.id),
          label: reciter.name,
          description: style,
          trailing: onToggleFavorite ? (
            <button
              type="button"
              className="reciter-favorite"
              aria-label={`${favorite ? 'Remove' : 'Add'} ${name} ${favorite ? 'from' : 'to'} favorites`}
              aria-pressed={favorite}
              onClick={() => {
                paperToggleHaptic(!favorite)
                onToggleFavorite(reciter.id)
              }}
            >
              <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
                <path
                  d="m12 3 2.8 5.7 6.3.9-4.5 4.4 1.1 6.2-5.7-3-5.7 3 1.1-6.2L2.9 9.6l6.3-.9Z"
                  fill={favorite ? 'currentColor' : 'none'}
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          ) : undefined,
        }
      })}
      onChange={(id) => onSelect(Number(id))}
    />
  )
}

/** The complete catalog; the main Settings leaf carries only favorites. */
export function RecitersScreen({ onBack, ...choices }: ReciterChoicesProps & { onBack: () => void }) {
  return (
    <div className="reciters">
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
        <BackChevron />
      </button>
      <h1>Reciters</h1>
      <p className="settings-caption reciters-caption">
        Choose a voice. Star favorites to keep them on the main Settings leaf.
      </p>
      <section className="settings-section">
        <h2>All reciters</h2>
        <ReciterChoices {...choices} reciters={[...choices.reciters].sort((a, b) => a.name.localeCompare(b.name))} />
      </section>
    </div>
  )
}
