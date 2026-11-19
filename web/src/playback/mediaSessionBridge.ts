export interface MediaSessionActions {
  play: () => void
  pause: () => void
  previous: () => void
  next: () => void
}

export interface MediaSessionTrack {
  title: string
  artist: string
  album: string
}

type SessionPort = Pick<MediaSession, 'metadata' | 'setActionHandler'> &
  Partial<Pick<MediaSession, 'playbackState'>>
type MetadataFactory = (track: MediaSessionTrack) => MediaMetadata

export function browserMediaSession(): MediaSession | null {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return null
  return navigator.mediaSession
}

/** Owns lock-screen metadata and OS transport handlers for the web player. */
export class MediaSessionBridge {
  constructor(
    actions: MediaSessionActions,
    private readonly session: SessionPort | null = browserMediaSession(),
    private readonly createMetadata: MetadataFactory = (track) => new MediaMetadata(track),
  ) {
    if (!session) return
    session.setActionHandler('play', actions.play)
    session.setActionHandler('pause', actions.pause)
    session.setActionHandler('previoustrack', actions.previous)
    session.setActionHandler('nexttrack', actions.next)
  }

  /**
   * Declare playing while the element is paused on purpose (the pause between
   * verses), so the lock screen keeps its Pause control; null hands the state
   * back to the browser's own reading of the media element.
   */
  setPlaying(playing: true | null): void {
    if (!this.session || !('playbackState' in this.session)) return
    this.session.playbackState = playing ? 'playing' : 'none'
  }

  update(track: MediaSessionTrack): void {
    if (!this.session) return
    this.session.metadata = this.createMetadata(track)
  }
}
