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
 * It draws what a page is made of and no more: text, backgrounds (flat or a
 * linear gradient, square or rounded: the paper a waiting word lies under,
 * a search field, the dissolve under a pinned bar), opacity, clipping, 2D
 * transforms, a fade at the foot of a scrolling block, a field's text, and
 * the line work of an inline SVG (an icon, the title page's medallion).
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

/** One layer of `linear-gradient(...)` as computed: where it runs, and its stops. */
export interface LinearGradient {
  /** Clockwise from straight up, in degrees: 180 runs top to bottom. */
  angle: number
  stops: { colour: [number, number, number, number]; at: number | null }[]
}

function splitTopLevel(text: string): string[] {
  const parts: string[] = []
  let depth = 0
  let from = 0
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (char === '(') depth++
    else if (char === ')') depth--
    else if (char === ',' && depth === 0) {
      parts.push(text.slice(from, index).trim())
      from = index + 1
    }
  }
  parts.push(text.slice(from).trim())
  return parts
}

const SIDES: Record<string, number> = { 'to top': 0, 'to right': 90, 'to bottom': 180, 'to left': 270 }

/** The first `linear-gradient` of a computed `background-image`; null for anything else. */
export function parseLinearGradient(image: string): LinearGradient | null {
  if (!image.startsWith('linear-gradient(')) return null
  let depth = 0
  let end = -1
  for (let index = 'linear-gradient'.length; index < image.length; index++) {
    if (image[index] === '(') depth++
    else if (image[index] === ')' && --depth === 0) { end = index; break }
  }
  if (end < 0) return null
  const parts = splitTopLevel(image.slice('linear-gradient('.length, end))
  let angle = 180
  if (parts[0] in SIDES) angle = SIDES[parts.shift()!]
  else if (/^-?[\d.]+deg$/.test(parts[0])) angle = parseFloat(parts.shift()!)
  const stops = parts.map((part) => {
    const match = /^rgba?\(([^)]+)\)\s*(?:(-?[\d.]+)%)?$/.exec(part)
    if (!match) return null
    const channels = match[1].split(/[,/\s]+/).filter(Boolean).map(Number)
    return {
      colour: [channels[0], channels[1], channels[2], channels[3] ?? 1] as [number, number, number, number],
      at: match[2] == null ? null : Number(match[2]) / 100,
    }
  })
  if (stops.length < 2 || stops.some((stop) => stop == null)) return null
  return { angle, stops: stops as LinearGradient['stops'] }
}

/**
 * A clear stop takes its neighbour's colour. CSS fades toward `transparent`
 * without passing through black; a canvas gradient does not, and a dissolve
 * of paper came out with a grey band in it.
 */
export function clearStops(stops: LinearGradient['stops']): LinearGradient['stops'] {
  return stops.map((stop, index) => {
    if (stop.colour[3] > 0) return stop
    const near = stops[index - 1]?.colour[3] ? stops[index - 1] : stops.slice(index + 1).find((next) => next.colour[3] > 0) ?? stops[index - 1]
    return near ? { ...stop, colour: [near.colour[0], near.colour[1], near.colour[2], 0] } : stop
  })
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
  /** The page being pictured: nothing outside it is drawn. */
  frame: Frame
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
  source: Element,
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
  for (const el of source.querySelectorAll('*')) {
    const style = getComputedStyle(el)
    const matrix = parseMatrix(style.transform)
    if (!matrix) continue
    const [originX, originY] = style.transformOrigin.split(' ').map(parseFloat)
    placed.set(el, { matrix, originX: originX || 0, originY: originY || 0 })
  }
  source.setAttribute('data-flat', '')
  try {
    const painting: Painting = { placed, range: document.createRange(), scale, frame, snap: drift, carried: { x: 0, y: 0 } }
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
  if (el instanceof SVGSVGElement) {
    paintSvg(ctx, el, opacity)
    return
  }
  const box = el.getBoundingClientRect()
  // A long list scrolled under a page has most of its rows off it.
  const page = painting.frame
  if (
    box.width > 0 && box.height > 0 && !painting.placed.has(el) && (
      box.bottom < page.top - REACH || box.top > page.top + page.height + REACH ||
      box.right < page.left - REACH || box.left > page.left + page.width + REACH
    )
  ) return

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
  // What scrolls is painted on a layer of its own, set where layout has it:
  // the fraction a layer above dropped does not reach it (so measured: a
  // scrolled list stood a pixel low with it).
  if (painting.snap && /auto|scroll/.test(style.overflowX + style.overflowY)) {
    painting = { ...painting, snap: { x: 0, y: 0 } }
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

  paintFill(target, style, left, top, right - left, bottom - top, opacity)
  const positioned = style.position !== 'static'
  if (positioned) paintPseudo(target, el, '::before', left, top, opacity)

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

  if (el instanceof HTMLInputElement) paintField(target, el, style, left, top, bottom - top, opacity)
  // What stands over its siblings (a bar pinned over the list it heads) is
  // drawn after them.
  const raised: { child: Element; z: number }[] = []
  for (const child of el.childNodes) {
    if (child.nodeType === Node.TEXT_NODE) paintText(target, child as Text, style, opacity, painting)
    else if (child.nodeType === Node.ELEMENT_NODE) {
      const look = getComputedStyle(child as Element)
      const z = look.position === 'static' ? 0 : Number(look.zIndex) || 0
      if (z > 0) raised.push({ child: child as Element, z })
      else paintElement(target, child as Element, opacity, painting)
    }
  }
  for (const { child } of raised.sort((a, b) => a.z - b.z)) paintElement(target, child, opacity, painting)
  if (positioned) paintPseudo(target, el, '::after', left, top, opacity)

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

/** How far outside the page a box may stand and still be drawn (ink overhangs its box). */
const REACH = 48

/** A box's background: its colour, then the first linear gradient over it, to its corners' radius. */
function paintFill(
  ctx: CanvasRenderingContext2D,
  style: CSSStyleDeclaration,
  left: number,
  top: number,
  width: number,
  height: number,
  alpha: number,
) {
  if (width <= 0 || height <= 0) return
  const colour = style.backgroundColor
  const flat = colour && colour !== 'transparent' && !/, 0\)$/.test(colour)
  const gradient = style.backgroundImage === 'none' ? null : parseLinearGradient(style.backgroundImage)
  if (!flat && !gradient) return
  const radius = (value: string, across: number) =>
    value.endsWith('%') ? (parseFloat(value) / 100) * across : parseFloat(value) || 0
  const corners = [
    radius(style.borderTopLeftRadius, width),
    radius(style.borderTopRightRadius, width),
    radius(style.borderBottomRightRadius, width),
    radius(style.borderBottomLeftRadius, width),
  ]
  const fill = () => {
    if (corners.some((corner) => corner > 0)) {
      ctx.beginPath()
      ctx.roundRect(left, top, width, height, corners)
      ctx.fill()
    } else {
      ctx.fillRect(left, top, width, height)
    }
  }
  ctx.globalAlpha = alpha
  if (flat) {
    ctx.fillStyle = colour
    fill()
  }
  if (gradient) {
    // The gradient line through the box's middle, long enough to reach its corners.
    const turn = (gradient.angle * Math.PI) / 180
    const dx = Math.sin(turn)
    const dy = -Math.cos(turn)
    const half = (Math.abs(width * dx) + Math.abs(height * dy)) / 2
    const cx = left + width / 2
    const cy = top + height / 2
    const paint = ctx.createLinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
    const stops = clearStops(gradient.stops)
    stops.forEach((stop, index) => {
      const at = stop.at ?? index / (stops.length - 1)
      paint.addColorStop(Math.min(1, Math.max(0, at)), `rgba(${stop.colour.join(', ')})`)
    })
    ctx.fillStyle = paint
    fill()
  }
}

/** An element's `::before` or `::after`, where it is a placed box with a background. */
function paintPseudo(
  ctx: CanvasRenderingContext2D,
  el: Element,
  which: '::before' | '::after',
  left: number,
  top: number,
  alpha: number,
) {
  const style = getComputedStyle(el, which)
  if (style.content === 'none' || style.display === 'none' || style.position !== 'absolute') return
  const [x, y, width, height] = [style.left, style.top, style.width, style.height].map(parseFloat)
  if (![x, y, width, height].every(Number.isFinite)) return
  const opacity = alpha * (Number(style.opacity) || 0)
  if (opacity <= 0.002) return
  paintFill(ctx, style, left + el.clientLeft + x, top + el.clientTop + y, width, height, opacity)
}

/** What a text field shows: what was typed into it, or its prompt. */
function paintField(
  ctx: CanvasRenderingContext2D,
  field: HTMLInputElement,
  style: CSSStyleDeclaration,
  left: number,
  top: number,
  height: number,
  alpha: number,
) {
  const text = field.value || field.placeholder
  if (!text || field.type === 'password') return
  ctx.font = fontOf(style)
  ctx.fillStyle = field.value ? style.color : getComputedStyle(field, '::placeholder').color
  ctx.globalAlpha = alpha * (field.value ? 1 : Number(getComputedStyle(field, '::placeholder').opacity) || 1)
  ctx.direction = style.direction === 'rtl' ? 'rtl' : 'ltr'
  const metrics = ctx.measureText(text)
  const baseline = top + (height + metrics.fontBoundingBoxAscent - metrics.fontBoundingBoxDescent) / 2
  ctx.fillText(text, left + field.clientLeft + (parseFloat(style.paddingLeft) || 0), baseline)
}

/**
 * Draws an inline SVG's shapes: paths, circles, ellipses, rects, lines and
 * polygons, filled and stroked in flat colour or a linear gradient, under
 * their own transforms. An image of the SVG would be exact, but an image
 * decodes on a later frame and a picture is wanted on this one.
 */
function paintSvg(ctx: CanvasRenderingContext2D, svg: SVGSVGElement, alpha: number) {
  const box = svg.getBoundingClientRect()
  const view = svg.viewBox.baseVal
  const width = view && view.width > 0 ? view.width : box.width
  const height = view && view.height > 0 ? view.height : box.height
  if (box.width <= 0 || box.height <= 0 || width <= 0 || height <= 0) return
  // The default fit: the whole drawing, at one scale, centred in its box.
  const fit = Math.min(box.width / width, box.height / height)
  ctx.save()
  ctx.translate(box.left + (box.width - width * fit) / 2, box.top + (box.height - height * fit) / 2)
  ctx.scale(fit, fit)
  ctx.translate(-(view?.x ?? 0), -(view?.y ?? 0))
  for (const child of svg.children) paintShape(ctx, child, alpha, svg)
  ctx.restore()
}

function svgPaint(
  ctx: CanvasRenderingContext2D,
  value: string,
  shape: SVGGraphicsElement,
  svg: SVGSVGElement,
): string | CanvasGradient | null {
  if (!value || value === 'none') return null
  const reference = /^url\(["']?#([^"')]+)["']?\)/.exec(value)
  if (!reference) return value
  const gradient = svg.querySelector(`#${CSS.escape(reference[1])}`)
  if (!(gradient instanceof SVGLinearGradientElement)) return null
  const length = (at: SVGAnimatedLength) => at.baseVal.valueInSpecifiedUnits / (at.baseVal.unitType === 2 ? 100 : 1)
  // Gradient ends are shares of the shape's own box unless it says otherwise.
  const own = gradient.gradientUnits.baseVal !== SVGUnitTypes.SVG_UNIT_TYPE_USERSPACEONUSE
  const bounds = own ? shape.getBBox() : null
  const x = (share: number) => (bounds ? bounds.x + share * bounds.width : share)
  const y = (share: number) => (bounds ? bounds.y + share * bounds.height : share)
  const fill = ctx.createLinearGradient(
    x(length(gradient.x1)), y(length(gradient.y1)), x(length(gradient.x2)), y(length(gradient.y2)),
  )
  for (const stop of gradient.querySelectorAll('stop')) {
    const look = getComputedStyle(stop)
    const colour = /^rgba?\(([^)]+)\)$/.exec(look.stopColor)
    const channels = colour ? colour[1].split(/[,/\s]+/).filter(Boolean).map(Number) : [0, 0, 0]
    const opacity = (Number(look.stopOpacity) || 0) * (channels[3] ?? 1)
    const offset = stop.offset.baseVal
    fill.addColorStop(Math.min(1, Math.max(0, offset)), `rgba(${channels[0]}, ${channels[1]}, ${channels[2]}, ${opacity})`)
  }
  return fill
}

function paintShape(ctx: CanvasRenderingContext2D, el: Element, alpha: number, svg: SVGSVGElement) {
  if (!(el instanceof SVGGraphicsElement)) return
  const style = getComputedStyle(el)
  if (style.display === 'none' || style.visibility === 'collapse') return
  const opacity = alpha * (Number(style.opacity) || 0)
  if (opacity <= 0.002) return
  ctx.save()
  const placed = el.transform.baseVal.consolidate()?.matrix
  if (placed) ctx.transform(placed.a, placed.b, placed.c, placed.d, placed.e, placed.f)
  if (el instanceof SVGGElement) {
    for (const child of el.children) paintShape(ctx, child, opacity, svg)
    ctx.restore()
    return
  }

  let path: Path2D | null = null
  if (el instanceof SVGPathElement) {
    path = new Path2D(el.getAttribute('d') ?? '')
  } else if (el instanceof SVGCircleElement) {
    path = new Path2D()
    path.arc(el.cx.baseVal.value, el.cy.baseVal.value, el.r.baseVal.value, 0, Math.PI * 2)
  } else if (el instanceof SVGEllipseElement) {
    path = new Path2D()
    path.ellipse(el.cx.baseVal.value, el.cy.baseVal.value, el.rx.baseVal.value, el.ry.baseVal.value, 0, 0, Math.PI * 2)
  } else if (el instanceof SVGRectElement) {
    path = new Path2D()
    path.roundRect(el.x.baseVal.value, el.y.baseVal.value, el.width.baseVal.value, el.height.baseVal.value, el.rx.baseVal.value)
  } else if (el instanceof SVGLineElement) {
    path = new Path2D()
    path.moveTo(el.x1.baseVal.value, el.y1.baseVal.value)
    path.lineTo(el.x2.baseVal.value, el.y2.baseVal.value)
  } else if (el instanceof SVGPolygonElement || el instanceof SVGPolylineElement) {
    path = new Path2D()
    const points = Array.from(el.points)
    points.forEach((point, index) => (index ? path!.lineTo(point.x, point.y) : path!.moveTo(point.x, point.y)))
    if (el instanceof SVGPolygonElement) path.closePath()
  }
  if (path) {
    const fill = svgPaint(ctx, style.fill, el, svg)
    if (fill) {
      ctx.globalAlpha = opacity * (Number(style.fillOpacity) || 0)
      ctx.fillStyle = fill
      ctx.fill(path, style.fillRule === 'evenodd' ? 'evenodd' : 'nonzero')
    }
    const stroke = svgPaint(ctx, style.stroke, el, svg)
    const weight = parseFloat(style.strokeWidth)
    if (stroke && weight > 0) {
      ctx.globalAlpha = opacity * (Number(style.strokeOpacity) || 0)
      ctx.strokeStyle = stroke
      ctx.lineWidth = weight
      ctx.lineCap = style.strokeLinecap as CanvasLineCap
      ctx.lineJoin = style.strokeLinejoin as CanvasLineJoin
      // A length-normalised dash is a line being drawn in; at rest it is whole.
      const dashes = el.hasAttribute('pathLength') || style.strokeDasharray === 'none'
        ? []
        : style.strokeDasharray.split(/[,\s]+/).map(parseFloat).filter((dash) => dash >= 0)
      ctx.setLineDash(dashes)
      ctx.stroke(path)
    }
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
