import { memo, useLayoutEffect, useMemo, useRef } from 'react'
import type { Surah } from '../../data/models'
import { chapterOrnamentSeed, generateChapterOrnament } from '../theme/ornamentGenerator'
import { pathD } from '../theme/ornamentSvg'

/** The Android cartouche: tooled wings, two shamsas, and open paper around
 * the name. Plain SVG paths also survive the page-turn canvas capture.
 */
export const MushafChapterBand = memo(function MushafChapterBand({ surah, latin = false, fit = 1 }: {
  surah: Surah
  latin?: boolean
  /** Remeasure inherited type when a spread's hand changes at the same size. */
  fit?: number
}) {
  const root = useRef<HTMLDivElement>(null)
  const title = useRef<HTMLSpanElement>(null)
  const baseline = useRef<HTMLElement>(null)
  const drawing = useRef<SVGSVGElement>(null)
  const ornament = useMemo(() => generateChapterOrnament(chapterOrnamentSeed(surah.id, surah.ayahCount)), [surah.id, surah.ayahCount])
  useLayoutEffect(() => {
    const band = root.current
    const name = title.current
    const probe = baseline.current
    const svg = drawing.current
    if (!band || !name || !probe || !svg) return
    let disposed = false
    const measure = () => {
      if (disposed) return
      const w = band.clientWidth
      const h = band.clientHeight
      const style = getComputedStyle(band)
      const type = parseFloat(style.fontSize) * (latin ? 0.95 : 1.35)
      const ctx = document.createElement('canvas').getContext('2d')!
      ctx.font = `${type}px ${getComputedStyle(name).fontFamily}`
      const natural = ctx.measureText(name.textContent ?? '').width
      const fitted = type * Math.min(1, Math.max(h, w - 3.3 * h) / Math.max(1, natural))
      ctx.font = `${fitted}px ${getComputedStyle(name).fontFamily}`
      const head = ctx.measureText(latin ? 'H' : 'ا').actualBoundingBoxAscent
      const tail = Math.max(...Array.from(latin ? 'gjpqy' : 'منقعجيى', letter => ctx.measureText(letter).actualBoundingBoxDescent))
      const drop = Math.max(0, Math.min(head / 2, h * 0.62 - tail))
      name.style.fontSize = `${fitted}px`
      const offset = probe.getBoundingClientRect().top - name.getBoundingClientRect().top
      name.style.top = `${h / 2 + drop - offset}px`
      // A turning leaf captures its children in its own layout effect.
      // Publish the complete drawing now, before that parent takes its picture.
      svg.setAttribute('viewBox', `0 0 ${Math.max(1, w)} ${Math.max(1, h)}`)
      svg.innerHTML = cartoucheDrawing(ornament, w, h, natural * fitted / type)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(band)
    void document.fonts?.ready.then(measure)
    return () => { disposed = true; observer.disconnect() }
  }, [surah, latin, fit])
  return (
    <div className="mushaf-chapter-band" data-chapter={surah.id} ref={root}>
      <svg ref={drawing} aria-hidden="true" />
      <span className="mushaf-chapter-name" lang={latin ? 'en' : 'ar'} dir={latin ? 'ltr' : 'rtl'} ref={title}>
        {latin ? surah.nameTransliteration : surah.nameArabic}<i className="mushaf-chapter-baseline" ref={baseline} />
      </span>
    </div>
  )
})

/** Static paths only: the page-picture painter draws these without decoding an image. */
function cartoucheDrawing(ornament: ReturnType<typeof generateChapterOrnament>, w: number, h: number, advance: number): string {
  const half = Math.min(Math.max(advance + 1.1 * h, 2.6 * h), Math.max(2.6 * h, w - 2.2 * h)) / 2
  const x0 = w / 2 - half
  const x1 = w / 2 + half
  const tip = h * 0.32
  const sides = `M ${x0} 0 Q ${x0 - 0.2 * tip} ${0.275 * h} ${x0 - tip} ${h / 2} Q ${x0 - 0.2 * tip} ${0.725 * h} ${x0} ${h}
    M ${x1} ${h} Q ${x1 + 0.2 * tip} ${0.725 * h} ${x1 + tip} ${h / 2} Q ${x1 + 0.2 * tip} ${0.275 * h} ${x1} 0`
  const window = `M ${x0} ${-h} L ${x0} 0 Q ${x0 - 0.2 * tip} ${0.275 * h} ${x0 - tip} ${h / 2} Q ${x0 - 0.2 * tip} ${0.725 * h} ${x0} ${h}
    L ${x0} ${2 * h} L ${x1} ${2 * h} L ${x1} ${h} Q ${x1 + 0.2 * tip} ${0.725 * h} ${x1 + tip} ${h / 2} Q ${x1 + 0.2 * tip} ${0.275 * h} ${x1} 0 L ${x1} ${-h} Z`
  const weave = ornament.field.strokes.map(stroke => pathD(stroke, 1)).join(' ')
  const cells = []
  if (h > 0) {
    const unit = h * 0.8 / ornament.field.cellH
    const step = ornament.field.cellW * unit
    for (let x = -step; x < w + step; x += step) {
      cells.push(`<path d="${weave}" transform="translate(${x} 0) scale(${unit})" stroke-width="${0.4 / unit}"/>`)
    }
  }
  const rosette = ornament.rosette.strokes.map(stroke => `<path d="${pathD(stroke, 1)}"/>`).join('')
  const shamsas = [h * 0.14, w - h * 0.74].map(x =>
    `<g transform="translate(${x} ${h * 0.2}) scale(${h * 0.6})" fill="none" stroke="currentColor" stroke-width="${h > 0 ? 0.7 / (h * 0.6) : 0}" opacity="0.48">${rosette}</g>`).join('')
  return `<g class="mushaf-chapter-ground" fill="none" stroke="currentColor">${cells.join('')}</g>
    <rect x="0.6" y="0.6" width="${Math.max(0, w - 1.2)}" height="${Math.max(0, h - 1.2)}" rx="3" fill="none" stroke="currentColor" stroke-width="1.2" opacity="0.5"/>
    <rect x="3" y="3" width="${Math.max(0, w - 6)}" height="${Math.max(0, h - 6)}" rx="3" fill="none" stroke="currentColor" stroke-width="0.8" opacity="0.28"/>
    <path d="${window}" fill="var(--paper)"/>
    <path d="${sides}" fill="none" stroke="currentColor" stroke-width="0.8" opacity="0.5"/>
    ${shamsas}`
}
