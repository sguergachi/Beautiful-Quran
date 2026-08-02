import { afterEach, describe, expect, it } from 'vitest'
import {
  dismissEducation,
  isEducationDismissed,
  rearmEducation,
  shouldShowAyahRailTip,
  shouldShowBookmarkNoteTip,
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

  it('gates the ayah-rail tip on developer mode + guides + dismiss', () => {
    expect(
      shouldShowAyahRailTip({
        developerMode: true,
        educationGuidesEnabled: true,
      }),
    ).toBe(true)
    expect(
      shouldShowAyahRailTip({
        developerMode: false,
        educationGuidesEnabled: true,
      }),
    ).toBe(false)
    dismissEducation('ayah_rail')
    expect(
      shouldShowAyahRailTip({
        developerMode: true,
        educationGuidesEnabled: true,
      }),
    ).toBe(false)
  })

  it('gates the bookmark-note tip on a fresh mark', () => {
    expect(
      shouldShowBookmarkNoteTip({
        developerMode: true,
        educationGuidesEnabled: true,
        nowBookmarked: true,
      }),
    ).toBe(true)
    expect(
      shouldShowBookmarkNoteTip({
        developerMode: true,
        educationGuidesEnabled: true,
        nowBookmarked: false,
      }),
    ).toBe(false)
  })
})
