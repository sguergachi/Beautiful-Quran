/**
 * Shared DOM helpers for directional ink / paper-cover washes.
 * Controllers only — engines stay pure in `ui/reader/InkEngine`.
 *
 * Progress timelines run through Motion (`animate`) so wash easing matches
 * the rest of the reader (and Android's cubic-bezier curves).
 *
 * **Fidelity law:** the active-word wash MUST use the smootherstep directional
 * mask (`washMaskImage` / `paperCoverMaskImage`) so the reader sees the soft
 * faded leading edge — the same `letterFadeIn` / `shapedWordBloom` look as
 * Android. Never replace this with whole-word opacity or a hard `scaleX` cut
 * for "performance". Quantize + cache mask strings so the hot path stays cheap.
 */
import { animate, type AnimationPlaybackControls } from 'motion'
import {
  cubicBezierEase,
  paperCoverMaskImage,
  washMaskImage,
} from '../ui/theme/Fade'
import { getTuning } from '../ui/reader/InkEngine'
import { tarjiSignal } from '../playback/tarjiSignal'
import { TarjiWordGate } from '../playback/tarji'
import {
  glintGlowAlpha,
  glintLetterLight,
  glintLightLagMs,
  glintLightLevel,
  glintResonance,
} from './glintLight'
import { cubicBezierTuple, type CubicBezierEase } from '../ui/motion/easing'

/** Subpixel steps even on long words; keep the cache bounded across tuning changes. */
const WASH_STEPS = 256
const MASK_CACHE_LIMIT = 2048

const washMaskCache = new Map<string, string>()
const paperMaskCache = new Map<string, string>()

function quantizeProgress(p: number): number {
  if (p >= 1) return 1
  if (p <= 0) return 0
  return Math.min(WASH_STEPS - 1, Math.round(p * WASH_STEPS)) / WASH_STEPS
}

function sweepEase() {
  const t = getTuning()
  return {
    x1: t.sweepEaseX1,
    y1: t.sweepEaseY1,
    x2: t.sweepEaseX2,
    y2: t.sweepEaseY2,
  }
}

export function cachedWashMask(
  progress: number,
  restingAlpha: number,
  rtl: boolean,
  feather: number,
): string {
  const q = quantizeProgress(progress)
  if (q >= 1) return 'none'
  const key = `${q}|${restingAlpha}|${rtl ? 1 : 0}|${feather}`
  let mask = washMaskCache.get(key)
  if (mask == null) {
    mask = washMaskImage(q, restingAlpha, rtl, feather)
    if (washMaskCache.size >= MASK_CACHE_LIMIT) washMaskCache.clear()
    washMaskCache.set(key, mask)
  }
  return mask
}

export function cachedPaperCoverMask(
  progress: number,
  restingAlpha: number,
  rtl: boolean,
  feather: number,
): string {
  const q = quantizeProgress(progress)
  if (q >= 1) return 'none'
  const key = `${q}|${restingAlpha}|${rtl ? 1 : 0}|${feather}`
  let mask = paperMaskCache.get(key)
  if (mask == null) {
    mask = paperCoverMaskImage(q, restingAlpha, rtl, feather)
    if (paperMaskCache.size >= MASK_CACHE_LIMIT) paperMaskCache.clear()
    paperMaskCache.set(key, mask)
  }
  return mask
}

export function applyMask(el: HTMLElement | SVGElement, mask: string) {
  if (mask === 'none') {
    if (el.style.maskImage || el.style.webkitMaskImage) {
      el.style.removeProperty('mask-image')
      el.style.removeProperty('-webkit-mask-image')
    }
    el.classList.remove('word-wash')
    return
  }
  // Skip redundant writes when the quantized mask hasn't changed.
  if (el.style.maskImage === mask) {
    if (!el.classList.contains('word-wash')) el.classList.add('word-wash')
    return
  }
  el.style.setProperty('mask-image', mask)
  el.style.setProperty('-webkit-mask-image', mask)
  el.classList.add('word-wash')
}

/** Snap-clear an opaque paper cover without exposing a transition frame. */
export function clearPaperCover(cover: HTMLElement) {
  cover.style.transition = 'none'
  cover.classList.remove('ink-cover-peel', 'word-wash')
  cover.removeAttribute('data-peel')
  cover.style.removeProperty('transform')
  cover.style.removeProperty('transform-origin')
  applyMask(cover, 'none')
  cover.style.removeProperty('opacity')
  cover.style.removeProperty('will-change')
}

export type WashTick = (progress: number, eased: number) => void

/**
 * Run a one-shot wash from 0→1 with cubic-bezier easing via Motion.
 * Returns a cancel function (stops the animation mid-flight).
 */
export function runWash(
  durationMs: number,
  ease: { x1: number; y1: number; x2: number; y2: number } | CubicBezierEase,
  _easeFn: (
    t: number,
    x1: number,
    y1: number,
    x2: number,
    y2: number,
  ) => number,
  onTick: WashTick,
  onDone?: () => void,
): () => void {
  const curve: CubicBezierEase =
    'x1' in ease ? cubicBezierTuple(ease.x1, ease.y1, ease.x2, ease.y2) : ease

  let controls: AnimationPlaybackControls | null = null
  let cancelled = false

  // Keep the animation clock continuous. Only mask strings are quantized;
  // secondary ink and glint opacity must retain display-refresh precision.
  controls = animate(0, 1, {
    duration: Math.max(0.001, durationMs / 1000),
    ease: [...curve] as [number, number, number, number],
    onUpdate: (eased) => {
      if (cancelled) return
      onTick(eased, eased)
    },
    onComplete: () => {
      if (cancelled) return
      onTick(1, 1)
      onDone?.()
    },
  })

  return () => {
    cancelled = true
    controls?.stop()
  }
}

/**
 * Active Arabic paper-cover bloom — Android `shapedWordBloom`.
 *
 * Glyphs stay full opaque ink; a paper overlay peels away with the
 * smootherstep directional mask so the soft faded edge is always visible.
 */
export function runPaperCoverWash(
  cover: HTMLElement,
  rtl: boolean,
  durationMs: number,
  ease: { x1: number; y1: number; x2: number; y2: number } | CubicBezierEase,
  restingAlpha: number,
  onTick?: WashTick,
  onDone?: () => void,
): () => void {
  const t = getTuning()
  const feather = t.washFeather
  cover.style.transition = 'none'
  cover.style.opacity = '1'
  cover.classList.remove('ink-cover-peel')
  cover.removeAttribute('data-peel')
  cover.style.removeProperty('transform')
  cover.style.removeProperty('transform-origin')
  cover.style.removeProperty('will-change')
  applyMask(cover, cachedPaperCoverMask(0, restingAlpha, rtl, feather))

  return runWash(
    durationMs,
    ease,
    cubicBezierEase,
    (p, eased) => {
      if (p >= 1) {
        clearPaperCover(cover)
        onTick?.(p, eased)
        return
      }
      applyMask(cover, cachedPaperCoverMask(eased, restingAlpha, rtl, feather))
      onTick?.(p, eased)
    },
    () => {
      clearPaperCover(cover)
      onDone?.()
    },
  )
}

/**
 * Active English letter wash — Android `letterFadeIn`.
 * Directional smootherstep mask on the glyph (soft faded edge required).
 */
export function runLetterWash(
  el: HTMLElement,
  rtl: boolean,
  durationMs: number,
  ease: { x1: number; y1: number; x2: number; y2: number } | CubicBezierEase,
  restingAlpha: number,
  onTick?: WashTick,
  onDone?: () => void,
): () => void {
  const t = getTuning()
  const feather = t.washFeather
  el.style.removeProperty('opacity')
  applyMask(el, cachedWashMask(0, restingAlpha, rtl, feather))

  return runWash(
    durationMs,
    ease,
    cubicBezierEase,
    (p, eased) => {
      if (p >= 1) {
        applyMask(el, 'none')
        onTick?.(p, eased)
        return
      }
      applyMask(el, cachedWashMask(eased, restingAlpha, rtl, feather))
      onTick?.(p, eased)
    },
    () => {
      applyMask(el, 'none')
      onDone?.()
    },
  )
}

/** @deprecated Use [runPaperCoverWash] — kept so older call sites type-check. */
export function runPaperCoverPeel(
  cover: HTMLElement,
  rtl: boolean,
  durationMs: number,
  ease: { x1: number; y1: number; x2: number; y2: number } | CubicBezierEase,
  onTick?: WashTick,
  onDone?: () => void,
): () => void {
  return runPaperCoverWash(
    cover,
    rtl,
    durationMs,
    ease,
    getTuning().upcomingAlpha,
    onTick,
    onDone,
  )
}

/** @deprecated Use [runLetterWash] for directional English ink. */
export function runOpacityReveal(
  el: HTMLElement,
  fromAlpha: number,
  _toAlpha: number,
  durationMs: number,
  ease: { x1: number; y1: number; x2: number; y2: number } | CubicBezierEase,
  onTick?: WashTick,
  onDone?: () => void,
): () => void {
  return runLetterWash(el, false, durationMs, ease, fromAlpha, onTick, onDone)
}

/**
 * Theme half of the fresh-ink glint gate (the word half is
 * `InkEngine.glinting`): the white-gold first-gloss sheen is a Nightfall and
 * Royal Green signature, keyed off themes that define `--glint` in styles.css.
 */
export function glintEnabled(): boolean {
  const theme = document.documentElement.getAttribute('data-theme')
  return theme === 'dark' || theme === 'royal_green'
}

/**
 * Last eased orange-wash progress per overlay (0→1).
 * **Absent** from the map means the word never started a wash — residual
 * release must no-op (do not treat missing as progress 0 and bloom orange).
 */
const repeatWashProgress = new WeakMap<HTMLElement, number>()

export function hasRepeatWashProgress(el: HTMLElement): boolean {
  return repeatWashProgress.has(el)
}

export function getRepeatWashProgress(el: HTMLElement): number {
  return repeatWashProgress.get(el) ?? 0
}

function setRepeatWashProgress(el: HTMLElement, progress: number) {
  repeatWashProgress.set(el, progress)
}

function clearRepeatWashProgress(el: HTMLElement) {
  repeatWashProgress.delete(el)
}

/**
 * Tinted overlay wash-in (orange repeat, white-gold glint): directional
 * ink-engine mask (restingAlpha 0) over the overlay, then clear the mask so
 * the full tint holds. Tracks progress for residual finish on release.
 */
export function runRepeatWashIn(
  el: HTMLElement,
  rtl: boolean,
  durationMs: number,
  onDone?: () => void,
  ease: CubicBezierEase | ReturnType<typeof sweepEase> = sweepEase(),
): () => void {
  return runRepeatWashFrom(el, rtl, 0, durationMs, onDone, ease)
}

/**
 * Continue (or start) a directional orange wash from [fromProgress]→1.
 * Never snaps incomplete→full; the soft edge always travels the remainder.
 */
export function runRepeatWashFrom(
  el: HTMLElement,
  rtl: boolean,
  fromProgress: number,
  totalDurationMs: number,
  onDone?: () => void,
  ease: CubicBezierEase | ReturnType<typeof sweepEase> = sweepEase(),
): () => void {
  const feather = getTuning().washFeather
  const from = Math.min(1, Math.max(0, fromProgress))
  el.style.opacity = '1'
  el.style.removeProperty('transform')
  el.style.removeProperty('transform-origin')
  el.classList.remove('ink-cover-peel')
  el.removeAttribute('data-peel')

  if (from >= 1) {
    setRepeatWashProgress(el, 1)
    applyMask(el, 'none')
    onDone?.()
    return () => {}
  }

  const remainMs = Math.max(1, (1 - from) * totalDurationMs)
  setRepeatWashProgress(el, from)
  applyMask(el, cachedWashMask(from, 0, rtl, feather))
  return runWash(
    remainMs,
    ease,
    cubicBezierEase,
    (_p, eased) => {
      const progress = from + eased * (1 - from)
      setRepeatWashProgress(el, progress)
      applyMask(el, cachedWashMask(progress, 0, rtl, feather))
    },
    () => {
      setRepeatWashProgress(el, 1)
      applyMask(el, 'none')
      onDone?.()
    },
  )
}

/**
 * Promise form of [runRepeatWashIn] for sequential chain washes.
 * Resolves when the soft edge reaches full orange. Does **not** cancel on
 * handoff — callers only abandon *queued* work still waiting on the gate.
 */
export function runRepeatWashInAsync(
  el: HTMLElement,
  rtl: boolean,
  durationMs: number,
): Promise<void> {
  return new Promise((resolve) => {
    runRepeatWashIn(el, rtl, durationMs, () => resolve())
  })
}

/**
 * Finish residual orange edge only (animate remainder — never snap).
 * No-ops when the overlay never started a wash (absent progress) so a
 * queued-then-dropped chain member cannot bloom orange after release.
 * Call under the ordered gate; alpha dissolve stays outside the gate.
 */
export function runRepeatResidualAsync(
  el: HTMLElement,
  rtl: boolean,
): Promise<void> {
  if (!hasRepeatWashProgress(el)) return Promise.resolve()
  const t = getTuning()
  const from = getRepeatWashProgress(el)
  if (from >= 1) {
    applyMask(el, 'none')
    return Promise.resolve()
  }
  return new Promise((resolve) => {
    runRepeatWashFrom(el, rtl, from, t.repeatSweepMs, () => resolve())
  })
}

export type CancellablePromise = Promise<void> & { cancel: () => void }

/** Opacity dissolve after residual is complete (or was already full). */
export function runRepeatFadeOutAsync(el: HTMLElement): CancellablePromise {
  let cancelFn: (() => void) | null = null
  let settled = false
  let resolvePromise: (() => void) | null = null
  const promise = new Promise<void>((resolve) => {
    resolvePromise = resolve
    cancelFn = runRepeatFadeOut(el, () => {
      if (settled) return
      settled = true
      resolve()
    })
  }) as CancellablePromise
  promise.cancel = () => {
    if (settled) return
    settled = true
    cancelFn?.()
    cancelFn = null
    // Leave opacity alone — re-entry wash-in will set it to 1.
    resolvePromise?.()
  }
  return promise
}

/**
 * Orange dissolve after a **completed** wash (search-hit flash). Snaps mask
 * clear then fades opacity — only valid when progress is already 1.
 * Chain release: residual under gate + [runRepeatFadeOutAsync] outside.
 */
export function runRepeatFadeOut(
  el: HTMLElement,
  onDone?: () => void,
  durationMs = getTuning().repeatFadeOutMs,
): () => void {
  applyMask(el, 'none')
  setRepeatWashProgress(el, 1)
  return runWash(
    durationMs,
    sweepEase(),
    cubicBezierEase,
    (_p, eased) => {
      el.style.opacity = String(1 - eased)
    },
    () => {
      el.style.opacity = '0'
      clearRepeatWashProgress(el)
      onDone?.()
    },
  )
}

/** White-gold glyph sheen plus its subtle outline halo. */
export function runGlintWashIn(
  ink: HTMLElement | null,
  halo: HTMLElement,
  rtl: boolean,
  durationMs: number,
  motion?: {
    eligible: boolean
    wordStartMs: number
    washProgress?: () => number
    repeat?: boolean
  },
): () => void {
  halo.style.opacity = '0'
  if (motion?.repeat) {
    halo.style.setProperty('--glint', 'var(--repeat)')
    ink?.style.setProperty('--glint', 'var(--repeat)')
  } else {
    halo.style.removeProperty('--glint')
    ink?.style.removeProperty('--glint')
  }
  const glyph = !ink
    ? halo.parentElement?.querySelector<HTMLElement>(
        ':scope > .hafs-glyph, :scope > .word-arabic',
      )
    : null
  if (glyph) {
    // Tint the authoritative filled glyph, never a second Hafs rasterization.
    glyph.style.color =
      'color-mix(in srgb, var(--ink), var(--glint-lit, var(--ink)) var(--glint-tint, 0%))'
    glyph.style.setProperty('--glint-tint', '0%')
  }
  const cancelInk =
    ink && !motion?.washProgress ? runRepeatWashIn(ink, rtl, durationMs) : null
  const gate = new TarjiWordGate(tarjiSignal.eventLedger)
  let formation = 0,
    glow = 0,
    wet = 1,
    frame = 0,
    last = performance.now()
  const source = tarjiSignal.sourceKey
  const cancelFormation = runWash(
    durationMs,
    sweepEase(),
    cubicBezierEase,
    (_p, eased) => {
      formation = eased
    },
  )
  const tick = (now: number) => {
    const delta = Math.max(0, now - last)
    last = now
    if (motion?.washProgress) {
      formation = motion.washProgress()
      if (ink) {
        ink.style.opacity = String(wet)
        applyMask(
          ink,
          cachedWashMask(formation, 0, rtl, getTuning().washFeather),
        )
      }
    }
    const live = tarjiSignal.sample()
    const paused = source !== '' && !tarjiSignal.isPlaying
    wet += (1 - Math.exp(-delta / 250)) * ((paused ? 0 : 1) - wet)
    const allowed =
      !paused &&
      motion?.eligible &&
      source === tarjiSignal.sourceKey &&
      gate.allows(
        live.gain,
        live.eventMs >= 0,
        live.eventMs,
        motion.wordStartMs,
      )
    // Read ahead by the light filter's phase lag, on the same source clock.
    const ahead = allowed
      ? tarjiSignal.sample(glintLightLagMs(139, live.rate, tarjiSignal.speed))
      : live
    const target = allowed ? glintResonance(ahead.pulse, ahead.gain) : 0
    glow += (1 - Math.exp(-delta / 139)) * (target - glow)
    const level = glintLightLevel(glow, tarjiSignal.lightBrightness)
    const letters = glintLetterLight(level)
    halo.style.opacity = String(formation * wet)
    halo.style.setProperty(
      '--glint-halo-alpha',
      `${glintGlowAlpha(0.4514, level) * 100}%`,
    )
    halo.style.setProperty(
      '--glint-bloom-alpha',
      `${glintGlowAlpha(0.75, level) * 100}%`,
    )
    halo.style.setProperty(
      '--glint-veil-alpha',
      `${glintGlowAlpha(0.6, level) * 100}%`,
    )
    halo.style.setProperty('--glint-light', String(Math.pow(letters, 1 / 2.2)))
    if (glyph) {
      glyph.style.setProperty('--glint-tint', `${88 * formation * wet}%`)
      glyph.style.setProperty(
        '--glint-lit',
        `color-mix(in srgb-linear, ${motion?.repeat ? 'var(--repeat)' : 'var(--glint, var(--ink))'} ${letters * 100}%, black)`,
      )
    }
    if (ink)
      ink.style.setProperty('--glint-light', String(Math.pow(letters, 1 / 2.2)))
    frame = requestAnimationFrame(tick)
  }
  frame = requestAnimationFrame(tick)
  return () => {
    cancelInk?.()
    cancelFormation()
    cancelAnimationFrame(frame)
  }
}

/** Glimmer dry-down: glyph sheen and its halo recede together. */
export function runGlintFadeOut(
  ink: HTMLElement | null,
  halo: HTMLElement,
  onDone?: () => void,
  durationMs = getTuning().glintFadeMs,
): () => void {
  if (ink) applyMask(ink, 'none')
  const glyph = !ink
    ? halo.parentElement?.querySelector<HTMLElement>(
        ':scope > .hafs-glyph, :scope > .word-arabic',
      )
    : null
  const fromTint = glyph
    ? parseFloat(glyph.style.getPropertyValue('--glint-tint')) || 0
    : 0
  const fade = (el: HTMLElement, done?: () => void) => {
    const from = Number(el.style.opacity || 0)
    return runWash(
      durationMs,
      sweepEase(),
      cubicBezierEase,
      (_p, eased) => {
        el.style.opacity = String(from * (1 - eased))
        if (glyph && el === halo)
          glyph.style.setProperty('--glint-tint', `${fromTint * (1 - eased)}%`)
      },
      () => {
        el.style.opacity = '0'
        if (glyph && el === halo) {
          glyph.style.removeProperty('color')
          glyph.style.removeProperty('--glint-tint')
          glyph.style.removeProperty('--glint-lit')
        }
        done?.()
      },
    )
  }
  const cancelInk = ink ? fade(ink, onDone) : null
  const cancelHalo = fade(halo, ink ? undefined : onDone)
  return () => {
    cancelInk?.()
    cancelHalo()
  }
}

/**
 * Search-hit locator: the complete orange word softly fades in and out.
 * Callers pass a dedicated orange overlay (same classes as the karaoke repeat
 * layer), so opacity can breathe without changing the measured text.
 */
export function runSearchHitWash(
  el: HTMLElement,
  timing: {
    BREATHS: number
    INHALE_MS: number
    CREST_MS: number
    EXHALE_MS: number
    REST_MS: number
    EASING: CubicBezierEase
  },
  onDone?: () => void,
): () => void {
  let cancelled = false
  let cancelCurrent: (() => void) | null = null

  const finish = () => {
    el.style.opacity = '0'
    el.style.removeProperty('transform')
    el.style.removeProperty('transform-origin')
    el.style.removeProperty('will-change')
    el.classList.remove('ink-cover-peel')
    el.removeAttribute('data-peel')
    applyMask(el, 'none')
    clearRepeatWashProgress(el)
  }

  let waitTimer: ReturnType<typeof setTimeout> | null = null
  const wait = (durationMs: number, next: () => void) => {
    waitTimer = setTimeout(() => {
      waitTimer = null
      if (!cancelled) next()
    }, durationMs)
  }
  const fade = (
    from: number,
    to: number,
    durationMs: number,
    next: () => void,
  ) => {
    el.style.opacity = String(from)
    cancelCurrent = runWash(
      durationMs,
      timing.EASING,
      cubicBezierEase,
      (_p, eased) => {
        el.style.opacity = String(from + (to - from) * eased)
      },
      () => {
        el.style.opacity = String(to)
        if (!cancelled) next()
      },
    )
  }
  const breathe = (remaining: number) => {
    fade(0, 1, timing.INHALE_MS, () => {
      wait(timing.CREST_MS, () => {
        fade(1, 0, timing.EXHALE_MS, () => {
          if (remaining > 1) wait(timing.REST_MS, () => breathe(remaining - 1))
          else {
            finish()
            onDone?.()
          }
        })
      })
    })
  }

  applyMask(el, 'none')
  // Only the few active search overlays get a temporary compositor hint;
  // finish() removes it as soon as the third breath ends.
  el.style.willChange = 'opacity'
  breathe(timing.BREATHS)

  return () => {
    cancelled = true
    if (waitTimer != null) clearTimeout(waitTimer)
    cancelCurrent?.()
    finish()
  }
}
