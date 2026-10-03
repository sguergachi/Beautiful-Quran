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
}

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

/**
 * The box the leaf can reach: it stands toward the eye as it turns, and
 * what is nearer is drawn larger, so it overhangs the page it left.
 */
export function sweepBox(
  hingeX: number,
  top: number,
  width: number,
  height: number,
  reach: number,
  originX: number,
  originY: number,
  perspective: number,
  within: Frame,
): Frame {
  const near = perspective / Math.max(1, perspective - reach)
  const left = Math.min(hingeX - width, originX + (hingeX - width - originX) * near)
  const right = Math.max(hingeX + width, originX + (hingeX + width - originX) * near)
  const head = Math.min(top, originY + (top - originY) * near)
  const foot = Math.max(top + height, originY + (top + height - originY) * near)
  const x0 = Math.max(within.left, left - 2)
  const y0 = Math.max(within.top, head - 2)
  const x1 = Math.min(within.left + within.width, right + 2)
  const y1 = Math.min(within.top + within.height, foot + 2)
  return { left: x0, top: y0, width: Math.max(1, x1 - x0), height: Math.max(1, y1 - y0) }
}

function frameOf(el: Element): Frame {
  const box = el.getBoundingClientRect()
  return { left: box.left, top: box.top, width: box.width, height: box.height }
}

/**
 * Lifts the leaf staged in [root] and turns it. [root] holds the leaf's two
 * pages, laid out where they lie on the book and hidden (`[data-face]`);
 * their pictures are taken, and the leaf is drawn on a canvas added to
 * [root] for as long as the turn lasts. Returns what stops it and takes the
 * canvas away. [onEnd] is called once the leaf has landed, and at once
 * where there is nothing to draw it with.
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
  const rootBox = frameOf(root)
  if (!front || rootBox.width < 1 || rootBox.height < 1) return skip()
  const within = frameOf(root.closest('.app-shell') ?? document.documentElement)
  const gl = rendererFor(within.width * within.height * window.devicePixelRatio ** 2)
  if (!gl) return skip()

  const longest = Math.max(rootBox.width, rootBox.height)
  const scale = Math.min(window.devicePixelRatio || 1, gl.pictureLimit / longest)
  const style = getComputedStyle(front)
  const paperCss = style.backgroundColor
  const probe = document.createElement('i')
  probe.className = 'mushaf-flip-probe'
  root.append(probe)
  const tone = (value: string, otherwise: Rgba): Rgba => {
    probe.style.color = value
    return parseRgba(getComputedStyle(probe).color) ?? otherwise
  }
  const paper = parseRgba(paperCss) ?? [1, 1, 1, 1]
  const shade = tone('var(--book-gutter-shade, rgb(60 44 20 / 0.16))', [0.2, 0.17, 0.08, 0.16])
  const edgeLine = tone('var(--book-edge-line, rgb(110 104 88 / 0.3))', [0.43, 0.41, 0.35, 0.3])
  const edgeShade = tone('var(--book-edge-shade, rgb(60 44 20 / 0.14))', [0.2, 0.17, 0.08, 0.14])
  const block = probe.getBoundingClientRect().width
  probe.remove()

  // Each picture is rounded to pixels as the page it stands in for is: the
  // leaf of that side lying on the book now, or this leaf's own place.
  const shell = root.closest('.app-shell')
  const lying = (side: 'recto' | 'verso'): Element => {
    const leaves = request.single ? [] : Array.from(shell?.querySelectorAll(`.mushaf[data-side="${side}"]`) ?? [])
    return leaves.find((leaf) => !leaf.closest('.mushaf-flip, .mushaf-fit-probes')) ?? root
  }
  const frontSide = request.hinge === 'right' ? 'verso' : 'recto'
  const frontDrift = pixelDrift(lying(frontSide), scale)
  const backDrift = pixelDrift(lying(frontSide === 'verso' ? 'recto' : 'verso'), scale)

  const frontFrame = snapFrame(frameOf(front), scale)
  const backFrame = back ? snapFrame(frameOf(back), scale) : null
  const width = request.single ? rootBox.width : rootBox.width / 2
  const hingeX = request.single ? rootBox.left + rootBox.width : rootBox.left + rootBox.width / 2
  const originX = rootBox.left + rootBox.width / 2
  const originY = rootBox.top + rootBox.height / 2
  const perspective = request.single ? SINGLE_PERSPECTIVE : SPREAD_PERSPECTIVE
  const wad = request.single ? 0 : request.wad * block
  const scene: TurnScene = {
    box: snapFrame(
      sweepBox(hingeX, frontFrame.top, width, frontFrame.height, width + wad, originX, originY, perspective, within),
      scale,
    ),
    scale,
    hingeX,
    top: frontFrame.top,
    width,
    height: frontFrame.height,
    side: request.hinge === 'right' ? -1 : 1,
    front: { picture: paintPage(front, frontFrame, scale, paperCss, frontDrift), frame: frontFrame },
    back: back && backFrame
      ? { picture: paintPage(back, backFrame, scale, paperCss, backDrift), frame: backFrame }
      : null,
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
  }
}
