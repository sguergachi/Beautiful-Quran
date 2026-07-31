/** HTMLMediaElement.HAVE_FUTURE_DATA — enough to start without an immediate stall. */
export const HAVE_FUTURE_DATA = 3

export type AudioElementFactory = () => HTMLAudioElement

export interface MediaElementEvents {
  timeUpdate(): void
  ended(): void
  play(): void
  playing(): void
  pause(): void
  waiting(): void
  stalled(): void
  error(): void
  loadedMetadata(): void
  canPlayThrough(): void
}

export interface ActiveSource {
  src: string
  loop: boolean
  playbackRate: number
  volume: number
}

/** Keep natural pitch when playbackRate ≠ 1 (Android Media3 default). */
function applyPreservesPitch(audio: HTMLAudioElement): void {
  const el = audio as HTMLAudioElement & {
    preservesPitch?: boolean
    mozPreservesPitch?: boolean
    webkitPreservesPitch?: boolean
  }
  el.preservesPitch = true
  el.mozPreservesPitch = true
  el.webkitPreservesPitch = true
}

/**
 * Apply rate for both the live clock and the post-src default.
 *
 * Browsers re-seed [HTMLMediaElement.playbackRate] from
 * [HTMLMediaElement.defaultPlaybackRate] when the resource changes (src/load).
 * Setting only playbackRate is wiped on the next ayah load — which is why a
 * saved 0.75 felt like 1× until the user cycled speed while media was already
 * loaded. Set both so quiet open + first play honor the preference.
 */
function applyPlaybackRate(audio: HTMLAudioElement, speed: number): void {
  audio.defaultPlaybackRate = speed
  audio.playbackRate = speed
}

function createBrowserAudio(): HTMLAudioElement {
  const audio = new Audio()
  audio.preload = 'auto'
  audio.setAttribute('playsinline', 'true')
  audio.setAttribute('webkit-playsinline', 'true')
  ;(audio as HTMLAudioElement & { playsInline?: boolean }).playsInline = true
  applyPreservesPitch(audio)
  return audio
}

/**
 * Owns the browser media elements and their identity across desktop promotion.
 * Events from the retired element are discarded here, before controller state
 * can observe them.
 */
export class MediaElementTransport {
  private activeElement: HTMLAudioElement
  private standbyElement: HTMLAudioElement | null
  private standbyIndex = -1

  constructor(
    readonly singleElement: boolean,
    private readonly events: MediaElementEvents,
    createAudio: AudioElementFactory = createBrowserAudio,
  ) {
    this.activeElement = createAudio()
    this.standbyElement = singleElement ? null : createAudio()
    this.bind(this.activeElement)
    if (this.standbyElement) this.bind(this.standbyElement)
  }

  get active(): HTMLAudioElement {
    return this.activeElement
  }

  get standby(): HTMLAudioElement | null {
    return this.standbyElement
  }

  /**
   * Standby is usable once it has current media data. Blob URLs often sit at
   * HAVE_CURRENT_DATA briefly before FUTURE; requiring FUTURE left joins cold
   * and forced a full silence-tail wait until natural `ended`.
   */
  isStandbyReady(index: number): boolean {
    return this.standbyElement != null &&
      this.standbyIndex === index &&
      this.standbyElement.readyState >= 2 /* HAVE_CURRENT_DATA */
  }

  loadActive(source: ActiveSource): void {
    this.activeElement.pause()
    this.activeElement.loop = source.loop
    this.activeElement.src = source.src
    applyPlaybackRate(this.activeElement, source.playbackRate)
    applyPreservesPitch(this.activeElement)
    this.activeElement.volume = source.volume
    this.activeElement.load()
    // Re-assert after load(): some engines re-seed from defaultPlaybackRate
    // during the resource algorithm; keep both in lockstep.
    applyPlaybackRate(this.activeElement, source.playbackRate)
  }

  prepareStandby(index: number, src: string, playbackRate: number): void {
    const standby = this.standbyElement
    if (!standby) return
    const current = standby.currentSrc || standby.src
    const sameSource = current === src || current.endsWith(src) || src.endsWith(current)
    if (this.standbyIndex === index && sameSource) {
      // Same clip may have been prepared under a different speed preference.
      applyPlaybackRate(standby, playbackRate)
      return
    }

    standby.pause()
    standby.volume = 1
    standby.loop = false
    standby.src = src
    applyPlaybackRate(standby, playbackRate)
    applyPreservesPitch(standby)
    standby.load()
    applyPlaybackRate(standby, playbackRate)
    this.standbyIndex = index
  }

  /** Promote a prepared standby; the retired active becomes an empty standby. */
  promoteStandby(index: number): boolean {
    if (!this.isStandbyReady(index) || !this.standbyElement) return false
    const retired = this.activeElement
    this.activeElement = this.standbyElement
    this.standbyElement = retired
    this.standbyIndex = -1
    this.reset(this.standbyElement)
    return true
  }

  clearStandby(): void {
    this.standbyIndex = -1
    if (this.standbyElement) this.reset(this.standbyElement)
  }

  setSpeed(speed: number): void {
    applyPlaybackRate(this.activeElement, speed)
    applyPreservesPitch(this.activeElement)
    if (this.standbyElement) {
      applyPlaybackRate(this.standbyElement, speed)
      applyPreservesPitch(this.standbyElement)
    }
  }

  resetVolumes(): void {
    this.activeElement.volume = 1
    if (this.standbyElement) this.standbyElement.volume = 1
  }

  stop(): void {
    this.activeElement.pause()
    this.activeElement.removeAttribute('src')
    this.clearStandby()
  }

  /** Resolve once the selected element can start, rejecting on error/timeout. */
  waitForCanPlay(audio = this.activeElement, timeoutMs = 15_000): Promise<void> {
    if (audio.readyState >= HAVE_FUTURE_DATA) return Promise.resolve()
    return new Promise((resolve, reject) => {
      let settled = false
      const finish = (ok: boolean, error?: Error) => {
        if (settled) return
        settled = true
        globalThis.clearTimeout(timer)
        audio.removeEventListener('canplay', onCanPlay)
        audio.removeEventListener('error', onError)
        if (ok) resolve()
        else reject(error ?? new Error('Audio failed to load'))
      }
      const onCanPlay = () => finish(true)
      const onError = () => finish(false, new Error('Audio failed to load'))
      const timer = globalThis.setTimeout(
        () => finish(false, new Error('Audio buffer timeout')),
        timeoutMs,
      )
      audio.addEventListener('canplay', onCanPlay)
      audio.addEventListener('error', onError)
      if (audio.readyState >= HAVE_FUTURE_DATA) finish(true)
    })
  }

  private reset(audio: HTMLAudioElement): void {
    audio.pause()
    audio.removeAttribute('src')
    audio.load()
  }

  private bind(audio: HTMLAudioElement): void {
    const active = (event: keyof MediaElementEvents) => () => {
      if (audio === this.activeElement) this.events[event]()
    }
    audio.addEventListener('timeupdate', active('timeUpdate'))
    audio.addEventListener('ended', active('ended'))
    audio.addEventListener('play', active('play'))
    audio.addEventListener('playing', active('playing'))
    audio.addEventListener('pause', active('pause'))
    audio.addEventListener('waiting', active('waiting'))
    audio.addEventListener('stalled', active('stalled'))
    audio.addEventListener('error', active('error'))
    audio.addEventListener('loadedmetadata', active('loadedMetadata'))
    audio.addEventListener('canplaythrough', active('canPlayThrough'))
  }
}
