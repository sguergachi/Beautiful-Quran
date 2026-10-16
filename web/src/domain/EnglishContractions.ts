import { foldEnglish } from './WordSearch'

const hosts = new Set([
  'i', 'you', 'he', 'she', 'it', 'we', 'they', 'that', 'there', 'here',
  'who', 'what', 'where', 'when', 'why', 'how', "y'all",
])
const negatives = new Set([
  'am', 'is', 'are', 'was', 'were', 'do', 'does', 'did', 'have', 'has', 'had',
  'could', 'would', 'should', 'must', 'may', 'might', 'need', 'dare', 'ought', 'used',
])
const questions = new Set(['who', 'what', 'where', 'when', 'why', 'how'])
const special: Record<string, string> = {
  "can't": 'can not', cannot: 'can not', "won't": 'will not', "shan't": 'shall not',
  "let's": 'let us', "y'all": 'you all', "ma'am": 'madam', "o'clock": 'of the clock',
  "'tis": 'it is', "'twas": 'it was', "'twere": 'it were',
}
interface Token { forms: string[]; start: number; end: number }

function expand(word: string): string[][] {
  if (Object.hasOwn(special, word)) return special[word]!.split(' ').map((part) => [part])
  if (word === "ain't") return [['am', 'is', 'are', 'has', 'have'], ['not']]
  if (word.endsWith("n't") && negatives.has(word.slice(0, -3))) return [[word.slice(0, -3)], ['not']]
  const at = word.lastIndexOf("'")
  const stem = at < 0 ? '' : word.slice(0, at)
  const suffix = word.slice(at + 1)
  if (suffix === 've' && stem.endsWith("'d") && hosts.has(stem.slice(0, -2))) {
    return [...expand(stem.slice(0, -2)), ['would'], ['have']]
  }
  let tail: string[] | null = null
  if (suffix === 've' && (hosts.has(stem) || ['could', 'would', 'should', 'might', 'must'].includes(stem) ||
    stem.endsWith("n't") || stem.endsWith("'ll"))) tail = ['have']
  else if (hosts.has(stem)) {
    if (suffix === 'm' && stem === 'i') tail = ['am']
    else if (suffix === 're') tail = ['are']
    else if (suffix === 'll') tail = ['i', 'we'].includes(stem) ? ['will', 'shall'] : ['will']
    else if (suffix === 'd') tail = questions.has(stem) ? ['had', 'would', 'did'] : ['had', 'would']
    else if (suffix === 's') tail = questions.has(stem) ? ['is', 'has', 'does'] : ['is', 'has']
  }
  return tail ? [...expand(stem), tail] : [[word]]
}

function tokens(text: string): Token[] {
  return [...foldEnglish(text).matchAll(/'?[\p{L}\p{N}]+(?:'[\p{L}\p{N}]+)*/gu)]
    .flatMap((match) => expand(match[0].toLowerCase()).map((forms) => ({
      forms, start: match.index, end: match.index + match[0].length,
    })))
}

/** Expanded vocabulary for grounding visible contraction hits in Arabic word glosses. */
export const contractionWords = (text: string): string[] => tokens(text).flatMap((token) => token.forms)

/** Alternative auxiliaries remain one word slot rather than requiring both meanings. */
export const contractionForms = (text: string): string[][] => tokens(text).map((token) => token.forms)

/** Cheap gate for search-only expansion; displayed translations stay unchanged. */
export const mayExpandContractions = (text: string): boolean => /['’]|cannot/i.test(text)

/** Contiguous equivalents mapped back onto the original glyphs and brackets. */
export function contractionRanges(text: string, query: string): { start: number; end: number }[] {
  if (!mayExpandContractions(text) && !mayExpandContractions(query)) return []
  const needle = tokens(query)
  const haystack = tokens(text)
  if (!needle.length || needle.length > haystack.length) return []
  const ranges: { start: number; end: number }[] = []
  for (let at = 0; at <= haystack.length - needle.length; at++) {
    if (needle.some((token, i) => !token.forms.some((form) => haystack[at + i]!.forms.includes(form)))) continue
    let start = haystack[at]!.start
    let end = haystack[at + needle.length - 1]!.end
    while (start > 0 && '(['.includes(text[start - 1]!)) start--
    while (end < text.length && ')]'.includes(text[end]!)) end++
    if (!ranges.some((range) => range.start === start && range.end === end)) ranges.push({ start, end })
  }
  return ranges
}
