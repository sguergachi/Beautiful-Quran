import { expect, it } from 'vitest'
import { cachedPaperCoverMask, cachedWashMask } from '../inkWash'
it('retains a soft mask until the actual completion frame', () => {
  for (const progress of [0.98, 0.999, 0.99999]) {
    // Clearing a paper mask while its cover is opaque paints a solid rectangle.
    expect(cachedPaperCoverMask(progress, 0.2661, true, 1.1092)).toContain(
      'linear-gradient',
    )
    expect(cachedWashMask(progress, 0.2661, false, 1.6)).toContain(
      'linear-gradient',
    )
  }
  expect(cachedPaperCoverMask(1, 0.2661, true, 1.1092)).toBe('none')
})
