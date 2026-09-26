import { shareFooterCopy, type ShareVerse } from './gather'

const WIDTH = 1080
const PAD_X = 72
const PAD_TOP = 88
const PAD_BETWEEN = 36

function wrap(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const words = text.trim().split(/\s+/).filter(Boolean)
  const lines: string[] = []
  let line = ''
  for (const word of words) {
    const next = line ? `${line} ${word}` : word
    if (line && ctx.measureText(next).width > maxWidth) {
      lines.push(line)
      line = word
    } else {
      line = next
    }
  }
  if (line) lines.push(line)
  return lines.length > 0 ? lines : ['']
}

function cssColor(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/**
 * One paper card: each gathered verse, then a gold citation.
 * Android draws this as stitched strips; the browser can hold one canvas.
 */
export async function renderShareCard(verses: readonly ShareVerse[]): Promise<Blob> {
  await document.fonts.load("28px 'Hafs Uthmanic'")
  await document.fonts.load("16px 'EB Garamond'")
  const measure = document.createElement('canvas').getContext('2d')
  if (!measure) throw new Error('No canvas')
  const maxWidth = WIDTH - PAD_X * 2
  measure.font = "28px 'Hafs Uthmanic', serif"
  const arabicLines = verses.map((verse) => wrap(measure, verse.arabic, maxWidth))
  measure.font = "16px 'EB Garamond', serif"
  const englishLines = verses.map((verse) =>
    verse.translation.trim() ? wrap(measure, verse.translation, maxWidth) : [],
  )
  const footer = shareFooterCopy(
    verses.map((verse) => ({
      surahId: verse.surahId,
      ayah: verse.ayah,
      surahName: verse.surahNameTransliteration,
    })),
  )
  let height = PAD_TOP
  verses.forEach((_, index) => {
    height += arabicLines[index]!.length * 46
    if (englishLines[index]!.length > 0) height += 12 + englishLines[index]!.length * 24
    height += index === verses.length - 1 ? 0 : PAD_BETWEEN
  })
  height += 28 + 8 + 36 + 22 + 72

  const canvas = document.createElement('canvas')
  canvas.width = WIDTH
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('No canvas')
  const paper = cssColor('--paper', '#faf3e8')
  const ink = cssColor('--ink', '#1c1b18')
  const gold = cssColor('--gold', '#a8831d')
  ctx.fillStyle = paper
  ctx.fillRect(0, 0, WIDTH, height)
  ctx.textAlign = 'center'
  ctx.direction = 'rtl'
  let y = PAD_TOP
  verses.forEach((_, index) => {
    ctx.fillStyle = ink
    ctx.font = "28px 'Hafs Uthmanic', serif"
    for (const line of arabicLines[index]!) {
      y += 46
      ctx.fillText(line, WIDTH / 2, y - 12)
    }
    if (englishLines[index]!.length > 0) {
      y += 12
      ctx.direction = 'ltr'
      ctx.font = "16px 'EB Garamond', serif"
      ctx.fillStyle = ink
      ctx.globalAlpha = 0.74
      for (const line of englishLines[index]!) {
        y += 24
        ctx.fillText(line, WIDTH / 2, y - 6)
      }
      ctx.globalAlpha = 1
      ctx.direction = 'rtl'
    }
    if (index !== verses.length - 1) y += PAD_BETWEEN
  })
  y += 28
  ctx.strokeStyle = gold
  ctx.globalAlpha = 0.45
  ctx.beginPath()
  ctx.moveTo(PAD_X, y)
  ctx.lineTo(WIDTH - PAD_X, y)
  ctx.stroke()
  ctx.globalAlpha = 1
  ctx.direction = 'ltr'
  ctx.fillStyle = gold
  ctx.font = "28px 'Cormorant Garamond', serif"
  y += 40
  ctx.fillText(footer.chapter, WIDTH / 2, y)
  ctx.globalAlpha = 0.72
  ctx.font = "16px 'EB Garamond', serif"
  y += 28
  ctx.fillText(footer.verses, WIDTH / 2, y)
  ctx.globalAlpha = 1

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('Could not draw the card')
  return blob
}
