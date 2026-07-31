/** One word-by-word English rendering of a form, and how often it is used. */
export interface GlossVote {
  translation: string
  count: number
}

/** Function words that carry no lemma meaning when they open a rendering. */
const LEADING = new Set([
  'and', 'then', 'so', 'but', 'or', 'the', 'a', 'an', 'of', 'to', 'for',
  'in', 'is', 'are', 'was', 'were', 'with', 'from', 'on', 'at', 'by', 'o',
  'i', 'he', 'she', 'it', 'we', 'they', 'you', 'him', 'her', 'them', 'us',
  'me', 'my', 'your', 'his', 'its', 'our', 'their', 'that', 'this',
  'these', 'those', 'will', 'has', 'have', 'had', 'did', 'do', 'does',
  'surely', 'indeed', 'verily', 'not', 'who', 'whom', 'which', 'all',
  'any', 'some', 'most',
])

/** Attached objects and dangling particles that end a rendering. */
const TRAILING = new Set([
  'them', 'him', 'her', 'us', 'me', 'you', 'it', 'to', 'for', 'of',
  'with', 'on', 'upon', 'from', 'by', 'at', 'in', 'and', 'their', 'your',
  'his', 'its', 'my', 'our',
])

const ASIDES = /\([^)]*\)|\[[^\]]*]/g
const EDGE_PUNCTUATION = /^[\s.,;:!?"']+|[\s.,;:!?"']+$/g

interface Bucket {
  votes: number
  topVotes: number
  display: string
}

/** Drops asides and edge punctuation, then trims framing words. */
function normalize(translation: string): string[] {
  const plain = translation.replace(ASIDES, ' ').replace(/\s+/g, ' ').replace(EDGE_PUNCTUATION, '')
  if (!plain) return []
  const words = plain.split(' ').filter(Boolean)
  while (words.length > 1 && LEADING.has(words[0].toLowerCase())) words.shift()
  while (words.length > 1 && TRAILING.has(words[words.length - 1].toLowerCase())) words.pop()
  return words
}

/** Prefer the most used rendering, then the plainest, then alphabetical. */
function betterDisplay(count: number, display: string, bucket: Bucket): boolean {
  if (count !== bucket.topVotes) return count > bucket.topVotes
  if (display.length !== bucket.display.length) return display.length < bucket.display.length
  return display < bucket.display
}

/**
 * Chooses the short English gloss shown beside a related form in the root
 * viewer.
 *
 * The corpus has no dictionary definitions, so the gloss is voted for by the
 * word-by-word translations of every Quran word carrying that lemma. Those
 * renderings are inflected and context-bound ('the Book', '(of) the Book',
 * 'and mercy', 'you know'), so each is first reduced to a comparison key —
 * bracketed asides dropped, leading articles/pronouns/auxiliaries and trailing
 * particles/objects trimmed — and the votes of every rendering sharing a key
 * are pooled. The winning key is displayed using its most common rendering.
 *
 * Trimming never empties a rendering: a bare 'is' or 'them' keeps its own
 * word, so copula-heavy lemmas (كان) still gloss as 'is' rather than losing
 * every frequent form to the empty string.
 *
 * The mirror of Android `LemmaGloss.kt` — keep the two in step. Returns ''
 * when no rendering survives normalisation.
 */
export function pickLemmaGloss(votes: GlossVote[]): string {
  const buckets = new Map<string, Bucket>()
  for (const { translation, count } of votes) {
    const words = normalize(translation)
    if (words.length === 0) continue
    const display = words.join(' ')
    const key = words.map((word) => word.toLowerCase()).join(' ')
    let bucket = buckets.get(key)
    if (!bucket) {
      bucket = { votes: 0, topVotes: 0, display: '' }
      buckets.set(key, bucket)
    }
    bucket.votes += count
    if (!bucket.display || betterDisplay(count, display, bucket)) {
      bucket.topVotes = count
      bucket.display = display
    }
  }

  let winnerKey = ''
  let winner: Bucket | null = null
  for (const [key, bucket] of buckets) {
    if (
      !winner ||
      bucket.votes > winner.votes ||
      (bucket.votes === winner.votes && bucket.topVotes > winner.topVotes) ||
      (bucket.votes === winner.votes && bucket.topVotes === winner.topVotes && key < winnerKey)
    ) {
      winner = bucket
      winnerKey = key
    }
  }
  return winner ? winner.display : ''
}
