/**
 * Lane writes English prose with Arabic set inline ("inf. n. كِتَابٌ and
 * كِتَابَةٌ"), so an entry has to be drawn in two scripts at once: Latin runs
 * in the reading face, Arabic runs in the mushaf face at its own size.
 *
 * The mirror of Android `LexiconText.kt` — keep the two in step.
 */

export interface LexiconRun {
  text: string
  isArabic: boolean
}

/** Characters of Lane shown before the reader asks for the whole article. */
export const LEXICON_PREVIEW_CHARS = 1_400

/** Arabic block, Arabic Supplement/Extended-A, and the presentation forms. */
const ARABIC = /[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]/
const ALPHANUMERIC = /[\p{L}\p{N}]/u

/**
 * Splits `text` into alternating Latin and Arabic runs.
 *
 * Neutral characters — spaces, the commas and parentheses Lane sets around a
 * word, his ↓ reference arrow — carry no script of their own, so they stay in
 * the run they follow rather than starting a one-character run of their own.
 * Which font draws a comma is invisible; bidi ordering is resolved over the
 * whole paragraph, not per run, so the split only ever chooses a typeface.
 */
export function lexiconRuns(text: string): LexiconRun[] {
  if (!text) return []
  const runs: LexiconRun[] = []
  let current = ''
  let arabic: boolean | null = null

  for (const char of text) {
    const script = ARABIC.test(char) ? true : ALPHANUMERIC.test(char) ? false : null
    if (script !== null && arabic !== null && script !== arabic) {
      runs.push({ text: current, isArabic: arabic })
      current = ''
    }
    if (script !== null) arabic = script
    current += char
  }
  if (current) runs.push({ text: current, isArabic: arabic === true })
  return runs
}

/**
 * The opening of an article, cut at one of Lane's own divisions.
 *
 * Articles run from a paragraph to ~99,000 characters, so the section shows
 * its head and lets the reader unfold the rest. The cut prefers the last
 * sense break inside the budget, then a sentence end, so the preview never
 * stops mid-clause; a short article is returned whole.
 */
export function lexiconPreview(text: string, budget = LEXICON_PREVIEW_CHARS): string {
  if (text.length <= budget) return text
  const window = text.slice(0, budget)
  const cut =
    [window.lastIndexOf('\n•'), window.lastIndexOf('\n\n'), window.lastIndexOf('. ')].find(
      (index) => index > budget / 3,
    ) ?? budget
  return `${window.slice(0, cut).trimEnd().replace(/[,;—(]+$/, '')} …`
}
