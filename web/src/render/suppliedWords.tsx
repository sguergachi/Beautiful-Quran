import type { ReactNode } from 'react'

/**
 * Sahih International's [supplied words]: the brackets recede to 40% of the
 * text's ink and the words keep their voice. Mirrors Android's
 * `quietSuppliedBrackets` (ui/theme/SuppliedWords.kt).
 */
export function suppliedWords(text: string, keyPrefix = 'sw'): ReactNode[] {
  if (!text.includes('[') && !text.includes(']')) return [text]
  return text.split(/([[\]])/u).filter(Boolean).map((part, i) =>
    part === '[' || part === ']'
      ? <span key={`${keyPrefix}-${i}`} className="supplied-bracket">{part}</span>
      : part,
  )
}
