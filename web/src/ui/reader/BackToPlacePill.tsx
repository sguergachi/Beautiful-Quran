/**
 * "Back to {chapter · ayah}" after a Root Viewer concordance jump — Android
 * `BackToOriginPill` / `IslamicBackToOriginCapsule`: an opaque paper capsule
 * with a gilt rim and a reed-pen arrow that inks in, the stadium twin of the
 * return-to-ayah roundel. Tap returns; a hand move arms the 30 s fade.
 */
import {
  backToPlaceAyahLabel,
  backToPlaceChapterLabel,
  backToPlaceLabel,
} from '../root/backToPlace'
import { returnToPlace, useBackToPlaceTarget } from '../root/backToPlaceStore'
import './backToPlacePill.css'

export function BackToPlacePill() {
  const target = useBackToPlaceTarget()
  if (!target) return null
  return (
    <button
      type="button"
      className="back-to-place"
      aria-label={backToPlaceLabel(target)}
      onClick={returnToPlace}
    >
      <svg className="back-to-place-arrow" viewBox="0 0 28 28" aria-hidden="true">
        {/* Qalam arrow pointing back: shaft, then each barb. */}
        <path className="back-to-place-stroke s1" d="M23 14.4 C17 13.6 11 13.7 6.2 14" />
        <path className="back-to-place-stroke s2" d="M6.2 14 C8.6 12.2 10.6 10.4 12.2 8.4" />
        <path className="back-to-place-stroke s3" d="M6.2 14 C8.7 15.6 10.8 17.4 12.4 19.6" />
      </svg>
      <span className="back-to-place-chapter">{backToPlaceChapterLabel(target)}</span>
      <span className="back-to-place-sep" aria-hidden="true">
        {'  ·  '}
      </span>
      <span className="back-to-place-ayah">{backToPlaceAyahLabel(target)}</span>
    </button>
  )
}
