/**
 * How a leaf moves through the air, as numbers the renderer bends a sheet by.
 *
 * Paper does not stretch, and a leaf bound at the spine bends about lines
 * parallel to it. So the sheet's shape is one curve seen from the head: the
 * angle its surface makes with the page it left, from the bound edge out to
 * the free one. [turn] is that angle at the bound edge and [curl] how much
 * further round the free edge has got. The free edge is lifted first and
 * laid down first; the sheet bows behind it and flattens last.
 */
export interface LeafPose {
  /** The bound edge's angle from the page it left: 0 to π. */
  turn: number
  /** How much further round the free edge is than the bound edge. */
  curl: number
  /**
   * How much more the foot of the leaf curls than its head, as a share of
   * [curl]: a leaf is turned by a corner, which leads the rest of its edge.
   */
  taper: number
  /** The eased share of the turn made, 0 to 1: a pile sinks by it (see PageTurnGl). */
  share: number
}

export interface TurnKind {
  /** One leaf with nowhere to land (phones): it lifts to edge-on, or comes down from there. */
  single: boolean
  /** The share of the book's block the turn carries; a pile is stiff. */
  wad: number
  /** A single leaf coming back down, from edge-on to flat. */
  reverse: boolean
}

/** The most a lone leaf curls over its width. */
export const LEAF_CURL = (52 * Math.PI) / 180
/** A single leaf has no far page to be laid on, and stays bowed. */
export const SINGLE_CURL = (48 * Math.PI) / 180
/** Just past edge-on: a single leaf is out of sight there. */
export const SINGLE_TURN = (96 * Math.PI) / 180
/** The corner's lead over the rest of the free edge, at its most. */
export const LEAF_TAPER = 0.22
/** A whole block bends this much less than a leaf. */
const WAD_STIFFNESS = 0.86

/** The distance the eye stands from the page, in CSS pixels. */
export const SPREAD_PERSPECTIVE = 2600
export const SINGLE_PERSPECTIVE = 1700

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value))
}

/** CSS `cubic-bezier(x1, y1, x2, y2)` at [x]. */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number, x: number): number {
  if (x <= 0) return 0
  if (x >= 1) return 1
  const curve = (a: number, b: number, t: number) => {
    const u = 1 - t
    return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
  }
  // The curve's x is monotonic for control points in 0..1: bisect for t.
  let low = 0
  let high = 1
  let t = x
  for (let step = 0; step < 24; step++) {
    const at = curve(x1, x2, t)
    if (Math.abs(at - x) < 1e-6) break
    if (at < x) low = t
    else high = t
    t = (low + high) / 2
  }
  return curve(y1, y2, t)
}

const easeInOut = (x: number) => cubicBezier(0.42, 0, 0.58, 1, x)

/** The bound edge follows the hand: it starts late and settles last. */
const turnEase = (x: number) => cubicBezier(0.5, 0.02, 0.28, 1, x)

/**
 * The curl over a turn that lands: quick into it as the edge is picked up,
 * slow out of it as the sheet is laid flat.
 */
function landingCurl(t: number): number {
  if (t <= 0.34) return easeInOut(t / 0.34)
  if (t >= 0.9) return 0
  return 1 - easeInOut((t - 0.34) / 0.56)
}

/** The curl of a leaf that leaves: it stays bowed until it is gone. */
function liftingCurl(t: number): number {
  return easeInOut(clamp01(t / 0.45))
}

/** The leaf at share [t] of its turn's time. */
export function leafPose(t: number, kind: TurnKind): LeafPose {
  const time = clamp01(t)
  const share = turnEase(time)
  if (kind.single) {
    // Coming down, the leaf is in sight from the start and settles slowly,
    // as it does going up: the bound edge keeps its own easing and only the
    // bow runs backwards, flattening as the leaf is laid.
    const bow = liftingCurl(kind.reverse ? 1 - time : time)
    const turn = SINGLE_TURN * (kind.reverse ? 1 - share : share)
    return { turn, curl: SINGLE_CURL * bow, taper: LEAF_TAPER * bow, share }
  }
  const turn = Math.PI * share
  const stiffness = 1 - WAD_STIFFNESS * clamp01(kind.wad)
  const bow = landingCurl(time)
  // No part of the sheet goes through the page it lands on: the free edge
  // touches down and the bow behind it flattens out.
  const curl = Math.min(LEAF_CURL * stiffness * bow, Math.PI - turn)
  return { turn, curl, taper: LEAF_TAPER * stiffness * bow, share }
}

/**
 * A point of the bent sheet, for a leaf one unit wide lying along +x from
 * its bound edge: [u] is the share of the width from that edge. Returns how
 * far along the page it now stands and how far above it. The same curve the
 * vertex shader bends the mesh by (PageTurnGl).
 */
export function sheetPoint(u: number, turn: number, curl: number): { x: number; z: number } {
  const half = 0.5 * curl * u
  const sinc = Math.abs(half) < 1e-4 ? 1 : Math.sin(half) / half
  return { x: u * Math.cos(turn + half) * sinc, z: u * Math.sin(turn + half) * sinc }
}
