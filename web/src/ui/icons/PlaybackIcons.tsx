/** Inline playback glyphs — ink-colored, no Material chrome. */
import type { ReactNode, SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement> & { title?: string }

function base(props: IconProps, path: ReactNode) {
  const { title, ...rest } = props
  return (
    <svg
      viewBox="0 0 24 24"
      width="1.15em"
      height="1.15em"
      fill="currentColor"
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      {...rest}
    >
      {title ? <title>{title}</title> : null}
      {path}
    </svg>
  )
}

export function IconPlay(props: IconProps) {
  return base(props, <path d="M8 5.14v13.72L19 12 8 5.14z" />)
}

export function IconPause(props: IconProps) {
  return base(
    props,
    <path d="M7 5h3.5v14H7V5zm6.5 0H17v14h-3.5V5z" />,
  )
}

/** Indeterminate buffer ring — Android CircularProgressIndicator parity. */
export function IconBuffering(props: IconProps) {
  const { title, className, ...rest } = props
  return (
    <svg
      viewBox="0 0 24 24"
      width="1.15em"
      height="1.15em"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      className={['icon-buffering', className].filter(Boolean).join(' ')}
      {...rest}
    >
      {title ? <title>{title}</title> : null}
      <circle cx="12" cy="12" r="9" opacity="0.2" />
      <path d="M12 3a9 9 0 0 1 9 9" />
    </svg>
  )
}

/** Material FastRewind — Android Icons.Rounded.FastRewind parity. */
export function IconFastRewind(props: IconProps) {
  return base(
    props,
    <path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z" />,
  )
}

/** Material FastForward — Android Icons.Rounded.FastForward parity. */
export function IconFastForward(props: IconProps) {
  return base(
    props,
    <path d="M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z" />,
  )
}

export function IconRepeat(props: IconProps) {
  return base(
    props,
    <path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z" />,
  )
}

export function IconRepeatOne(props: IconProps) {
  return base(
    props,
    <>
      <path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z" />
      <path d="M12 9.2h1.1V15H11.7v-4.3h-.9V9.6c.4-.2.8-.3 1.2-.4z" />
    </>,
  )
}

/** Material VolumeUp — root-viewer word audition speaker. */
export function IconVolumeUp(props: IconProps) {
  return base(
    props,
    <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z" />,
  )
}

/** Rounded close (×) — ink-bleed dismiss control. */
export function IconClose(props: IconProps) {
  return base(
    props,
    <path d="M18.3 5.7a1 1 0 0 0-1.4 0L12 10.6 7.1 5.7a1 1 0 1 0-1.4 1.4L10.6 12l-4.9 4.9a1 1 0 1 0 1.4 1.4L12 13.4l4.9 4.9a1 1 0 0 0 1.4-1.4L13.4 12l4.9-4.9a1 1 0 0 0 0-1.4z" />,
  )
}

/** Magnifying glass — in-surah English search. */
export function IconSearch(props: IconProps) {
  return base(
    props,
    <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" />,
  )
}

/**
 * Tune / sliders — opens settings. Android `Icons.Rounded.Tune`: three rails,
 * each with one knob. (The path here used to end in a stray subpath, a bar
 * from the top of the icon down through the middle knob.)
 */
export function IconTune(props: IconProps) {
  return base(
    props,
    <path d="M3 18c0 .55.45 1 1 1h5v-2H4c-.55 0-1 .45-1 1zM3 6c0 .55.45 1 1 1h9V5H4c-.55 0-1 .45-1 1zm10 14v-1h7c.55 0 1-.45 1-1s-.45-1-1-1h-7v-1c0-.55-.45-1-1-1s-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1zM7 10v1H4c-.55 0-1 .45-1 1s.45 1 1 1h3v1c0 .55.45 1 1 1s1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1zm14 2c0-.55-.45-1-1-1h-9v2h9c.55 0 1-.45 1-1zm-5-3c.55 0 1-.45 1-1V7h3c.55 0 1-.45 1-1s-.45-1-1-1h-3V4c0-.55-.45-1-1-1s-1 .45-1 1v4c0 .55.45 1 1 1z" />,
  )
}

export function IconChevronUp(props: IconProps) {
  return base(props, <path d="M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z" />)
}

export function IconChevronDown(props: IconProps) {
  return base(props, <path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" />)
}
