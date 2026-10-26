import type { AudioPrefetcher } from './audioPrefetch'
import {
  QUIET_TARJI,
  reciterTarjiKnobs,
  sampleTarji,
  TarjiEventLedger,
  type TarjiTrack,
  type TarjiSample,
} from './tarji'

/** The detector and ink use the same per-verse media clock. Decoded PCM is
 * transferred to a worker and released; only bounded 50 Hz features survive.
 * Unavailable audio/CORS/worker support leaves still wet ink, never fake pulses.
 */
class TarjiSignal {
  private cache = new Map<string, TarjiTrack>()
  private pending = new Map<string, Promise<void>>()
  private worker: Worker | null = null
  private jobs = new Map<number, (track?: TarjiTrack) => void>()
  private serial = 0
  private chain = Promise.resolve()
  private wanted = new Set<string>()
  private key = ''
  private mediaMs = 0
  private playing = false
  private brightness = 1
  private playbackSpeed = 1
  private ledger = new TarjiEventLedger()
  setClock(
    url: string,
    positionMs: number,
    playing: boolean,
    reciterId: number,
    speed = 1,
  ): void {
    const key = `${reciterId}|${url}`
    if (this.key !== key || positionMs < this.mediaMs - 250)
      this.ledger = new TarjiEventLedger()
    this.key = key
    this.mediaMs = positionMs
    this.playing = playing
    this.brightness = reciterTarjiKnobs(reciterId).brightness
    this.playbackSpeed = Math.max(0.1, speed)
  }
  get speed(): number {
    return this.playbackSpeed
  }
  get isPlaying(): boolean {
    return this.playing
  }
  get positionMs(): number {
    return this.mediaMs
  }
  get sourceKey(): string {
    return this.key
  }
  get eventLedger(): TarjiEventLedger {
    return this.ledger
  }
  get lightBrightness(): number {
    return this.brightness
  }
  sample(leadMs = 0): TarjiSample {
    return this.playing
      ? sampleTarji(this.cache.get(this.key), this.mediaMs + leadMs)
      : QUIET_TARJI
  }
  retain(urls: string[], reciterId: number): void {
    this.wanted = new Set(urls.map((url) => `${reciterId}|${url}`))
  }
  prepare(
    url: string,
    reciterId: number,
    prefetcher: AudioPrefetcher,
  ): Promise<void> {
    if (
      typeof Worker === 'undefined' ||
      typeof OfflineAudioContext === 'undefined'
    )
      return Promise.resolve()
    const key = `${reciterId}|${url}`
    if (this.cache.has(key)) return Promise.resolve()
    const prior = this.pending.get(key)
    if (prior) return prior
    const task = this.chain
      .then(async () => {
        try {
          if (this.wanted.size && !this.wanted.has(key)) return
          // A stalled shared prefetch must not block every subsequent analysis.
          let timer: ReturnType<typeof setTimeout> | undefined
          const src = await Promise.race([
            prefetcher.ensure(url),
            new Promise<null>((resolve) => {
              timer = setTimeout(() => resolve(null), 15000)
            }),
          ]).finally(() => {
            clearTimeout(timer)
          })
          if (!src || (this.wanted.size && !this.wanted.has(key))) return
          const response = await fetch(src, {
            signal: AbortSignal.timeout(15000),
          })
          if (!response.ok) return
          // Native resampling preserves anti-alias filtering before 8 kHz pitch analysis.
          const context = new OfflineAudioContext(1, 1, 8000)
          const audio = await context.decodeAudioData(
            await response.arrayBuffer(),
          )
          const pcm = new Float32Array(audio.length)
          for (let ch = 0; ch < audio.numberOfChannels; ch++) {
            const channel = audio.getChannelData(ch)
            for (let i = 0; i < pcm.length; i++)
              pcm[i] += channel[i] / audio.numberOfChannels
          }
          const track = await this.analyze(pcm, reciterId)
          if (track) {
            this.cache.set(key, track)
            while (this.cache.size > 12) {
              const oldest = [...this.cache.keys()].find((k) => k !== this.key)
              if (!oldest) break
              this.cache.delete(oldest)
            }
          }
        } catch {
          /* Analysis cannot prevent or delay playback. Retry on a later visit. */
        }
      })
      .finally(() => {
        this.pending.delete(key)
      })
    this.chain = task
    this.pending.set(key, task)
    return task
  }
  private analyze(
    pcm: Float32Array,
    reciterId: number,
  ): Promise<TarjiTrack | undefined> {
    this.worker ??= this.createWorker()
    const worker = this.worker
    return new Promise((resolve) => {
      const id = ++this.serial
      this.jobs.set(id, resolve)
      worker.postMessage(
        { id, pcm, reciterId, knobs: reciterTarjiKnobs(reciterId) },
        [pcm.buffer],
      )
    })
  }
  private createWorker(): Worker {
    const worker = new Worker(new URL('./tarjiWorker.ts', import.meta.url), {
      type: 'module',
    })
    worker.onmessage = (
      event: MessageEvent<{ id: number; track?: TarjiTrack }>,
    ) => {
      this.jobs.get(event.data.id)?.(event.data.track)
      this.jobs.delete(event.data.id)
    }
    worker.onerror = () => {
      for (const resolve of this.jobs.values()) resolve()
      this.jobs.clear()
      worker.terminate()
      this.worker = null
    }
    return worker
  }
}
export const tarjiSignal = new TarjiSignal()
