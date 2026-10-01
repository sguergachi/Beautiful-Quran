export type ReaderKeyboardAction =
  | { type: 'jump'; ayah: number }
  | { type: 'playPause' }
  | { type: 'search' }
  | { type: 'bookmark' }

/** Maps unmodified reader keystrokes to actions without touching the DOM. */
export function readerKeyboardAction(
  key: string,
  currentAyah: number,
  ayahCount: number,
): ReaderKeyboardAction | null {
  const jump = (ayah: number): ReaderKeyboardAction => ({
    type: 'jump',
    ayah: Math.min(ayahCount, Math.max(1, ayah)),
  })
  switch (key) {
    case 'ArrowUp': return jump(currentAyah - 1)
    case 'ArrowDown': return jump(currentAyah + 1)
    case 'PageUp': return jump(currentAyah - 5)
    case 'PageDown': return jump(currentAyah + 5)
    case 'Home': return jump(1)
    case 'End': return jump(ayahCount)
    case ' ': return { type: 'playPause' }
    case '/': return { type: 'search' }
    case 'b':
    case 'B': return { type: 'bookmark' }
    default: return null
  }
}

/** Native controls retain their browser keyboard behavior. */
export function isKeyboardControl(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(
    target.closest('input, textarea, select, button, [contenteditable="true"], [role="slider"]'),
  )
}

/** Only the visible reader owns navigation; an open root sheet owns its keys. */
export function readerOwnsKeyboard(visible: boolean, rootViewerOpen: boolean): boolean {
  return visible && !rootViewerOpen
}
