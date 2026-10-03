import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { TurningLeaf, TURNING_LEAF_STRIPS } from '../TurningLeaf'

describe('airborne leaf', () => {
  it.each([false, true])('renders each face once across its strips (single=%s)', (single) => {
    let renders = 0
    const Face = () => { renders++; return createElement('button', null, 'word') }
    const markup = renderToStaticMarkup(createElement(TurningLeaf, {
      hinge: 'right', dir: 'on', single,
      face: createElement(Face), back: single ? undefined : createElement(Face),
      onEnd: () => {},
    }))
    expect(renders).toBe(single ? 1 : 2)
    expect(markup.match(/class="mushaf-flip-strip"/g)).toHaveLength(TURNING_LEAF_STRIPS)
    expect(markup).toContain('inert=""')
    expect(markup).toContain('aria-hidden="true"')
  })
})
