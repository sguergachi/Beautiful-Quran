import { describe, expect, it } from 'vitest'
import {
  ayahFromTrackY,
  collapsedRailHitHeightPx,
  collapsedStackSpanPx,
  dialDeltaFromPointerDy,
  dialFromTickY,
  dialFromTrackY,
  dialOnCollapsedActivate,
  focusRadiusForHeight,
  isMajorAyah,
  railCollapsedBarRect,
  railExpandedLayout,
  railTickBarRect,
  railTickLabelX,
  rubberBandDialPosition,
  symbolicAyahBarCount,
  tickFocus,
  tickLength,
  trackYFromDial,
} from '../ayahRailMath'

describe('symbolicAyahBarCount', () => {
  it('clamps short and long surahs', () => {
    expect(symbolicAyahBarCount(1)).toBe(4)
    expect(symbolicAyahBarCount(7)).toBe(4)
    expect(symbolicAyahBarCount(286)).toBe(17)
    expect(symbolicAyahBarCount(10_000)).toBe(18)
  })
})

describe('collapsed stack hit height', () => {
  it('matches the drawn dash stack span (Android parity)', () => {
    // count=4 → spacing 8, step 9.5, span = 3*9.5 + 1.5 = 30
    expect(collapsedStackSpanPx(1)).toBeCloseTo(30, 5)
    expect(collapsedStackSpanPx(286)).toBeGreaterThan(30)
    expect(collapsedStackSpanPx(286)).toBeLessThan(120)
  })

  it('pads the stack and floors at 48px so short surahs stay tappable', () => {
    expect(collapsedRailHitHeightPx(1)).toBeCloseTo(54, 5)
    expect(collapsedRailHitHeightPx(1, 0)).toBe(48)
    expect(collapsedRailHitHeightPx(286)).toBeLessThan(200)
    expect(collapsedRailHitHeightPx(286)).toBeGreaterThan(collapsedRailHitHeightPx(1))
  })
})

describe('rubberBandDialPosition', () => {
  it('passes through in-range values', () => {
    expect(rubberBandDialPosition(12, 1, 20)).toBe(12)
  })

  it('softens overscroll past either end', () => {
    expect(rubberBandDialPosition(0, 1, 20)).toBeCloseTo(1 - 0.32, 5)
    expect(rubberBandDialPosition(22, 1, 20)).toBeCloseTo(20 + 2 * 0.32, 5)
  })
})

describe('track ↔ dial mapping', () => {
  const h = 1000
  const count = 101

  it('maps the top inset to ayah 1 and the bottom to the last ayah', () => {
    expect(ayahFromTrackY(h * 0.08, h, count)).toBe(1)
    expect(ayahFromTrackY(h * (1 - 0.12), h, count)).toBe(count)
  })

  it('round-trips dial through track Y', () => {
    for (const dial of [1, 26, 51, 76, 101]) {
      const y = trackYFromDial(dial, h, count)
      expect(dialFromTrackY(y, h, count)).toBeCloseTo(dial, 5)
      expect(ayahFromTrackY(y, h, count)).toBe(dial)
    }
  })

  it('returns the sole ayah for single-ayah surahs', () => {
    expect(ayahFromTrackY(500, h, 1)).toBe(1)
    expect(dialFromTrackY(100, h, 1)).toBe(1)
  })
})

describe('wheel scrub (tick spacing)', () => {
  const tick = 14

  it('maps one tick of pointer travel to one ayah (Android parity)', () => {
    expect(dialDeltaFromPointerDy(-tick, tick)).toBeCloseTo(1, 5)
    expect(dialDeltaFromPointerDy(tick, tick)).toBeCloseTo(-1, 5)
    expect(dialDeltaFromPointerDy(-tick * 2.5, tick)).toBeCloseTo(2.5, 5)
  })

  it('opening the centered collapsed stack keeps the reading ayah', () => {
    // The stack lives at the rail midline. Absolute Y mapping of that press
    // is the middle of Al-Baqarah, not the verse being read.
    const height = 800
    const ayahCount = 286
    expect(Math.round(dialFromTrackY(height / 2, height, ayahCount))).toBeGreaterThan(100)
    expect(dialOnCollapsedActivate(7, ayahCount)).toBe(7)
    expect(dialOnCollapsedActivate(286, ayahCount)).toBe(286)
    expect(dialOnCollapsedActivate(1, 1)).toBe(1)
  })

  it('selects the visible tick under the pointer, not an absolute track fraction', () => {
    // Magnification wheel: dial at 50, ticks 14px apart. Pointer on the tick
    // drawn for ayah 55 (5 ticks below the focal anchor) must commit 55 —
    // absolute track mapping on a long surah would skip far past it.
    const dial = 50
    const anchorY = 400
    const yOnAyah55 = anchorY + (55 - dial) * tick
    expect(Math.round(dialFromTickY(yOnAyah55, dial, anchorY, tick))).toBe(55)

    // Absolute track mapping (the old bug) would not land on 55 for Al-Baqarah.
    const ayahCount = 286
    const height = 800
    const absolute = dialFromTrackY(yOnAyah55, height, ayahCount)
    expect(Math.round(absolute)).not.toBe(55)
  })
})

describe('tickFocus / tickLength', () => {
  it('peaks at the focal tick and falls off', () => {
    expect(tickFocus(0, 8)).toBe(1)
    expect(tickFocus(4, 8)).toBe(0.5)
    expect(tickFocus(8, 8)).toBe(0)
    expect(tickFocus(20, 8)).toBe(0)
  })

  it('grows widthwise with focus; major ticks get a bonus', () => {
    expect(tickLength(1, false, 8, 44, 6)).toBe(44)
    expect(tickLength(0, false, 8, 44, 6)).toBe(8)
    expect(tickLength(1, true, 8, 44, 6)).toBe(50)
    expect(tickLength(0.5, false, 8, 44, 6)).toBe(8 + 36 * 0.25)
  })
})

describe('isMajorAyah / focusRadiusForHeight', () => {
  it('marks ends and multiples of five', () => {
    expect(isMajorAyah(1, 40)).toBe(true)
    expect(isMajorAyah(40, 40)).toBe(true)
    expect(isMajorAyah(15, 40)).toBe(true)
    expect(isMajorAyah(14, 40)).toBe(false)
  })

  it('keeps a usable focus window on short rails', () => {
    expect(focusRadiusForHeight(200, 14)).toBeGreaterThanOrEqual(8)
    expect(focusRadiusForHeight(800, 14)).toBeGreaterThan(8)
  })
})

describe('railExpandedLayout', () => {
  const edgePad = 2

  function labelFits(
    railWidth: number,
    side: 'left' | 'right',
    labelWidth: number,
    growFrom: 'center' | 'edge',
  ): boolean {
    const layout = railExpandedLayout(railWidth, side, labelWidth, growFrom)
    const peak = layout.maxBarLen + layout.majorBonus
    const labelX = railTickLabelX(layout, side, peak)
    if (side === 'left') {
      return labelX + labelWidth <= railWidth - edgePad + 0.01
    }
    return labelX - labelWidth >= edgePad - 0.01
  }

  it('keeps the midline centered when the rail is wide enough', () => {
    const layout = railExpandedLayout(120, 'left', 20, 'center')
    expect(layout.growFrom).toBe('center')
    expect(layout.originX).toBeCloseTo(60, 5)
    expect(layout.maxBarLen).toBe(44)
    expect(layout.majorBonus).toBe(6)
  })

  it('biases toward the outer edge on a narrow centered rail', () => {
    const left = railExpandedLayout(72, 'left', 20, 'center')
    expect(left.originX).toBeLessThan(36)
    expect(labelFits(72, 'left', 20, 'center')).toBe(true)

    const right = railExpandedLayout(72, 'right', 20, 'center')
    expect(right.originX).toBeGreaterThan(36)
    expect(labelFits(72, 'right', 20, 'center')).toBe(true)
  })

  it('anchors flush to the screen edge in edge mode (Android parity)', () => {
    const left = railExpandedLayout(88, 'left', 22, 'edge')
    expect(left.growFrom).toBe('edge')
    expect(left.originX).toBe(0)
    expect(labelFits(88, 'left', 22, 'edge')).toBe(true)

    const right = railExpandedLayout(88, 'right', 22, 'edge')
    expect(right.originX).toBe(88)
    expect(labelFits(88, 'right', 22, 'edge')).toBe(true)
  })

  it('hides the outer rounded cap behind the edge in edge mode', () => {
    const left = railExpandedLayout(88, 'left', 20, 'edge')
    const bar = railTickBarRect(left, 'left', 44, 3)
    expect(bar.x).toBeLessThan(0)
    expect(bar.x + bar.width).toBeCloseTo(44, 5)

    const right = railExpandedLayout(88, 'right', 20, 'edge')
    const rightBar = railTickBarRect(right, 'right', 44, 3)
    expect(rightBar.x + rightBar.width).toBeGreaterThan(88)
    expect(rightBar.x).toBeCloseTo(88 - 44, 5)
  })

  it('shortens bars only when even edge-anchoring cannot fit the label', () => {
    const layout = railExpandedLayout(40, 'left', 14, 'edge')
    expect(layout.maxBarLen + layout.majorBonus).toBeLessThan(50)
    expect(labelFits(40, 'left', 14, 'edge')).toBe(true)
  })
})

describe('railCollapsedBarRect', () => {
  it('centers collapsed dashes on desktop and shrinks them on exit', () => {
    const full = railCollapsedBarRect(80, 'left', 'center', 10, 1.5, 1)
    expect(full.x).toBeCloseTo(35, 5)
    expect(full.width).toBe(10)

    const half = railCollapsedBarRect(80, 'left', 'center', 10, 1.5, 0.5)
    expect(half.width).toBe(5)
    expect(half.x).toBeCloseTo(37.5, 5)
  })

  it('flushes collapsed dashes to the screen edge on mobile', () => {
    const left = railCollapsedBarRect(80, 'left', 'edge', 10, 1.5, 1)
    expect(left.x).toBeLessThan(0)
    expect(left.x + left.width).toBeCloseTo(10, 5)

    const right = railCollapsedBarRect(80, 'right', 'edge', 10, 1.5, 1)
    expect(right.x).toBeCloseTo(70, 5)
    expect(right.x + right.width).toBeGreaterThan(80)
  })

  it('slips edge-flush dashes off-screen as they exit', () => {
    const open = railCollapsedBarRect(80, 'left', 'edge', 10, 1.5, 1)
    const exiting = railCollapsedBarRect(80, 'left', 'edge', 10, 1.5, 0)
    expect(exiting.x).toBeLessThan(open.x)
  })
})
