import type { PageNumberScript } from '../../data/settings'
import { pageFolioLayout } from '../../util/digits'

/**
 * Subtle mushaf page break — thin gold hairline. Both scripts sit at opposite
 * ends; a single script centres that figure between equal rules.
 */
export function PageBreak({
  page,
  script = 'both',
}: {
  page: number
  script?: PageNumberScript
}) {
  const folio = pageFolioLayout(page, script)
  return (
    <div
      className={`page-break${folio.centered ? ' page-break--western-only' : ''}`}
      role="separator"
      aria-label={`Page ${page}`}
    >
      {folio.centered ? (
        <>
          <span className="page-break__line" aria-hidden="true" />
          <span className="page-break__num" lang={script === 'arabic' ? 'ar' : undefined}>
            {folio.leading}
          </span>
          <span className="page-break__line" aria-hidden="true" />
        </>
      ) : (
        <>
          <span className="page-break__num">{folio.leading}</span>
          <span className="page-break__line" aria-hidden="true" />
          <span className="page-break__num" lang="ar">
            {folio.trailing}
          </span>
        </>
      )}
    </div>
  )
}
