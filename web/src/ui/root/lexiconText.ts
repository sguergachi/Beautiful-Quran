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
  /(?<=[;:.,)\]]|[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]) (?=\[?(?:He|It|I|She|They|A|An|To|The|One)\b)/
/** Lane often sets the primary English sense in square brackets after the morph. */
const BRACKET_GLOSS = /\[([^\[\]]{12,500})\]/
const FORM_HEAD = /^Form (\d+)\.\s*/
const FORM_SPLIT = /(?=Form \d+\.)/
const BLOCK_SPLIT = /\n\n+|\n(?=•)/
const PAREN = /\([^()\n]*\)/g
/**
 * Bare Latin "see …" cross-refs — not the ones already inside `(see …)`.
 * Stops before Arabic so the target keeps the mushaf face and bidi doesn't
 * wrap `see` / `ظَلَعَ.` onto two lines.
 */
const SEE_REF =
  /(?<!\()\b[Ss]ee\b(?:(?![؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿])[^.!\n])*(?:[.!])?/g
const LATIN_WORD = /[A-Za-z]+/g
const EDITORIAL_MARK = /^[a-z]+:$/
const PRIMARY_BRACKET_LEAD = /^(?:He|It|I|She|They|A|An|To|The|One)\b/
const FORM_MARKER = /(?:^|\n)Form \d+\./
const SEE_FORM = /\bsee (\d+)\b/i
const SEE_LATTER = /\bsee the latter\b/i

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

/** How many `Form N.` measures Lane marks in the article. */
export function lexiconFormCount(text: string): number {
  return (text.match(/Form \d+\./g) ?? []).length
}

/**
 * A short English sense for the Root section, taken from Lane's Form 1
 * (or the Form his opening "see N" / "see the latter" points at).
 *
 * Uses only the opening sense (before later `•` senses), prefers his
 * bracketed primary gloss, then the first English lead after the morphology.
 * Returns null when the article has no readable English lead.
 */
export function lexiconRootSense(text: string, maxChars = 180): string | null {
  if (!text.trim()) return null
  const section = resolveSenseSection(lexiconReflow(text))
  if (!section) return null
  return senseLeadFromSection(section, maxChars)
}

/**
 * Follow Lane's Form-1 cross-refs (`see 4`, `see the latter`) so roots like
 * نور — where Form 1 only redirects to أَنَارَ — still yield a Root gloss.
 */
function resolveSenseSection(reflowed: string): string | null {
  if (!FORM_MARKER.test(reflowed)) {
    return looksLikeEnglishSense(reflowed) || BRACKET_GLOSS.test(reflowed) ? reflowed : null
  }
  let n = 1
  const visited = new Set<number>()
  while (!visited.has(n) && visited.size < 6) {
    visited.add(n)
    const section = formSection(reflowed, n)
    if (!section) break
    const redirect = openingFormRedirect(section)
    if (redirect === null) return section
    n = redirect === 0 ? n + 1 : redirect
  }
  return formSection(reflowed, 1)
}

function formSection(reflowed: string, n: number): string | null {
  const head = new RegExp(`(?:^|\\n)(Form ${n}\\.)`).exec(reflowed)
  if (!head || head.index === undefined) return null
  const start = head.index + (reflowed[head.index] === '\n' ? 1 : 0)
  const rest = reflowed.slice(start)
  const next = rest.slice(`Form ${n}.`.length).match(/\nForm \d+\./)
  if (!next || next.index === undefined) return rest
  return rest.slice(0, `Form ${n}.`.length + next.index)
}

/** `0` = see the latter; null = opening already has a sense. */
function openingFormRedirect(formSection: string): number | null {
  const open = formSection
    .replace(/^Form \d+\.\s*/, '')
    .split('\n•')[0]!
    .split('\n\n')[0]!
    .trim()
  if (!open || hasSenseLead(open)) return null
  const seeForm = open.match(SEE_FORM)
  if (seeForm) {
    const n = Number(seeForm[1])
    if (n >= 1 && n <= 15) return n
  }
  if (SEE_LATTER.test(open)) return 0
  return null
}

function hasSenseLead(text: string): boolean {
  const bracket = text.match(BRACKET_GLOSS)?.[1]?.trim()
  if (bracket && looksLikePrimaryBracketGloss(bracket)) return true
  return englishSenseLead(text) != null
}

function senseLeadFromSection(section: string, maxChars: number): string | null {
  const bullet = section.indexOf('\n•')
  const lead = bullet < 0 ? section : section.slice(0, bullet)

  const bracket = lead.match(BRACKET_GLOSS)?.[1]?.trim()
  if (bracket && looksLikePrimaryBracketGloss(bracket)) return shortenSense(bracket, maxChars)

  for (const block of lexiconBlocks(lead)) {
    const sense = englishSenseLead(block.text)
    if (sense) return shortenSense(sense, maxChars)
  }
  return null
}

function englishSenseLead(text: string): string | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  // Prefer an already-English paragraph (after reflow). Mid-string GLOSS_OPEN
  // would otherwise steal `, A,` inside Lane citations like `(S, A, Msb)`.
  const gloss = trimmed.match(GLOSS_OPEN)
  let body: string
  if (trimmed[0] === '[' || (!ARABIC.test(trimmed[0]!) && /[A-Z]/.test(trimmed[0]!))) {
    body = trimmed
  } else if (gloss && gloss.index !== undefined && gloss.index < 500) {
    body = trimmed.slice(gloss.index + gloss[0].length).trim()
  } else {
    return null
  }
  body = body.replace(/^\[/, '').replace(/^[\s\]]+/, '')
  return looksLikeEnglishSense(body) || /^[A-Z]/.test(body) ? body : null
}

function looksLikeEnglishSense(text: string): boolean {
  let latin = 0
  let arabic = 0
  for (const char of text) {
    if (/[A-Za-z]/.test(char)) latin++
    else if (ARABIC.test(char)) arabic++
  }
  return latin >= 12 && latin > arabic
}

function looksLikePrimaryBracketGloss(inner: string): boolean {
  if (!looksLikeEnglishSense(inner)) return false
  const lead = inner.trimStart()
  if (/^This is what/i.test(lead)) return false
  if (/^i\.\s*e\./i.test(lead)) return false
  return PRIMARY_BRACKET_LEAD.test(lead)
}

function shortenSense(text: string, maxChars: number): string {
  let sense = text.trim().replace(/^\[/, '').replace(/\]+$/, '').trimEnd()
  // `: (` / `; (` cite after a gloss; `; syn.` / `; or` and sentence ends
  // need a longer lead so we don't chop ordinary English.
  const stops = [
    (() => {
      const match = /:\s*\(/.exec(sense)
      return match && match.index >= 8 ? match.index : undefined
    })(),
    (() => {
      const match = /;\s*\(/.exec(sense)
      return match && match.index >= 16 ? match.index : undefined
    })(),
    (() => {
      const match = /;\s*(?:syn\.|or\b|and ↓|see\b)/i.exec(sense)
      return match && match.index >= 16 ? match.index : undefined
    })(),
    (() => {
      const match = /[.!?](?:\s|$)/.exec(sense)
      return match && match.index >= 24 ? match.index : undefined
    })(),
  ].filter((index): index is number => index !== undefined)
  const earlyStop = stops.length ? Math.min(...stops) : undefined
  if (earlyStop !== undefined) {
    const ch = sense[earlyStop]
    sense = sense.slice(0, earlyStop + (ch === '.' || ch === '!' || ch === '?' ? 1 : 0))
  }
  sense = sense.replace(/\s*\([^()]*\)\s*$/, '').replace(/[:;,]+$/, '').trimEnd()
  if (sense.length <= maxChars) return sense
  const window = sense.slice(0, maxChars)
  const cut = window.lastIndexOf(' ')
  const at = cut > maxChars / 2 ? cut : maxChars
  return `${window.slice(0, at).replace(/[,;:]+$/, '').trimEnd()}…`
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
