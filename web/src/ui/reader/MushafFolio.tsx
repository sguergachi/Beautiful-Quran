import type { PageNumberScript } from '../../data/settings'
import { mushafFolioLayout } from '../../util/digits'

/**
 * Mushaf leaf folio — quiet ink, always centred. A single script is the
 * number itself; both sit either side of a diamond. Matches Android
 * `MushafFolioMarks`.
 */
export function MushafFolio({
  page,
  script = 'both',
}: {
  page: number
  script?: PageNumberScript
}) {
  const folio = mushafFolioLayout(page, script)
  return (
    <div className="mushaf-folio" aria-label={`Page ${page}`}>
      {folio.western ? (
        <span
          className={
            folio.diamond ? 'mushaf-folio__western' : 'mushaf-folio__western mushaf-folio__solo'
          }
        >
          {folio.western}
        </span>
      ) : null}
      {folio.diamond ? <span className="mushaf-folio__diamond" aria-hidden="true" /> : null}
      {folio.arabic ? (
        <span
          className={
            folio.diamond ? 'mushaf-folio__arabic' : 'mushaf-folio__arabic mushaf-folio__solo'
          }
          lang="ar"
        >
          {folio.arabic}
        </span>
      ) : null}
    </div>
  )
}
