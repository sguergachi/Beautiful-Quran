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
        developerMode: true,
      }),
    ).toBe(true)
    expect(
      shouldShowAyahRailTip({
        educationGuidesEnabled: false,
        developerMode: true,
      }),
    ).toBe(false)
    dismissEducation('ayah_rail')
    expect(
      shouldShowAyahRailTip({
        educationGuidesEnabled: true,
        developerMode: true,
      }),
    ).toBe(false)
  })

  it('keeps lessons to developer mode, as Android', () => {
    expect(shouldShowAyahRailTip({ educationGuidesEnabled: true, developerMode: false })).toBe(false)
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        developerMode: false,
        annotationsEnabled: true,
        nowBookmarked: true,
      }),
    ).toBe(false)
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        developerMode: true,
        annotationsEnabled: false,
        nowBookmarked: true,
      }),
    ).toBe(false)
  })

  it('gates the bookmark-note lesson on a fresh mark', () => {
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        developerMode: true,
        annotationsEnabled: true,
        nowBookmarked: true,
      }),
    ).toBe(true)
    expect(
      shouldShowBookmarkTip({
        educationGuidesEnabled: true,
        developerMode: true,
        annotationsEnabled: true,
        nowBookmarked: false,
      }),
    ).toBe(false)
  })
})
