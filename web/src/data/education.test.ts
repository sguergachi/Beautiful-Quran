import { afterEach, describe, expect, it } from 'vitest'
import {
  dismissEducation,
  isEducationDismissed,
  rearmEducation,
  shouldShowAyahRailTip,
  shouldShowBookmarkTip,
} from './education'

afterEach(() => {
  rearmEducation()
})

describe('education guides', () => {
  it('defaults lessons undismissed and rearms after dismiss', () => {
    expect(isEducationDismissed('ayah_rail')).toBe(false)
    dismissEducation('ayah_rail')
    expect(isEducationDismissed('ayah_rail')).toBe(true)
    rearmEducation()
    expect(isEducationDismissed('ayah_rail')).toBe(false)
  })

  it('offers the rail to readers with hints on, once', () => {
    expect(
      shouldShowAyahRailTip({
        educationGuidesEnabled: true,
      }),
    ).toBe(true)
    expect(
      shouldShowAyahRailTip({
        educationGuidesEnabled: false,
      }),
    ).toBe(false)
    dismissEducation('ayah_rail')
    expect(
      shouldShowAyahRailTip({
        educationGuidesEnabled: true,
      }),
    ).toBe(false)
  })

  it('gates the saved-bookmark lesson on a fresh mark', () => {
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        nowBookmarked: true,
      }),
    ).toBe(true)
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        nowBookmarked: false,
      }),
    ).toBe(false)
  })
})
