/**
 * A picture of a page, taken from its DOM, for the leaf in the air.
 *
 * A turning leaf is a bent sheet, and only a texture can be bent. The page
 * is real DOM (words, verse marks, running head, folio), so its picture is
 * drawn here from where layout has already put everything: each word is set
 * with the canvas's own text shaping, in the element's font and colour, at
 * the box the browser measured for it. The same engine shapes both, so the
 * picture lands on the page it was taken from.
 *
 * It draws what a leaf is made of and no more: text, flat backgrounds
 * (the paper a waiting word lies under), opacity, clipping, 2D transforms,
 * and a fade at the foot of a scrolling block.
 */

/** A box in CSS pixels of the viewport. */
export interface Frame {
  left: number
  top: number
  width: number
  height: number
}

/**
 * [at], in CSS pixels, at the nearest whole device pixel. Layout positions
 * are 64ths of a device pixel and reach here through a division by the
 * scale, so a half is nudged back over the line it stood on.
 */
export function wholePixel(at: number, scale: number): number {
  return Math.round(at * scale + 0.002) / scale
}

/** [frame] moved out to whole device pixels, so a texel is a pixel at rest. */
export function snapFrame(frame: Frame, scale: number): Frame {
  const left = Math.round(frame.left * scale) / scale
  const top = Math.round(frame.top * scale) / scale
  const right = Math.round((frame.left + frame.width) * scale) / scale
  const bottom = Math.round((frame.top + frame.height) * scale) / scale
  return { left, top, width: right - left, height: bottom - top }
}

/** `matrix(a, b, c, d, e, f)` as computed; null for none, or one that is not 2D. */
export function parseMatrix(transform: string): [number, number, number, number, number, number] | null {
  const match = /^matrix\(([^)]+)\)$/.exec(transform.trim())
  if (!match) return null
  const parts = match[1].split(',').map(Number)
  return parts.length === 6 && parts.every(Number.isFinite)
    ? (parts as [number, number, number, number, number, number])
    : null
}

/**
 * The height of the fade a block's mask draws at its foot, from the computed
 * `linear-gradient(<ink> calc(100% - Npx), <clear>)`; 0 for anything else.
 */
export function parseFootFade(mask: string): number {
  if (!mask.startsWith('linear-gradient(')) return 0
  const match = /calc\(100% - ([\d.]+)px\)/.exec(mask)
  return match ? Number(match[1]) : 0
}

/** The runs of a text node that are set as one piece: its words. */
export function textRuns(text: string): { start: number; end: number }[] {
  const runs: { start: number; end: number }[] = []
  const word = /\S+/g
  for (let match = word.exec(text); match; match = word.exec(text)) {
    runs.push({ start: match.index, end: match.index + match[0].length })
  }
  return runs
}

/**
 * Where the browser really paints, against where layout says: [x] and [y]
 * are added to a layout position to get the pixel it lands on.
 *
 * Chromium rounds each run of text to a whole device pixel, paints a
 * transformed box from a whole pixel and lays its contents out from there,
 * and does the same for a layer that is kept ready to move (`will-change:
 * transform`), without passing the fraction it dropped on to what it holds.
 * A picture that sets text on the fractions layout reports stands a pixel
 * off for about half the words of a page, and they jump as the leaf lifts
 * and lands. Rounded the same way, the picture is the page. Measured
 * against Chromium's own painting, the share of a page's pixels that
 * differ: 0.02% at 1x and 2x (from 2%), 0.3% at 1.25x (from 1.4%).
 */
export interface Snap {
  x: number
  y: number
}

/**
 * How far the pixels of what [el] holds stand from where layout puts them:
 * each layer above it that is painted from a whole pixel drops a fraction.
 */
export function pixelDrift(el: Element | null, scale: number): Snap {
  const layers: Element[] = []
  for (let at = el; at; at = at.parentElement) layers.unshift(at)
  const drift = { x: 0, y: 0 }
  for (const layer of layers) {
    const style = getComputedStyle(layer)
    const moved = style.transform !== 'none' && style.transform !== 'matrix(1, 0, 0, 1, 0, 0)'
    if (!moved && !style.willChange.includes('transform')) continue
    const box = layer.getBoundingClientRect()
    drift.x = wholePixel(box.left + drift.x, scale) - box.left
    drift.y = wholePixel(box.top + drift.y, scale) - box.top
  }
  return drift
}

interface Placed {
  matrix: [number, number, number, number, number, number]
  /** Transform origin, from the element's own border box. */
  originX: number
  originY: number
}

/**
 * How far below the top of a text box its baseline lies, per font. Layout
 * rounds a font's ascent its own way in each engine, so it is read from
 * layout once (a box of no size sits on the baseline) and kept.
 */
const baselines = new Map<string, number>()

function fontOf(style: CSSStyleDeclaration): string {
  return `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
}

function baselineOf(node: Text, run: Range, font: string): number {
  const known = baselines.get(font)
  if (known != null) return known
  const probe = document.createElement('span')
  probe.style.cssText = 'display:inline-block;width:0;height:0;padding:0;border:0;margin:0'
  node.parentNode!.insertBefore(probe, node)
  const offset = probe.getBoundingClientRect().top - run.getBoundingClientRect().top
  probe.remove()
  baselines.set(font, offset)
  return offset
}

const spacesLetters = typeof CanvasRenderingContext2D !== 'undefined' &&
  'letterSpacing' in CanvasRenderingContext2D.prototype

/** The state a subtree is painted in. */
interface Painting {
  placed: Map<Element, Placed>
  range: Range
  scale: number
  /** Null where nothing is rounded. */
  snap: Snap | null
  /** How much of [snap] the canvas's own transform already carries. */
  carried: Snap
}

/**
 * Draws [source] and everything in it onto a canvas [frame] big, at [scale]
 * device pixels to the CSS pixel, on [paper]. [source] may be hidden with
 * `visibility`; it must be laid out. [drift] is that of the page this is a
 * picture of ([pixelDrift]), so the picture is rounded as that page is;
 * with none it is set where layout reports, unrounded.
 */
export function paintPage(
  source: HTMLElement,
  frame: Frame,
  scale: number,
  paper: string,
  drift: Snap | null = null,
): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(frame.width * scale))
  canvas.height = Math.max(1, Math.round(frame.height * scale))
  const ctx = canvas.getContext('2d', { alpha: false })!
  ctx.fillStyle = paper
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.setTransform(scale, 0, 0, scale, -frame.left * scale, -frame.top * scale)
  ctx.textBaseline = 'alphabetic'
  ctx.textAlign = 'left'

  // Boxes are read with every transform off: a transformed box reports where
  // it ends up, and the picture applies the transform itself.
  const placed = new Map<Element, Placed>()
  for (const el of source.querySelectorAll<HTMLElement>('*')) {
    const style = getComputedStyle(el)
    const matrix = parseMatrix(style.transform)
    if (!matrix) continue
    const [originX, originY] = style.transformOrigin.split(' ').map(parseFloat)
    placed.set(el, { matrix, originX: originX || 0, originY: originY || 0 })
  }
  source.setAttribute('data-flat', '')
  try {
    const painting: Painting = { placed, range: document.createRange(), scale, snap: drift, carried: { x: 0, y: 0 } }
    for (const child of source.children) paintElement(ctx, child, 1, painting)
  } finally {
    source.removeAttribute('data-flat')
  }
  return canvas
}

/** A layout position along one axis, as the canvas must be given it. */
function pixel(at: number, drift: number | undefined, carried: number, scale: number): number {
  return drift == null ? at : wholePixel(at + drift, scale) - carried
}

function paintElement(ctx: CanvasRenderingContext2D, el: Element, alpha: number, painting: Painting) {
  const style = getComputedStyle(el)
  if (style.display === 'none') return
  const opacity = alpha * (Number(style.opacity) || 0)
  if (opacity <= 0.002) return
  const box = el.getBoundingClientRect()

  ctx.save()
  const turn = painting.placed.get(el)
  if (turn) {
    // The box's corner goes to a whole pixel, and what it holds is laid out
    // from there: nothing above it rounds its contents any more.
    const { snap, carried } = painting
    // Across only: down the page a run is rounded from where layout has it,
    // transformed box or not (so measured).
    const within = snap ? { x: wholePixel(box.left + snap.x, painting.scale) - box.left, y: snap.y } : carried
    const lift = snap ? carried.y : within.y
    const x = box.left + within.x + turn.originX
    const y = box.top + lift + turn.originY
    ctx.translate(x - carried.x, y - carried.y)
    ctx.transform(...turn.matrix)
    ctx.translate(within.x - x, lift - y)
    painting = { ...painting, snap: snap ? within : null, carried: { x: within.x, y: lift } }
  }
  const { snap, carried, scale } = painting
  const left = pixel(box.left, snap?.x, carried.x, scale)
  const top = pixel(box.top, snap?.y, carried.y, scale)
  const right = pixel(box.right, snap?.x, carried.x, scale)
  const bottom = pixel(box.bottom, snap?.y, carried.y, scale)

  const fade = parseFootFade(style.maskImage || style.webkitMaskImage || '')
  // A faded block is drawn on a sheet of its own, so the fade takes the ink
  // and leaves the paper under it.
  let target = ctx
  let layer: HTMLCanvasElement | null = null
  if (fade > 0 && box.width >= 1 && box.height >= 1) {
    layer = document.createElement('canvas')
    layer.width = ctx.canvas.width
    layer.height = ctx.canvas.height
    target = layer.getContext('2d')!
    target.setTransform(ctx.getTransform())
    target.textBaseline = 'alphabetic'
    target.textAlign = 'left'
  }

  const background = style.backgroundColor
  if (background && background !== 'transparent' && !/, 0\)$/.test(background) && right > left && bottom > top) {
    target.globalAlpha = opacity
    target.fillStyle = background
    target.fillRect(left, top, right - left, bottom - top)
  }

  if (style.overflowX !== 'visible' || style.overflowY !== 'visible') {
    target.beginPath()
    target.rect(
      left + el.clientLeft,
      top + el.clientTop,
      right - left - (box.width - el.clientWidth),
      bottom - top - (box.height - el.clientHeight),
    )
    target.clip()
  }

  for (const child of el.childNodes) {
    if (child.nodeType === Node.TEXT_NODE) paintText(target, child as Text, style, opacity, painting)
    else if (child.nodeType === Node.ELEMENT_NODE) paintElement(target, child as Element, opacity, painting)
  }

  if (layer) {
    const foot = box.top + el.clientTop + el.clientHeight
    const shade = target.createLinearGradient(0, foot - fade, 0, foot)
    shade.addColorStop(0, 'rgba(0, 0, 0, 1)')
    shade.addColorStop(1, 'rgba(0, 0, 0, 0)')
    target.globalAlpha = 1
    target.globalCompositeOperation = 'destination-in'
    target.fillStyle = shade
    // Above the fade the gradient is solid, so the rest of the block is kept.
    target.fillRect(box.left, box.top, box.width, box.height)
    ctx.save()
    ctx.resetTransform()
    ctx.globalAlpha = 1
    ctx.drawImage(layer, 0, 0)
    ctx.restore()
  }
  ctx.restore()
}

function paintText(
  ctx: CanvasRenderingContext2D,
  node: Text,
  style: CSSStyleDeclaration,
  alpha: number,
  { range, snap, carried, scale }: Painting,
) {
  const text = node.data
  const runs = textRuns(text)
  if (runs.length === 0) return
  const font = fontOf(style)
  ctx.font = font
  ctx.fillStyle = style.color
  ctx.globalAlpha = alpha
  ctx.direction = style.direction === 'rtl' ? 'rtl' : 'ltr'
  const spacing = style.letterSpacing === 'normal' ? '0px' : style.letterSpacing
  if (spacesLetters) ctx.letterSpacing = spacing

  for (const run of runs) {
    range.setStart(node, run.start)
    range.setEnd(node, run.end)
    const box = range.getBoundingClientRect()
    if (box.width <= 0 || box.height <= 0) continue
    const baseline = pixel(box.top + baselineOf(node, range, font), snap?.y, carried.y, scale)
    if (spacesLetters || spacing === '0px') {
      ctx.fillText(text.slice(run.start, run.end), pixel(box.left, snap?.x, carried.x, scale), baseline)
      continue
    }
    // No letter spacing on this canvas: set each letter where layout put it.
    for (let index = run.start; index < run.end; index++) {
      range.setStart(node, index)
      range.setEnd(node, index + 1)
      ctx.fillText(text[index], pixel(range.getBoundingClientRect().left, snap?.x, carried.x, scale), baseline)
    }
  }
}
