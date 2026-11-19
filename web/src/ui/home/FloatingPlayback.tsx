import { useEffect, useRef, useState } from 'react'
import {
  IconBuffering,
  IconClose,
  IconFastForward,
  IconFastRewind,
  IconPause,
  IconPlay,
  IconRepeat,
  IconRepeatOne,
} from '../icons/PlaybackIcons'
import { appStore, useAppSelector, shallowEqual } from '../../store/appStore'
import { nextRepeatMode, nextSpeed, speedLabel } from './floatingPlaybackPolicy'

/** Android `HomeFloatExitMs`: the whole sheet sweeps down to uncover the rows. */
const EXIT_MS = 260

/**
 * The chapter list's transport (Android `FloatingPlaybackControl`): quiet ink
 * on opaque paper, no card. The reciter (opens Settings) or a dismissible
 * playback notice; the green return pill to the playing verse; then repeat,
 * rewind, play, forward and speed, with Close at the paper's top end.
 * Enters on the shared float rise and leaves by sweeping down.
 */
export function FloatingPlayback({
  visible,
  chapterLabel,
  ayahLabel,
  onOpenNowPlaying,
}: {
  visible: boolean
  chapterLabel: string
  ayahLabel: string
  onOpenNowPlaying: () => void
}) {
  const s = useAppSelector(
    (state) => ({
      isPlaying: state.player.isPlaying,
      isBuffering: state.player.isBuffering,
      repeatMode: state.player.repeatMode,
      repeatRange: state.player.repeatRange,
      error: state.player.error,
      speed: state.settings.playbackSpeed,
      reciterName:
        state.reciters.find(
          (r) => r.id === (state.player.nowPlaying?.reciterId ?? state.settings.reciterId),
        )?.name ??
        state.reciters.find((r) => r.id === state.settings.reciterId)?.name ??
        '',
    }),
    shallowEqual,
  )

  // Keep the last labels through the exit sweep: the session is already gone.
  const [rendered, setRendered] = useState(visible)
  const [leaving, setLeaving] = useState(false)
  const labels = useRef({ chapterLabel, ayahLabel })
  if (visible && chapterLabel) labels.current = { chapterLabel, ayahLabel }
  useEffect(() => {
    if (visible) {
      setRendered(true)
      setLeaving(false)
      return
    }
    if (!rendered) return
    setLeaving(true)
    const done = window.setTimeout(() => {
      setRendered(false)
      setLeaving(false)
    }, EXIT_MS)
    return () => window.clearTimeout(done)
  }, [visible, rendered])
  if (!rendered) return null

  const { chapterLabel: chapter, ayahLabel: ayah } = labels.current
  const waiting = s.isBuffering && !s.isPlaying
  const repeatOn = s.repeatMode !== 'off'
  const singleAyahRange = s.repeatRange != null && s.repeatRange.first === s.repeatRange.last
  return (
    <div
      className="floating-play home-float"
      role="group"
      aria-label="Playback"
      data-leaving={leaving || undefined}
      inert={leaving || undefined}
    >
      <div className="player-reciter-row home-float-reciter">
        {s.error ? (
          <div className="player-error home-float-error">
            <button
              type="button"
              className="player-error-dismiss"
              aria-label="Dismiss playback error"
              onClick={() => appStore.clearPlaybackError()}
            >
              <IconClose />
            </button>
            <span className="player-error-text" role="status" aria-live="polite">{s.error}</span>
          </div>
        ) : (
          <button
            type="button"
            className="home-float-reciter-btn"
            aria-label={`Reciter ${s.reciterName}. Open settings`}
            onClick={() => appStore.setSheet('settings')}
          >
            <span>{s.reciterName}</span>
          </button>
        )}
      </div>
      <button
        type="button"
        className="player-return"
        aria-label={`Return to ${chapter} · ${ayah}`}
        onClick={onOpenNowPlaying}
      >
        <span className="player-return-chapter">{chapter}</span>
        <span className="player-return-sep" aria-hidden="true">
          {'  ·  '}
        </span>
        <span className="player-return-ayah">{ayah}</span>
        <svg className="player-return-arrow" viewBox="0 0 24 24" aria-hidden="true">
          <path
            fill="currentColor"
            d="M5 13h11.17l-4.88 4.88a1 1 0 0 0 1.41 1.42l6.59-6.59a1 1 0 0 0 0-1.41l-6.58-6.6a1 1 0 0 0-1.41 1.41L16.17 11H5a1 1 0 0 0 0 2z"
          />
        </svg>
      </button>
      <div className="player-transport home-float-transport">
        <button
          type="button"
          className="home-float-ctrl home-float-repeat"
          data-active={repeatOn || undefined}
          aria-label="Repeat"
          aria-pressed={repeatOn}
          onClick={() => void appStore.applyRepeat(nextRepeatMode(s.repeatMode), null)}
        >
          {s.repeatMode === 'ayah' || singleAyahRange ? <IconRepeatOne /> : <IconRepeat />}
        </button>
        <button
          type="button"
          className="home-float-ctrl"
          aria-label="Fast backward"
          onClick={() => void appStore.prev()}
        >
          <IconFastRewind />
        </button>
        <button
          type="button"
          className="home-float-play"
          aria-label={waiting ? 'Buffering' : s.isPlaying ? 'Pause' : 'Play'}
          aria-busy={waiting || undefined}
          // Android lets the tap through while buffering: it cancels the wait.
          onClick={() => void appStore.playPause()}
        >
          {waiting ? <IconBuffering /> : s.isPlaying ? <IconPause /> : <IconPlay />}
        </button>
        <button
          type="button"
          className="home-float-ctrl"
          aria-label="Fast forward"
          onClick={() => void appStore.next()}
        >
          <IconFastForward />
        </button>
        <button
          type="button"
          className="home-float-ctrl home-float-speed"
          data-active={s.speed !== 1 || undefined}
          aria-label={`Speed ${speedLabel(s.speed)}`}
          onClick={() => appStore.updateSettings({ playbackSpeed: nextSpeed(s.speed) })}
        >
          {speedLabel(s.speed)}
        </button>
      </div>
      <button
        type="button"
        className="home-float-close"
        aria-label="Close playback"
        onClick={() => appStore.dismissFloatingPlayback()}
      >
        <IconClose />
      </button>
    </div>
  )
}
