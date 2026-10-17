import { describe, expect, it } from 'vitest'
import { chooseRepeat, repeatDraft, repeatSelection } from '../repeatPolicy'

describe('Android repeat sheet policy', () => {
  it('retains the instrument when two choices represent the same loop', () => {
    const range = { first: 3, last: 5 }
    expect(repeatDraft('range', range, 3, 10, 'range').choice).toBe('range')
    expect(repeatDraft('range', range, 3, 10, 'from_here').choice).toBe(
      'from_here',
    )
    expect(repeatDraft('range', range, 3, 10, null).choice).toBe('from_here')
    expect(
      repeatDraft('range', { first: 3, last: 3 }, 3, 10, null).choice,
    ).toBe('ayah')
  })
  it('carries count to bounds and back without discarding the chosen end', () => {
    const draft = { choice: 'from_here' as const, from: 1, to: 10, count: 4 }
    const bounds = chooseRepeat(draft, 'range', 3, 10)
    expect(bounds).toMatchObject({ from: 3, to: 6 })
    expect(chooseRepeat(bounds, 'from_here', 3, 10).count).toBe(4)
    expect(chooseRepeat({ ...bounds, to: 1 }, 'from_here', 3, 10).count).toBe(1)
  })
  it('includes the current ayah and clamps a final-ayah selection to one', () => {
    const draft = repeatDraft('off', null, 10, 10, null)
    expect(draft.count).toBe(1)
    expect(
      repeatSelection({ ...draft, choice: 'from_here', count: 99 }, 10, 10),
    ).toEqual({ mode: 'range', range: { first: 10, last: 10 } })
    expect(repeatSelection({ ...draft, choice: 'ayah' }, 3, 10)).toEqual({
      mode: 'range',
      range: { first: 3, last: 3 },
    })
  })
  it('turns off stale ranges and preserves whole-surah mode', () => {
    const draft = repeatDraft('off', null, 2, 7, null)
    expect(repeatSelection(draft, 2, 7)).toEqual({ mode: 'off', range: null })
    expect(repeatSelection({ ...draft, choice: 'surah' }, 2, 7)).toEqual({
      mode: 'surah',
      range: null,
    })
  })
})
