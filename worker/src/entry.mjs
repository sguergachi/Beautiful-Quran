import { DurableObject } from 'cloudflare:workers'
import { createQfProxy } from './index.mjs'
import { SqlTimingStore, TimingController } from './timings.mjs'

/** The shared authorization atom coordinates only six fixed QF resources. */
export class QfTimingControl extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env)
    const ready = ctx.blockConcurrencyWhile(async () => {
      this.control = new TimingController(new SqlTimingStore(ctx.storage), env, createQfProxy().qfResponse)
    })
    // Resume an interrupted purge without holding the input gate over network.
    ctx.waitUntil(ready.then(() => {
      if (this.control.global().revoked && !this.control.global().purgeComplete) return this.control.revoke()
    }))
  }
  response(id, operation, manifest, cors) { return this.control.response(id, operation, manifest, cors) }
  refresh(id) { return this.control.refresh(id) }
  maintain(id) { return this.control.maintain(id) }
  revoke() { return this.control.revoke() }
  content(path, cors) { return this.control.content(path, cors) }
}

export { default } from './index.mjs'
