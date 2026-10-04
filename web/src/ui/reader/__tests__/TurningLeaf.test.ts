import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { TurningLeaf } from '../TurningLeaf'

describe('airborne leaf', () => {
  it.each([false, true])('stages each of its pages once, out of reach (single=%s)', (single) => {
    let renders = 0
    const Face = () => { renders++; return createElement('button', null, 'word') }
    const markup = renderToStaticMarkup(createElement(TurningLeaf, {
      hinge: 'right', dir: 'on', single,
      face: createElement(Face), back: single ? undefined : createElement(Face),
      onEnd: () => {},
    }))
    expect(renders).toBe(single ? 1 : 2)
    expect(markup.match(/class="mushaf-flip-page"/g)).toHaveLength(single ? 1 : 2)
    expect(markup).toContain('data-face="front"')
    expect(markup.includes('data-face="back"')).toBe(!single)
    expect(markup).toContain('inert=""')
    expect(markup).toContain('aria-hidden="true"')
  })
})
