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
  /** Lane's source marks — `(S, K)`, `(Msb,)` — drawn quieter than the gloss. */
  isCitation?: boolean
}

/** One spaced unit of the article: an optional Form label, then body prose. */
export interface LexiconBlock {
  form: string | null
  text: string
}

/** Characters of Lane shown before the reader asks for the whole article. */
export const LEXICON_PREVIEW_CHARS = 1_400

/** Arabic block, Arabic Supplement/Extended-A, and the presentation forms. */
const ARABIC = /[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]/
const ALPHANUMERIC = /[\p{L}\p{N}]/u
/** Gloss after Lane punctuation or right after an Arabic headword. */
const GLOSS_OPEN =
  /(?<=[;:.,)\]]|[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]) (?=(?:He|It|She|They|A|An|To|The|One)\b)/
const FORM_HEAD = /^Form (\d+)\.\s*/
const FORM_SPLIT = /(?=Form \d+\.)/
const BLOCK_SPLIT = /\n\n+|\n(?=•)/
const PAREN = /\([^()\n]*\)/g
/** Bare "see …" cross-refs — not the ones already inside `(see …)`. */
const SEE_REF = /(?<!\()\b[Ss]ee\b[^.!\n]*(?:[.!])?/g
const LATIN_WORD = /[A-Za-z]+/g
const EDITORIAL_MARK = /^[a-z]+:$/

/**
 * Gives Lane's article a readable shape without rewriting his words.
 *
 * Each `Form N.` label gets its own line, and the first morphology→gloss
 * pivot under that form (`) He wrote`, `) It (a thing)`) becomes a paragraph
 * break so the preview is not one unbroken wall of citations.
 */
export function lexiconReflow(text: string): string {
  if (!text) return text
  return text
    .split(FORM_SPLIT)
    .map((section) => {
      const head = section.match(FORM_HEAD)
      if (!head) return section
      const label = `Form ${head[1]}.`
      let body = section.slice(head[0].length)
      const gloss = body.match(GLOSS_OPEN)
      if (gloss && gloss.index !== undefined && gloss.index < 500) {
        body = `${body.slice(0, gloss.index)}\n\n${body.slice(gloss.index + gloss[0].length)}`
      }
      return `${label}\n${body}`
    })
    .join('')
    .replace(/\n{3,}/g, '\n\n')
}

/** Reflowed article, optionally cut to the preview budget. */
export function lexiconArticleText(text: string, expanded: boolean): string {
  const reflowed = lexiconReflow(text)
  return expanded ? reflowed : lexiconPreview(reflowed)
}

/**
 * Spaced units for the page: a Form heading when Lane opens a measure, then
 * the prose beneath it. Sense / paragraph breaks become quiet air between
 * blocks — no bullet marks. `text` should already be `lexiconReflow`'d (or
 * come from `lexiconArticleText`).
 */
export function lexiconBlocks(text: string): LexiconBlock[] {
  return text
    .split(BLOCK_SPLIT)
    .map((chunk) => chunk.trim().replace(/^•\s*/, ''))
    .filter(Boolean)
    .map((trimmed) => {
      const head = trimmed.match(FORM_HEAD)
      if (head) {
        return { form: `Form ${head[1]}.`, text: trimmed.slice(head[0].length).trim() }
      }
      return { form: null, text: trimmed }
    })
}

/**
 * Parentheses that are Lane's source marks rather than English asides.
 *
 * `(S, K)` cites lexicographers; `(tropical:)` is editorial; `(a thing)`
 * glosses the subject and stays at body ink.
 */
export function isLaneCitation(inner: string): boolean {
  if (ARABIC.test(inner)) return false
  if (EDITORIAL_MARK.test(inner)) return true
  for (const word of inner.match(LATIN_WORD) ?? []) {
    if (word.length >= 4 && word[0] === word[0].toLowerCase()) return false
  }
  return true
}

/**
 * Splits `text` into alternating Latin, Arabic, and quiet runs.
 *
 * Source marks and "see …" cross-references are peeled first so they can
 * recede; neutrals otherwise stay with the run they follow.
 */
export function lexiconRuns(text: string): LexiconRun[] {
  if (!text) return []
  type Quiet = { start: number; end: number; value: string }
  const quiets: Quiet[] = []
  for (const match of text.matchAll(PAREN)) {
    const start = match.index ?? 0
    const inner = match[0].slice(1, -1)
    if (isLaneCitation(inner)) {
      quiets.push({ start, end: start + match[0].length, value: match[0] })
    }
  }
  for (const match of text.matchAll(SEE_REF)) {
    const start = match.index ?? 0
    const end = start + match[0].length
    if (quiets.some((q) => start >= q.start && start < q.end)) continue
    quiets.push({ start, end, value: match[0] })
  }
  quiets.sort((a, b) => a.start - b.start)

  const runs: LexiconRun[] = []
  let i = 0
  for (const quiet of quiets) {
    if (quiet.start < i) continue
    if (quiet.start > i) runs.push(...splitScripts(text.slice(i, quiet.start)))
    runs.push({ text: quiet.value, isArabic: false, isCitation: true })
    i = quiet.end
  }
  if (i < text.length) runs.push(...splitScripts(text.slice(i)))
  return runs
}

function splitScripts(text: string): LexiconRun[] {
  if (!text) return []
  const runs: LexiconRun[] = []
  let current = ''
  let arabic: boolean | null = null

  const flush = () => {
    if (!current || arabic === null) return
    runs.push({ text: current, isArabic: arabic })
    current = ''
  }

  for (const char of text) {
    const script = ARABIC.test(char) ? true : ALPHANUMERIC.test(char) ? false : null
    if (script !== null && arabic !== null && script !== arabic) flush()
    if (script !== null) arabic = script
    current += char
  }
  flush()
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
