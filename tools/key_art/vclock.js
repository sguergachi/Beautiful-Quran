// Injected before any page script. Virtualises time + media playback so a
// capture can step the page frame-by-frame at an exact rate.
(() => {
  const realNow = performance.now.bind(performance)
  const realDateNow = Date.now
  const realRAF = window.requestAnimationFrame.bind(window)
  const realCAF = window.cancelAnimationFrame.bind(window)
  const realST = window.setTimeout.bind(window)
  const realCT = window.clearTimeout.bind(window)
  const realSI = window.setInterval.bind(window)
  const realCI = window.clearInterval.bind(window)

  const V = (window.__vc = { on: false, t: 0, dateBase: 0, timers: new Map(), nextId: 1e7, rafs: new Map(), log: [] })

  performance.now = () => (V.on ? V.t : realNow())
  Date.now = () => (V.on ? V.dateBase + V.t : realDateNow())

  window.requestAnimationFrame = (cb) => {
    if (!V.on) return realRAF(cb)
    const id = V.nextId++
    V.rafs.set(id, cb)
    return id
  }
  window.cancelAnimationFrame = (id) => { if (V.rafs.delete(id)) return; realCAF(id) }
  const addTimer = (cb, ms, args, repeat) => {
    const id = V.nextId++
    V.timers.set(id, { cb, due: V.t + Math.max(0, +ms || 0), ms: Math.max(1, +ms || 0), args, repeat })
    return id
  }
  window.setTimeout = (cb, ms, ...args) => (V.on && typeof cb === 'function' ? addTimer(cb, ms, args, false) : realST(cb, ms, ...args))
  window.setInterval = (cb, ms, ...args) => (V.on && typeof cb === 'function' ? addTimer(cb, ms, args, true) : realSI(cb, ms, ...args))
  window.clearTimeout = (id) => { if (V.timers.delete(id)) return; realCT(id) }
  window.clearInterval = (id) => { if (V.timers.delete(id)) return; realCI(id) }

  // ---- media ----
  const M = new WeakMap()
  const live = new Set()
  const st = (el) => { let s = M.get(el); if (!s) { s = { pos: 0, playing: false, ended: false, lastTU: 0 }; M.set(el, s) } return s }
  const P = HTMLMediaElement.prototype
  const d = (k) => Object.getOwnPropertyDescriptor(P, k)
  const srcD = d('src'), ctD = d('currentTime')
  const fire = (el, type) => el.dispatchEvent(new Event(type))
  const logSeg = (el, kind) => { const s = st(el); V.log.push({ kind, t: V.t, src: el.currentSrc || srcD.get.call(el), pos: s.pos, rate: el.playbackRate }) }
  Object.defineProperty(P, 'currentTime', {
    get() { return st(this).pos },
    set(v) {
      const s = st(this); s.pos = Math.max(0, +v || 0); s.ended = false
      if (s.playing) logSeg(this, 'seek')
      Promise.resolve().then(() => { fire(this, 'seeking'); fire(this, 'seeked'); fire(this, 'timeupdate') })
    },
    configurable: true,
  })
  Object.defineProperty(P, 'paused', { get() { return !st(this).playing }, configurable: true })
  Object.defineProperty(P, 'ended', { get() { return st(this).ended }, configurable: true })
  Object.defineProperty(P, 'src', {
    get() { return srcD.get.call(this) },
    set(v) { const s = st(this); if (s.playing) logSeg(this, 'stop'); s.pos = 0; s.playing = false; s.ended = false; live.delete(this); srcD.set.call(this, v) },
    configurable: true,
  })
  const realLoad = P.load
  P.load = function () { const s = st(this); if (s.playing) logSeg(this, 'stop'); s.pos = 0; s.playing = false; s.ended = false; live.delete(this); return realLoad.call(this) }
  P.play = function () {
    const s = st(this)
    const el = this
    return new Promise((resolve) => {
      const go = () => {
        if (!s.playing) {
          if (s.ended) { s.pos = 0; s.ended = false }
          s.playing = true; live.add(el); logSeg(el, 'play')
          fire(el, 'play'); fire(el, 'playing')
        }
        resolve()
      }
      if (el.readyState >= 3) Promise.resolve().then(go)
      else {
        const once = () => { el.removeEventListener('canplay', once); go() }
        el.addEventListener('canplay', once)
        if (el.preload === 'none') realLoad.call(el)
      }
    })
  }
  P.pause = function () {
    const s = st(this)
    if (s.playing) { logSeg(this, 'stop'); s.playing = false; live.delete(this); Promise.resolve().then(() => fire(this, 'pause')) }
  }

  // ---- stepping ----
  const anims = new WeakMap()
  const syncAnimations = () => {
    for (const a of document.getAnimations()) {
      if (!anims.has(a)) { anims.set(a, V.t - (a.currentTime || 0)); try { a.pause() } catch {} }
      try { a.currentTime = V.t - anims.get(a) } catch {}
    }
  }
  const advanceMedia = (dt) => {
    for (const el of [...live]) {
      const s = st(el)
      if (!s.playing) { live.delete(el); continue }
      const dur = el.duration
      s.pos += (dt / 1000) * (el.playbackRate || 1)
      if (Number.isFinite(dur) && s.pos >= dur) {
        s.pos = dur; s.playing = false; s.ended = true; live.delete(el)
        logSeg(el, 'end')
        fire(el, 'timeupdate'); fire(el, 'pause'); fire(el, 'ended')
      } else if (V.t - s.lastTU >= 250) { s.lastTU = V.t; fire(el, 'timeupdate') }
    }
  }
  V.enable = () => { if (V.on) return; V.t = realNow(); V.dateBase = realDateNow() - V.t; V.on = true }
  // Advance virtual time by `ms`, firing timers in order, then one rAF tick.
  V.step = (ms) => {
    const end = V.t + ms
    const sub = 4 // media/timer resolution in ms
    while (V.t < end) {
      let next = Math.min(end, V.t + sub)
      for (const tm of V.timers.values()) if (tm.due < next) next = Math.max(V.t, tm.due)
      const dt = next - V.t
      V.t = next
      if (dt > 0) advanceMedia(dt)
      for (const [id, tm] of [...V.timers]) {
        if (tm.due <= V.t && V.timers.has(id)) {
          if (tm.repeat) tm.due = V.t + tm.ms; else V.timers.delete(id)
          try { tm.cb(...tm.args) } catch (e) { console.error(e) }
        }
      }
    }
    const cbs = [...V.rafs.values()]; V.rafs.clear()
    for (const cb of cbs) { try { cb(V.t) } catch (e) { console.error(e) } }
    syncAnimations()
  }
})()
