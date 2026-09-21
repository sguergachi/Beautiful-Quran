import { describe, expect, it } from 'vitest'
import {
  BASMALAH_FOOT,
  BASMALAH_HEAD,
  FOLIO_BAND,
  FOLIO_FOOT,
  FOLIO_HEAD_ENGLISH,
  INVITE_FOOT,
  INVITE_HEAD,
  INVITE_PILL_TOP,
  OPENING_FOOT,
  OPENING_FOOT_BEFORE_BASMALAH,
  OPENING_HEAD,
  RIBBON_TIP,
  ROSETTE,
  SCROLL_MARGIN,
  SCROLL_UNIT,
  VERSE_GAP,
  VERSE_GAP_ENGLISH,
  VERSE_PAD,
  VOICE_GAP,
} from './scrollGrid'

describe('scroll grid', () => {
  it('hangs every paper figure on a 4px step', () => {
    for (const figure of [
      VERSE_PAD,
      VERSE_GAP,
      VERSE_GAP_ENGLISH,
      VOICE_GAP,
      OPENING_HEAD,
      OPENING_FOOT,
      OPENING_FOOT_BEFORE_BASMALAH,
      BASMALAH_HEAD,
      BASMALAH_FOOT,
      FOLIO_FOOT,
      FOLIO_HEAD_ENGLISH,
      FOLIO_BAND,
      INVITE_HEAD,
      INVITE_FOOT,
      INVITE_PILL_TOP,
    ]) {
      expect(figure % SCROLL_UNIT).toBe(0)
    }
  })

  it('matches the Android chapter rhythm', () => {
    expect(SCROLL_MARGIN).toBe(38)
    expect(VERSE_PAD + VERSE_GAP + VERSE_PAD).toBe(56)
    expect(VERSE_PAD + VERSE_GAP_ENGLISH + VERSE_PAD).toBe(48)
    expect(OPENING_FOOT_BEFORE_BASMALAH + BASMALAH_HEAD).toBe(32)
    expect(BASMALAH_FOOT + VERSE_PAD).toBe(40)
    expect(RIBBON_TIP).toBe(26)
    expect(ROSETTE).toBe(52)
  })
})
