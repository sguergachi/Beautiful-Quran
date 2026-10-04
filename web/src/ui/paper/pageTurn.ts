import { paintPage, pixelDrift, snapFrame, type Frame } from './pagePicture'
import { PageTurnGl, type Rgba, type TurnScene } from './pageTurnGl'
import { SINGLE_PERSPECTIVE, SPREAD_PERSPECTIVE, leafPose, type TurnKind } from './pageTurnMotion'

export interface TurnRequest {
  /** Which edge of the leaf is bound. */
  hinge: 'left' | 'right'
  dir: 'on' | 'back'
  /** One leaf with nowhere to land (phones): it lifts to edge-on. */
  single: boolean
  /** The share of the book's block the turn carries, 0 to 1; 0 for one leaf. */
  wad: number
  ms: number
  /** What each page's picture is kept under ([keepPagePicture]), if it can be kept. */
  frontKey?: string
  backKey?: string
  /** A page already lying on the book, pictured from there instead of from its stage. */
  frontSource?: Element
  backSource?: Element
}

/** Where on the book a staged page lies: a page of the spread, or a phone's one leaf. */
export type PagePlace = 'recto' | 'verso' | 'single'

let renderer: PageTurnGl | null | undefined

function rendererFor(pixels: number): PageTurnGl | null {
  if (renderer?.lost) renderer = undefined
  if (renderer === undefined) renderer = PageTurnGl.create(pixels)
  return renderer
}

/** Builds the context and its shaders ahead of the first turn. */
export function warmPageTurn() {
  if (renderer !== undefined) return
  const build = () => {
    rendererFor(window.innerWidth * window.innerHeight * window.devicePixelRatio ** 2)
  }
  if ('requestIdleCallback' in window) window.requestIdleCallback(build, { timeout: 2000 })
  else setTimeout(build, 300)
}

/** `rgb(r, g, b)` or `rgba(r, g, b, a)` as computed, each channel 0 to 1. */
export function parseRgba(color: string): Rgba | null {
  const match = /^rgba?\(([^)]+)\)$/.exec(color.trim())
  if (!match) return null
  const parts = match[1].split(/[,/\s]+/).filter(Boolean).map(Number)
  if (parts.length < 3 || !parts.every(Number.isFinite)) return null
  return [parts[0] / 255, parts[1] / 255, parts[2] / 255, parts[3] ?? 1]
}

function frameOf(el: Element): Frame {
  const box = el.getBoundingClientRect()
  return { left: box.left, top: box.top, width: box.width, height: box.height }
}

/*
 * Pictures kept ahead of their turn.
 *
 * Taking a page's picture is the slow part of lifting a leaf (some 20 ms a
 * page, and the page has to be rendered first), and it used to be done on
 * the click: the leaf stood still for a tenth of a second before it moved.
 * A page that is not being recited looks the same until it is turned, so
 * its picture is taken when the reader is idle and kept here, already on
 * the GPU. The lift then draws nothing and uploads nothing.
 *
 * A picture is kept under a key its owner makes from everything the page's
 * look depends on; the theme and the screen's density are added here.
 */
interface Kept {
  picture: HTMLCanvasElement
  width: number
  height: number
}

const ALBUM_SIZE = 8
const album = new Map<string, Kept>()

function albumKey(key: string): string {
  const root = document.documentElement
  return `${key}|${root.getAttribute('data-theme') ?? ''}|${root.getAttribute('data-colors') ?? ''}|${window.devicePixelRatio}`
}

export function hasPagePicture(key: string): boolean {
  return album.has(albumKey(key))
}

/** Drops every kept picture: something they do not key on has changed. */
export function clearPagePictures() {
  for (const stale of album.values()) renderer?.release(stale.picture)
  album.clear()
}

function keep(key: string, picture: HTMLCanvasElement, frame: Frame) {
  const name = albumKey(key)
  const old = album.get(name)
  if (old) renderer?.release(old.picture)
  album.delete(name)
  album.set(name, { picture, width: frame.width, height: frame.height })
  for (const [oldest, stale] of album) {
    if (album.size <= ALBUM_SIZE) break
    album.delete(oldest)
    renderer?.release(stale.picture)
  }
}

/** The kept picture for [key], if it was taken of a page this size. */
function kept(key: string | undefined, frame: Frame): HTMLCanvasElement | null {
  if (!key) return null
  const name = albumKey(key)
  const found = album.get(name)
  if (!found) return null
  if (Math.abs(found.width - frame.width) > 0.01 || Math.abs(found.height - frame.height) > 0.01) {
    album.delete(name)
    renderer?.release(found.picture)
    return null
  }
  // Used again: last to be let go.
  album.delete(name)
  album.set(name, found)
  return found.picture
}

/** What taking pictures in [root] needs: the renderer, the scale, and the paper. */
function studio(root: HTMLElement) {
  const rootBox = frameOf(root)
  if (rootBox.width < 1 || rootBox.height < 1) return null
  const within = frameOf(root.closest('.app-shell') ?? document.documentElement)
  const gl = rendererFor(within.width * within.height * window.devicePixelRatio ** 2)
  if (!gl) return null
  const longest = Math.max(rootBox.width, rootBox.height)
  const scale = Math.min(window.devicePixelRatio || 1, gl.pictureLimit / longest)
  // The canvas is the whole window, whatever the turn: a leaf stands toward
  // the eye as it turns and overhangs its book, and a canvas resized to each
  // turn's reach came up empty for the frame it was resized in.
  const box = snapFrame(within, scale)
  gl.size(box, scale)
  return { gl, scale, rootBox, box }
}

/**
 * The picture of the page staged in [stage], and where it lies. Kept
 * pictures are used as they are; one that has to be taken now is kept if it
 * has a [key]. Null when the page is neither staged nor kept.
 */
function pictureOf(
  root: HTMLElement,
  stage: HTMLElement,
  place: PagePlace,
  key: string | undefined,
  scale: number,
  source?: Element,
): { picture: HTMLCanvasElement; frame: Frame; fresh: boolean } | null {
  const frame = snapFrame(frameOf(stage), scale)
  if (source) {
    // A sheet lying on the book: its own pixels, where it lies.
    const paper = getComputedStyle(source).backgroundColor
    return { picture: paintPage(source, frame, scale, paper, pixelDrift(source, scale)), frame, fresh: true }
  }
  const ready = kept(key, frame)
  if (ready) return { picture: ready, frame, fresh: false }
  if (!stage.firstElementChild) return null
  // The picture is rounded to pixels as the page it stands in for is: the
  // leaf of that side lying on the book now, or this leaf's own place.
  const leaves = place === 'single'
    ? []
    : Array.from(root.closest('.app-shell')?.querySelectorAll(`.mushaf[data-side="${place}"]`) ?? [])
  const lying = leaves.find((leaf) => !leaf.closest('.mushaf-flip, .mushaf-fit-probes')) ?? root
  const picture = paintPage(stage, frame, scale, getComputedStyle(stage).backgroundColor, pixelDrift(lying, scale))
  if (key) keep(key, picture, frame)
  return { picture, frame, fresh: !key }
}

/**
 * Takes the picture of the one page staged in [root] and keeps it under
 * [key], on the GPU, for the turn that will want it.
 */
export function keepPagePicture(root: HTMLElement, key: string, place: PagePlace) {
  const stage = root.querySelector<HTMLElement>(':scope > .mushaf-flip-page')
  const room = studio(root)
  if (!stage || !room) return
  const shot = pictureOf(root, stage, place, key, room.scale)
  if (shot) room.gl.hold(shot.picture)
}

/**
 * Lifts the leaf staged in [root] and turns it. [root] holds the leaf's two
 * pages, laid out where they lie on the book and hidden (`[data-face]`), or
 * empty where a picture is already kept; the leaf is drawn on a canvas
 * added to [root] for as long as the turn lasts. Returns what stops it and
 * takes the canvas away. [onEnd] is called once the leaf has landed, and at
 * once where there is nothing to draw it with.
 */
export function startPageTurn(root: HTMLElement, request: TurnRequest, onEnd: () => void): () => void {
  let frame = 0
  let stopped = false
  const skip = () => {
    frame = requestAnimationFrame(() => { if (!stopped) onEnd() })
    return () => { stopped = true; cancelAnimationFrame(frame) }
  }

  const front = root.querySelector<HTMLElement>(':scope > [data-face="front"]')
  const back = root.querySelector<HTMLElement>(':scope > [data-face="back"]')
  const room = studio(root)
  if (!front || !room) return skip()
  const { gl, scale, rootBox, box } = room

  const frontPlace: PagePlace = request.single ? 'single' : request.hinge === 'right' ? 'verso' : 'recto'
  const backPlace: PagePlace = frontPlace === 'verso' ? 'recto' : 'verso'
  const face = pictureOf(root, front, frontPlace, request.frontKey, scale, request.frontSource)
  const under = back ? pictureOf(root, back, backPlace, request.backKey, scale, request.backSource) : null
  if (!face || (back && !under)) return skip()

  const probe = document.createElement('i')
  probe.className = 'mushaf-flip-probe'
  root.append(probe)
  const tone = (value: string, otherwise: Rgba): Rgba => {
    probe.style.color = value
    return parseRgba(getComputedStyle(probe).color) ?? otherwise
  }
  const paper = parseRgba(getComputedStyle(front).backgroundColor) ?? [1, 1, 1, 1]
  const shade = tone('var(--book-gutter-shade, rgb(60 44 20 / 0.16))', [0.2, 0.17, 0.08, 0.16])
  const edgeLine = tone('var(--book-edge-line, rgb(110 104 88 / 0.3))', [0.43, 0.41, 0.35, 0.3])
  const edgeShade = tone('var(--book-edge-shade, rgb(60 44 20 / 0.14))', [0.2, 0.17, 0.08, 0.14])
  const block = probe.getBoundingClientRect().width
  probe.remove()

  const width = request.single ? rootBox.width : rootBox.width / 2
  const hingeX = request.single ? rootBox.left + rootBox.width : rootBox.left + rootBox.width / 2
  const originX = rootBox.left + rootBox.width / 2
  const originY = rootBox.top + rootBox.height / 2
  const perspective = request.single ? SINGLE_PERSPECTIVE : SPREAD_PERSPECTIVE
  const wad = request.single ? 0 : request.wad * block
  const scene: TurnScene = {
    box,
    scale,
    hingeX,
    top: face.frame.top,
    width,
    height: face.frame.height,
    side: request.hinge === 'right' ? -1 : 1,
    front: { picture: face.picture, frame: face.frame },
    back: under ? { picture: under.picture, frame: under.frame } : null,
    wad,
    originX,
    originY,
    perspective,
    paper,
    shade,
    edgeLine,
    edgeShade,
  }
  gl.load(scene)
  const canvas = gl.canvas
  canvas.style.left = `${scene.box.left - rootBox.left}px`
  canvas.style.top = `${scene.box.top - rootBox.top}px`
  root.append(canvas)

  const kind: TurnKind = {
    single: request.single,
    wad: request.wad,
    reverse: request.single && request.dir === 'back',
  }
  // Drawn before the browser paints: the leaf lies on the page it lifts
  // from in the same frame that page changes beneath it.
  gl.draw(leafPose(0, kind))
  let began: number | null = null
  const step = (now: number) => {
    if (stopped) return
    // The clock starts on the first frame after this one, so the work of
    // lifting the leaf is not taken out of its turn.
    began ??= now
    const t = Math.min(1, (now - began) / request.ms)
    gl.draw(leafPose(t, kind))
    if (t < 1) frame = requestAnimationFrame(step)
    else onEnd()
  }
  frame = requestAnimationFrame(step)

  return () => {
    stopped = true
    cancelAnimationFrame(frame)
    canvas.remove()
    gl.clear()
    // A picture nobody keeps (a page being recited changes word by word).
    if (face.fresh) gl.release(face.picture)
    if (under?.fresh) gl.release(under.picture)
  }
}
