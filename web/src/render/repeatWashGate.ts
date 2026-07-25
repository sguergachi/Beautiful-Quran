/**
 * Per-ayah gate so orange chain washes run one word at a time **in word
 * position order**. Same-tick enqueues are batched (microtask) so seek-into-
 * chain does not start the first job before siblings land in the queue.
 */
export type RepeatWashGate = {
  run: <T>(position: number, fn: () => Promise<T>) => Promise<T>
}

type Job = {
  position: number
  run: () => Promise<void>
}

export function createRepeatWashGate(): RepeatWashGate {
  const pending = new Map<number, Job[]>()
  let pumping = false
  let scheduled = false

  const lowestPosition = (): number | undefined => {
    let min: number | undefined
    for (const p of pending.keys()) {
      if (min === undefined || p < min) min = p
    }
    return min
  }

  const pump = () => {
    if (pumping) return
    pumping = true
    const step = (): void => {
      const pos = lowestPosition()
      if (pos === undefined) {
        pumping = false
        return
      }
      const q = pending.get(pos)!
      const job = q.shift()!
      if (q.length === 0) pending.delete(pos)
      job.run().then(step, step)
    }
    step()
  }

  /** Let all sync enqueues in this turn join before draining. */
  const schedule = () => {
    if (scheduled) return
    scheduled = true
    queueMicrotask(() => {
      scheduled = false
      pump()
    })
  }

  return {
    run<T>(position: number, fn: () => Promise<T>): Promise<T> {
      return new Promise<T>((resolve, reject) => {
        const list = pending.get(position) ?? []
        list.push({
          position,
          run: () => fn().then(resolve, reject),
        })
        pending.set(position, list)
        schedule()
      })
    },
  }
}
