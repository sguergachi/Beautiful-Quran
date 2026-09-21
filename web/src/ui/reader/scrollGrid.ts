/**
 * The Scroll reader's grid — Android `ScrollGrid`. Every paper figure is a
 * whole number of [UNIT] px (the web treats 1px as 1dp). Three vertical rules:
 * 38 left, the centre axis, 38 right. Text never sits in the margins.
 */
export const SCROLL_UNIT = 4

/** Both margins. The ribbon gutter is the authority; the rail side matches it. */
export const SCROLL_MARGIN = 38

export const VERSE_PAD = SCROLL_UNIT * 4
export const VERSE_GAP = SCROLL_UNIT * 6
export const VERSE_GAP_ENGLISH = SCROLL_UNIT * 4
export const VOICE_GAP = SCROLL_UNIT * 3
export const RIBBON_TIP = VERSE_PAD + 10

export const OPENING_HEAD = SCROLL_UNIT * 10
export const OPENING_FOOT = SCROLL_UNIT * 8
export const OPENING_FOOT_BEFORE_BASMALAH = SCROLL_UNIT * 2
export const ROSETTE = 52
export const ROSETTE_TO_TITLE = SCROLL_UNIT * 4
export const TITLE_TO_SUBTITLE = 0
export const SUBTITLE_TO_META = SCROLL_UNIT

export const BASMALAH_HEAD = SCROLL_UNIT * 6
export const BASMALAH_FOOT = SCROLL_UNIT * 6

export const FOLIO_HEAD = 0
export const FOLIO_FOOT = SCROLL_UNIT * 6
export const FOLIO_HEAD_ENGLISH = VERSE_GAP - VERSE_GAP_ENGLISH
export const FOLIO_BAND = SCROLL_UNIT * 4

export const INVITE_HEAD = SCROLL_UNIT * 16
export const INVITE_LABEL_TOP = INVITE_HEAD - SCROLL_UNIT * 4
export const INVITE_FOOT = SCROLL_UNIT * 20
export const INVITE_PILL_TOP = SCROLL_UNIT * 6
export const PILL_INSET = SCROLL_UNIT * 6

/**
 * Where a default top-bar glyph's ink starts, measured on device. The reader
 * shifts each group so that ink stands on the 38px text rules.
 */
export const TOP_BAR_START_INK = 20.6
export const TOP_BAR_END_INK = 22.1
