/**
 * The one-sheet deck's grid — Android's, at 1px = 1dp. Phones, and any
 * window too small to lie open as a book.
 *
 * Every sheet hangs its ink on two vertical rules, and the paper between
 * two pieces of ink is a whole number of [SHEET_UNIT] steps. A sheet's
 * margin says what kind of sheet it is:
 *
 * ```
 *  28  an information sheet — Home, Settings, Customize (Android's 28dp)
 *  24  a reference sheet — Bookmarks, the word viewer
 *  38  the scrolling chapter (reader/scrollGrid.ts)
 *  10  a Mushaf leaf, which is a printed page and keeps its own margin
 * ```
 *
 * The margins were a share of the window (4.5vw, clamped), so Settings
 * stood at 16px on a 360px phone and 18.5 on a 412px one, beside a Home that
 * stood at 28: the left edge moved as the sheets turned. They are fixed.
 * The desktop book has its own grid (one module, `--u`) and is not on this
 * one.
 *
 * The CSS carries these as custom properties (theme/styles.css); the test
 * beside this file holds the two together.
 */
export const SHEET_UNIT = 4

/** Both margins of an information sheet. Android `HomeStartInset`, SettingsScreen. */
export const SHEET_MARGIN = 28

/** Both margins of a reference sheet. Android BookmarksScreen, RootViewerScreen. */
export const REFERENCE_MARGIN = 24

/** Both margins of a Mushaf leaf. Android's leaf: "a bare 10dp". */
export const LEAF_MARGIN = 10

/**
 * Settings and Customize, top to bottom — Android SettingsScreen's figures.
 * Two of them are Android's own optical figures and stand off the step: the
 * back chevron sits 6 over the title it belongs to, and the colophon's
 * credits 18 under its mark.
 */
export const SETTINGS = {
  /** Paper over the back chevron, under the status bar. */
  head: SHEET_UNIT * 5,
  /** The back chevron's box. */
  chevron: SHEET_UNIT * 10,
  chevronToTitle: 6,
  titleToLabel: SHEET_UNIT * 9,
  /** A section's label to its first row. */
  labelToRows: SHEET_UNIT,
  /** Paper inside a row, above and below its ink. */
  rowPad: SHEET_UNIT * 2,
  /** One group to the next. */
  groupGap: SHEET_UNIT * 5,
  toColophon: SHEET_UNIT * 14,
  colophonToCredits: 18,
  foot: SHEET_UNIT * 12,
} as const
