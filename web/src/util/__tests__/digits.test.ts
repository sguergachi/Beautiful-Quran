import { describe, expect, it } from 'vitest'
import {
  formatAyahNumberMark,
  formatReaderDigits,
  mushafFolioLayout,
  pageFolioLayout,
  toArabicIndic,
} from '../digits'

describe('toArabicIndic', () => {
  it('maps Western digits to Arabic-Indic', () => {
    expect(toArabicIndic(1)).toBe('١')
    expect(toArabicIndic(10)).toBe('١٠')
    expect(toArabicIndic(286)).toBe('٢٨٦')
  })
})

describe('formatReaderDigits', () => {
  it('uses Arabic-Indic when requested', () => {
    expect(formatReaderDigits(7, true)).toBe('٧')
  })

  it('uses Western digits for English-only', () => {
    expect(formatReaderDigits(7, false)).toBe('7')
    expect(formatReaderDigits(114, false)).toBe('114')
  })
})

describe('formatAyahNumberMark', () => {
  const wj = '\u2060'

  it('uses RTL bracket order with Arabic-Indic digits', () => {
    expect(formatAyahNumberMark(12, true)).toBe(`﴿${wj}١${wj}٢${wj}﴾`)
  })

  it('emits the opposite code points so LTR mirroring paints cups toward the digits', () => {
    const lri = '\u2066'
    const pdi = '\u2069'
    expect(formatAyahNumberMark(12, false)).toBe(
      `${lri}${wj}﴾${wj}1${wj}2${wj}﴿${wj}${pdi}`,
    )
  })

  it('LTR-isolates English marks so an RTL line cannot flip the brackets', () => {
    const mark = formatAyahNumberMark(1, false)
    expect(mark.startsWith('\u2066')).toBe(true)
    expect(mark.endsWith('\u2069')).toBe(true)
  })

  it('glues mark characters so they cannot wrap mid-unit', () => {
    const mark = formatAyahNumberMark(3, false)
    expect(mark.includes('﴾3')).toBe(false)
    expect(mark.includes('3﴿')).toBe(false)
  })
})

describe('pageFolioLayout', () => {
  it('places both scripts at opposite ends', () => {
    expect(pageFolioLayout(12, 'both')).toEqual({
      leading: '12',
      trailing: '١٢',
      centered: false,
    })
  })

  it('centres a single script', () => {
    expect(pageFolioLayout(12, 'english')).toEqual({
      leading: '12',
      trailing: null,
      centered: true,
    })
    expect(pageFolioLayout(12, 'arabic')).toEqual({
      leading: '١٢',
      trailing: null,
      centered: true,
    })
  })
})

describe('mushafFolioLayout', () => {
  it('centres both scripts on a diamond', () => {
    expect(mushafFolioLayout(330, 'both')).toEqual({
      western: '330',
      arabic: '٣٣٠',
      diamond: true,
    })
  })

  it('centres a single script with no diamond', () => {
    expect(mushafFolioLayout(330, 'english')).toEqual({
      western: '330',
      arabic: null,
      diamond: false,
    })
    expect(mushafFolioLayout(330, 'arabic')).toEqual({
      western: null,
      arabic: '٣٣٠',
      diamond: false,
    })
  })
})
