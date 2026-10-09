/** A layout readout must not rewrite an explicit verse link before the reader moves. */
export function scrollReadingAyah(openAyah: number, focusedAyah: number, scrolled: boolean, playing: boolean): number {
  return scrolled || playing ? focusedAyah : openAyah
}
