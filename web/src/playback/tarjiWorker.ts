import type { TarjiKnobs } from './tarji'
import { analyzeRecording } from './tarjiRecording'
self.onmessage = (
  event: MessageEvent<{
    id: number
    pcm: Float32Array
    knobs: TarjiKnobs
    reciterId: number
  }>,
) => {
  const { id, pcm, knobs, reciterId } = event.data
  try {
    const track = analyzeRecording(pcm, knobs, reciterId)
    self.postMessage(
      { id, track },
      {
        transfer: [
          track.pulse.buffer,
          track.gain.buffer,
          track.rate.buffer,
          track.event.buffer,
        ],
      },
    )
  } catch {
    self.postMessage({ id, error: true })
  }
}
