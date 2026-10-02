import { useLayoutEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Draws [children] above the paper stack while a phone scroll chapter is
 * open, so the sheet transform does not carry the bar away. The host is
 * `#playback-pin` on the app shell.
 */
export function PlaybackPin({
  active,
  children,
}: {
  active: boolean
  children: ReactNode
}) {
  const [host, setHost] = useState<HTMLElement | null>(null)
  useLayoutEffect(() => {
    setHost(document.getElementById('playback-pin'))
  }, [])
  if (active && host) return createPortal(children, host)
  return children
}
