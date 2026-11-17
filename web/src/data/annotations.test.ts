import { describe, expect, it } from 'vitest'
import { annotationFor, parseAnnotations, withAnnotation } from './annotations'

describe('annotations', () => {
  it('stores, reads and deletes one verse note', () => {
    let map = withAnnotation({}, 2, 255, 'The Throne verse')
    expect(annotationFor(map, 2, 255)).toBe('The Throne verse')
    map = withAnnotation(map, 2, 255, '   ')
    expect(annotationFor(map, 2, 255)).toBeNull()
  })

  it('drops malformed stored entries', () => {
    const raw = JSON.stringify({ 'reader:1:1': 'Opening', bogus: 'x', 'reader:3:4': 7, 'reader:5:6': '  ' })
    expect(parseAnnotations(raw)).toEqual({ 'reader:1:1': 'Opening' })
    expect(parseAnnotations('not json')).toEqual({})
    expect(parseAnnotations('[1]')).toEqual({})
  })
})
