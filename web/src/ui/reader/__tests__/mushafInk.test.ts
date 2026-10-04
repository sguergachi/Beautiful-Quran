import { describe, expect, it } from 'vitest'
import type { ActiveWord } from '../../../data/models'
import { InkState } from '../InkEngine'
import { MUSHAF_INK_IDLE, mushafMarkWaits, mushafTokenInk, type MushafInk } from '../mushafInk'

const word = (ayah: number, wordPosition: number, extra: Partial<ActiveWord> = {}): ActiveWord => ({
  ayah,
  wordPosition,
  durationMs: 600,
  isRepeat: false,
  highWater: wordPosition,
  repeatStart: wordPosition,
  activation: 0,
  ...extra,
})

const voice = (extra: Partial<MushafInk>): MushafInk => ({
  reciting: true,
  inkAyah: 5,
  leadAyah: 5,
  activeWord: word(5, 3),
  inkAyahRead: true,
  speed: 1,
  ...extra,
})

const at = (ayah: number, position: number, surahId = 2) => ({ surahId, ayah, position })
const state = (token: ReturnType<typeof at>, ink: MushafInk, live = true) =>
  mushafTokenInk(token, 2, ink, live).state

describe('mushaf leaf ink', () => {
  it('runs the karaoke wash through the voiced verse', () => {
    const ink = voice({})
    expect(state(at(5, 2), ink)).toBe(InkState.Recited)
    expect(state(at(5, 3), ink)).toBe(InkState.Active)
    expect(state(at(5, 4), ink)).toBe(InkState.Upcoming)
  })

  it('keeps what has been read in full ink and holds what is to come under paper', () => {
    const ink = voice({})
    expect(state(at(4, 9), ink)).toBe(InkState.Plain)
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
    // A chapter sharing the leaf stands before or after the whole of this one.
    expect(state(at(200, 1, 1), ink)).toBe(InkState.Plain)
    expect(state(at(1, 1, 3), ink)).toBe(InkState.Upcoming)
  })

  it('softens the next verse before the audio hands off, without giving it the wash', () => {
    const ink = voice({ leadAyah: 6 })
    expect(state(at(5, 3), ink)).toBe(InkState.Active)
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
    expect(state(at(7, 1), ink)).toBe(InkState.Upcoming)
  })

  it('waits under paper until the verse has its first word', () => {
    const ink = voice({ activeWord: null, inkAyahRead: false })
    expect(state(at(5, 1), ink)).toBe(InkState.Upcoming)
  })

  it('keeps a verse inked through its audio tail, after its last word', () => {
    const ink = voice({ activeWord: null, inkAyahRead: true })
    expect(state(at(5, 1), ink)).toBe(InkState.Recited)
    expect(state(at(5, 9), ink)).toBe(InkState.Recited)
    // The verse after it is still to come.
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
  })

  it('does not ink the lead verse early because the last one was read', () => {
    const ink = voice({ activeWord: null, inkAyahRead: true, leadAyah: 6 })
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
  })

  it('carries the orange repeat chain', () => {
    const ink = voice({ activeWord: word(5, 3, { isRepeat: true, repeatStart: 2, highWater: 4 }) })
    expect(mushafTokenInk(at(5, 2), 2, ink, true).repeat).toBe(true)
    expect(mushafTokenInk(at(5, 3), 2, ink, true).repeat).toBe(true)
    expect(mushafTokenInk(at(5, 4), 2, ink, true)).toEqual({ state: InkState.Recited, repeat: false })
  })

  it('holds the whole leaf back while the basmalah is recited', () => {
    const ink = voice({ inkAyah: 0, leadAyah: null, activeWord: null })
    expect(state(at(1, 1), ink)).toBe(InkState.Upcoming)
  })

  it('leaves a browsed leaf, and a silent one, in plain ink', () => {
    expect(state(at(6, 1), voice({}), false)).toBe(InkState.Plain)
    expect(state(at(5, 3), MUSHAF_INK_IDLE)).toBe(InkState.Plain)
  })
})

describe('mushaf verse numbers', () => {
  const waits = (ayah: number, ink: MushafInk, live = true, surahId = 2) =>
    mushafMarkWaits({ surahId, ayah }, 2, ink, live)

  it('lights the number of the verse the voice has just started', () => {
    // First word of the verse: its last word is still under paper.
    const ink = voice({ activeWord: word(5, 1), inkAyahRead: false })
    expect(state(at(5, 9), ink)).toBe(InkState.Upcoming)
    expect(waits(5, ink)).toBe(false)
  })

  it('lights it before the first word, as soon as the verse owns the voice', () => {
    expect(waits(5, voice({ activeWord: null, inkAyahRead: false }))).toBe(false)
  })

  it('keeps the numbers of verses still to come under paper, and those read lit', () => {
    const ink = voice({})
    expect(waits(6, ink)).toBe(true)
    expect(waits(4, ink)).toBe(false)
    expect(waits(1, ink, true, 3)).toBe(true)
    expect(waits(200, ink, true, 1)).toBe(false)
  })

  it('lights the next verse\'s number with the lead, ahead of the hand-off', () => {
    const ink = voice({ leadAyah: 6 })
    expect(waits(6, ink)).toBe(false)
    expect(waits(7, ink)).toBe(true)
  })

  it('leaves every number lit on a leaf away from the voice, or with no voice', () => {
    expect(waits(9, voice({}), false)).toBe(false)
    expect(waits(9, MUSHAF_INK_IDLE)).toBe(false)
  })
})
