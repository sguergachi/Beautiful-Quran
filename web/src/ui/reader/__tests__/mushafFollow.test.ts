import { describe, expect, it } from 'vitest'
import { juzOf, mushafLeadsTurn, mushafTurnLeadDelayMs, mushafVoicePage, mushafWordPage, type PageMap } from '../mushafFollow'

const w = (surah_id: number, ayah_number: number, position: number) => ({ surah_id, ayah_number, position })
// Page 10 ends inside 2:20; page 11 carries the rest of it.
const pages = new Map<number, ReturnType<typeof w>[]>([
  [10, [w(2, 19, 1), w(2, 20, 1), w(2, 20, 2)]],
  [11, [w(2, 20, 3), w(2, 21, 1)]],
  [12, [w(3, 1, 1)]],
])
const map: PageMap = {
  pageOfAyah: (s, a) => s === 2 ? (a <= 20 ? 10 : 11) : s === 3 ? 12 : null,
  pageWords: (p) => pages.get(p) ?? [],
}
const voice = { reciting: true, inkAyah: 20, activeAyah: 20, activeWord: { ayah: 20, wordPosition: 3 } }

describe('mushafFollow', () => {
  it('follows the word, not the verse: a carried verse turns mid-verse', () => {
    expect(mushafWordPage(map, 2, 20, 2)).toBe(10)
    expect(mushafWordPage(map, 2, 20, 3)).toBe(11)
    expect(mushafVoicePage(map, 2, voice)).toBe(11)
    expect(mushafVoicePage(map, 2, { ...voice, activeWord: null })).toBe(10)
    expect(mushafVoicePage(map, 2, { ...voice, reciting: false })).toBe(null)
    expect(mushafVoicePage(map, 2, { ...voice, inkAyah: 0, activeWord: null })).toBe(10)
  })

  it("leads the turn only from the leaf's last word into its own chapter", () => {
    expect(mushafLeadsTurn(map, 10, 604, 2, { ayah: 20, wordPosition: 2 })).toBe(true)
    expect(mushafLeadsTurn(map, 10, 604, 2, { ayah: 20, wordPosition: 1 })).toBe(false)
    expect(mushafLeadsTurn(map, 11, 604, 2, { ayah: 21, wordPosition: 1 })).toBe(false)
  })

  it("starts the turn 500 ms before the word ends, at the voice's speed", () => {
    expect(mushafTurnLeadDelayMs(1200, 1)).toBe(700)
    expect(mushafTurnLeadDelayMs(1200, 2)).toBe(100)
    expect(mushafTurnLeadDelayMs(300, 1)).toBe(0)
  })

  it('knows the juzʾ of a verse', () => {
    expect(juzOf(1, 1)).toBe(1)
    expect(juzOf(2, 141)).toBe(1)
    expect(juzOf(2, 142)).toBe(2)
    expect(juzOf(114, 6)).toBe(30)
  })
})
