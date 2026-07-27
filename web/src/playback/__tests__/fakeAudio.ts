import { vi } from 'vitest'

/** Small event-capable HTMLAudioElement stand-in for controller sequence tests. */
export class FakeAudio extends EventTarget {
  preload = ''
  src = ''
  currentTime = 0
  duration = 10
  readyState = 4
  playbackRate = 1
  defaultPlaybackRate = 1
  volume = 1
  loop = false
  paused = true
  ended = false
  playsInline = false
  readonly attributes = new Map<string, string>()

  readonly play = vi.fn(async () => {
    this.paused = false
    this.ended = false
    this.emit('play')
    this.emit('playing')
  })

  readonly pause = vi.fn(() => {
    this.paused = true
    this.emit('pause')
  })

  readonly load = vi.fn()

  get currentSrc(): string {
    return this.src
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, value)
  }

  removeAttribute(name: string): void {
    this.attributes.delete(name)
    if (name === 'src') this.src = ''
  }

  emit(type: string): void {
    this.dispatchEvent(new Event(type))
  }

  asAudio(): HTMLAudioElement {
    return this as unknown as HTMLAudioElement
  }
}
