export interface RootViewerReference {
  title: string
  description: string
  url: string
}

/** Stable, location-aware learning links for a word in the Root Viewer. */
export function rootViewerReferences(
  surahId: number,
  ayah: number,
  position: number,
  root: string,
): RootViewerReference[] {
  const links: RootViewerReference[] = [{
    title: 'Detailed word grammar',
    description: 'Quranic Arabic Corpus analysis of this exact word and its segments.',
    url: `https://corpus.quran.com/wordmorphology.jsp?location=${encodeURIComponent(`(${surahId}:${ayah}:${position})`)}`,
  }]
  if (root) {
    links.push({
      title: 'Quran root dictionary',
      description: 'Quran-wide senses and derived forms grouped under this root.',
      url: `https://corpus.quran.com/qurandictionary.jsp?q=${encodeURIComponent(arabicToBuckwalter(root))}`,
    })
  }
  links.push({
    title: 'Read the full ayah',
    description: "Translations, recitation, and tafsir in the verse's wider context.",
    url: `https://quran.com/${surahId}/${ayah}`,
  })
  return links
}

/** Hawramani's Lane entry for `root` — shown under the Classical lexicon section. */
export function laneLexiconUrl(root: string): string {
  return `https://arabiclexicon.hawramani.com/search/${encodeURIComponent(root)}?cat=50`
}

const ARABIC_TO_BUCKWALTER: Record<string, string> = {
  'ء': "'", 'آ': '|', 'أ': '>', 'ؤ': '&', 'إ': '<', 'ئ': '}', 'ا': 'A', 'ب': 'b',
  'ت': 't', 'ث': 'v', 'ج': 'j', 'ح': 'H', 'خ': 'x', 'د': 'd', 'ذ': '*', 'ر': 'r',
  'ز': 'z', 'س': 's', 'ش': '$', 'ص': 'S', 'ض': 'D', 'ط': 'T', 'ظ': 'Z', 'ع': 'E',
  'غ': 'g', 'ف': 'f', 'ق': 'q', 'ك': 'k', 'ل': 'l', 'م': 'm', 'ن': 'n', 'ه': 'h',
  'و': 'w', 'ى': 'Y', 'ي': 'y', 'ة': 'p', 'ٱ': '{',
}

/** QAC's root-dictionary query uses its extended Buckwalter spelling. */
export function arabicToBuckwalter(value: string): string {
  return Array.from(value, (char) => ARABIC_TO_BUCKWALTER[char] ?? char).join('')
}
