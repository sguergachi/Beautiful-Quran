export type ReaderOpenIntent = 'chapter' | 'reading'

/** Only an ordinary chapter opening rests on the title above verse one. */
export function readerOpensOnTitle(ayah: number, intent: ReaderOpenIntent): boolean {
  return ayah <= 1 && intent === 'chapter'
}
