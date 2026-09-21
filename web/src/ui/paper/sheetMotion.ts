import { cubicBezierEase } from '../theme/Fade'
import { SHEET_TURN_MS } from '../home/continueInk'
import { READER_LAYER, SETTINGS_LAYER, type StackLayer } from './stack'

/**
 * Live paper-stack progress for ink that has to scrub with the turn.
 * Web navigation is a settled layer plus a CSS glide, so this clock stands
 * in for Android's dragged `stackPosition`.
 *
 * Reader approach is 0 on the chapter list and 1 once the reader sheet has
 * landed (including while Settings sits on top of it). Settings approach is
 * the last leg into Settings, from whichever sheet is beneath it.
 */

type Listener = () => void

let readerApproach = 0
let settingsApproach = 0
let openSurahId = 0
let booted = false
let frame = 0

const listeners = new Set<Listener>()

export function getReaderApproach(): number {
  return readerApproach
}

export function getSettingsApproach(): number {
  return settingsApproach
}

export function getOpenSurahId(): number {
  return openSurahId
}

export function subscribeSheetMotion(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function emit(): void {
  for (const listener of listeners) listener()
}

type Anim = {
  fromReader: number
  toReader: number
  fromSettings: number
  toSettings: number
  start: number
}

let anim: Anim | null = null

function targets(hasReader: boolean, stack: StackLayer): { reader: number; settings: number } {
  const settingsLayer = hasReader ? SETTINGS_LAYER : READER_LAYER
  return {
    reader: hasReader && stack >= READER_LAYER ? 1 : 0,
    settings: stack >= settingsLayer ? 1 : 0,
  }
}

export function syncSheetMotion(input: {
  hasReader: boolean
  stack: StackLayer
  openSurahId: number
}): void {
  openSurahId = input.openSurahId
  const next = targets(input.hasReader, input.stack)
  if (!booted) {
    booted = true
    readerApproach = next.reader
    settingsApproach = next.settings
    emit()
    return
  }
  if (
    !anim &&
    next.reader === readerApproach &&
    next.settings === settingsApproach
  ) {
    emit()
    return
  }
  anim = {
    fromReader: readerApproach,
    toReader: next.reader,
    fromSettings: settingsApproach,
    toSettings: next.settings,
    start: performance.now(),
  }
  if (frame === 0) frame = requestAnimationFrame(tick)
  emit()
}

function tick(now: number): void {
  frame = 0
  if (!anim) return
  const u = Math.min(1, Math.max(0, (now - anim.start) / SHEET_TURN_MS))
  const e = cubicBezierEase(u, 0.24, 0.02, 0.12, 1)
  readerApproach = anim.fromReader + (anim.toReader - anim.fromReader) * e
  settingsApproach = anim.fromSettings + (anim.toSettings - anim.fromSettings) * e
  emit()
  if (u >= 1) {
    readerApproach = anim.toReader
    settingsApproach = anim.toSettings
    anim = null
    emit()
    return
  }
  frame = requestAnimationFrame(tick)
}

/** Test hook. Production code never resets the clock. */
export function resetSheetMotionForTests(): void {
  if (frame) cancelAnimationFrame(frame)
  frame = 0
  anim = null
  booted = false
  readerApproach = 0
  settingsApproach = 0
  openSurahId = 0
  listeners.clear()
}
