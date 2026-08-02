/**
 * Progressive royal-green vellum for contextual guides.
 *
 * Prefers a WebGL port of Android's `ShaderInkSpillField` (AGSL). Falls back to
 * the Android 11–12 linear gradient when WebGL is unavailable.
 */
import { useEffect, useRef } from 'react'
import type { Point } from './contextualTipPlacement'
import { normalize } from './contextualTipPlacement'
import { VELLUM_FRAG, VELLUM_TUNING, VELLUM_VERT } from './vellumShader'

type Props = {
  progress: number
  spotlight: Point
  body: Point
  action: Point
  color: string
  width: number
  height: number
}

function parseRgb(color: string): { r: number; g: number; b: number } {
  const hex = color.trim()
  if (hex.startsWith('#') && hex.length === 7) {
    return {
      r: parseInt(hex.slice(1, 3), 16) / 255,
      g: parseInt(hex.slice(3, 5), 16) / 255,
      b: parseInt(hex.slice(5, 7), 16) / 255,
    }
  }
  const m = hex.match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i)
  if (m) {
    return {
      r: Number(m[1]) / 255,
      g: Number(m[2]) / 255,
      b: Number(m[3]) / 255,
    }
  }
  return { r: 6 / 255, g: 44 / 255, b: 36 / 255 }
}

type GlProgram = {
  gl: WebGLRenderingContext
  program: WebGLProgram
  locs: Record<string, WebGLUniformLocation | null>
}

function compile(
  gl: WebGLRenderingContext,
  type: number,
  source: string,
): WebGLShader | null {
  const shader = gl.createShader(type)
  if (!shader) return null
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    gl.deleteShader(shader)
    return null
  }
  return shader
}

function createProgram(canvas: HTMLCanvasElement): GlProgram | null {
  const gl = canvas.getContext('webgl', {
    alpha: true,
    premultipliedAlpha: true,
    antialias: false,
    depth: false,
    stencil: false,
  })
  if (!gl) return null
  const vs = compile(gl, gl.VERTEX_SHADER, VELLUM_VERT)
  const fs = compile(gl, gl.FRAGMENT_SHADER, VELLUM_FRAG)
  if (!vs || !fs) return null
  const program = gl.createProgram()
  if (!program) return null
  gl.attachShader(program, vs)
  gl.attachShader(program, fs)
  gl.linkProgram(program)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    gl.deleteProgram(program)
    return null
  }
  const buf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, buf)
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
    gl.STATIC_DRAW,
  )
  const aPos = gl.getAttribLocation(program, 'a_pos')
  gl.enableVertexAttribArray(aPos)
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0)
  gl.useProgram(program)
  gl.enable(gl.BLEND)
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
  const names = [
    'u_resolution',
    'u_spotlight',
    'u_bodyCenter',
    'u_actionCenter',
    'u_progress',
    'u_flow',
    'u_bodyEdge',
    'u_featherWidth',
    'u_fadeSoftness',
    'u_blurRadius',
    'u_blurStrength',
    'u_vellumGrain',
    'u_verticalTaper',
    'u_inkColor',
  ]
  const locs: Record<string, WebGLUniformLocation | null> = {}
  for (const name of names) locs[name] = gl.getUniformLocation(program, name)
  return { gl, program, locs }
}

function drawGradientFallback(
  canvas: HTMLCanvasElement,
  progress: number,
  spotlight: Point,
  body: Point,
  color: string,
  cssW: number,
  cssH: number,
) {
  const dpr = window.devicePixelRatio || 1
  if (
    canvas.width !== Math.round(cssW * dpr) ||
    canvas.height !== Math.round(cssH * dpr)
  ) {
    canvas.width = Math.round(cssW * dpr)
    canvas.height = Math.round(cssH * dpr)
    canvas.style.width = `${cssW}px`
    canvas.style.height = `${cssH}px`
  }
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, cssW, cssH)
  if (progress <= 0.001) return
  const direction = normalize({
    x: spotlight.x - body.x,
    y: spotlight.y - body.y,
  })
  const reachFraction = VELLUM_TUNING.bodyEdge + VELLUM_TUNING.featherWidth
  const span = Math.abs(direction.x) * cssW + Math.abs(direction.y) * cssH
  const reach = span * reachFraction * progress
  const bodyStop = Math.min(1, Math.max(0, VELLUM_TUNING.bodyEdge / reachFraction))
  const rgb = parseRgb(color)
  const r = Math.round(rgb.r * 255)
  const g = Math.round(rgb.g * 255)
  const b = Math.round(rgb.b * 255)
  const fieldCenter = { x: cssW / 2, y: cssH / 2 }
  const start = {
    x: fieldCenter.x - direction.x * (span / 2),
    y: fieldCenter.y - direction.y * (span / 2),
  }
  const end = {
    x: start.x + direction.x * reach,
    y: start.y + direction.y * reach,
  }
  const gradient = ctx.createLinearGradient(start.x, start.y, end.x, end.y)
  gradient.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${progress})`)
  gradient.addColorStop(bodyStop, `rgba(${r}, ${g}, ${b}, ${progress})`)
  gradient.addColorStop(
    bodyStop + (1 - bodyStop) * 0.42,
    `rgba(${r}, ${g}, ${b}, ${0.72 * progress})`,
  )
  gradient.addColorStop(
    bodyStop + (1 - bodyStop) * 0.76,
    `rgba(${r}, ${g}, ${b}, ${0.28 * progress})`,
  )
  gradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, cssW, cssH)
}

export function InkSpillField({
  progress,
  spotlight,
  body,
  action,
  color,
  width,
  height,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const glRef = useRef<GlProgram | null>(null)
  const triedGl = useRef(false)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || width <= 0 || height <= 0) return

    if (!triedGl.current) {
      triedGl.current = true
      glRef.current = createProgram(canvas)
    }

    const prog = glRef.current
    if (!prog) {
      drawGradientFallback(canvas, progress, spotlight, body, color, width, height)
      return
    }

    const { gl, locs } = prog
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const pw = Math.max(1, Math.round(width * dpr))
    const ph = Math.max(1, Math.round(height * dpr))
    if (canvas.width !== pw || canvas.height !== ph) {
      canvas.width = pw
      canvas.height = ph
      canvas.style.width = `${width}px`
      canvas.style.height = `${height}px`
    }
    gl.viewport(0, 0, pw, ph)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    if (progress <= 0.001) return

    const flow = normalize({
      x: spotlight.x - body.x,
      y: spotlight.y - body.y,
    })
    const rgb = parseRgb(color)
    // Uniforms are in CSS pixels; fragCoord is scaled via resolution.
    gl.uniform2f(locs.u_resolution!, pw, ph)
    gl.uniform2f(locs.u_spotlight!, spotlight.x * dpr, spotlight.y * dpr)
    gl.uniform2f(locs.u_bodyCenter!, body.x * dpr, body.y * dpr)
    gl.uniform2f(locs.u_actionCenter!, action.x * dpr, action.y * dpr)
    gl.uniform1f(locs.u_progress!, progress)
    gl.uniform2f(locs.u_flow!, flow.x, flow.y)
    gl.uniform1f(locs.u_bodyEdge!, VELLUM_TUNING.bodyEdge)
    gl.uniform1f(locs.u_featherWidth!, VELLUM_TUNING.featherWidth)
    gl.uniform1f(locs.u_fadeSoftness!, VELLUM_TUNING.fadeSoftness)
    gl.uniform1f(locs.u_blurRadius!, VELLUM_TUNING.blurRadiusPx * dpr)
    gl.uniform1f(locs.u_blurStrength!, VELLUM_TUNING.blurStrength)
    gl.uniform1f(locs.u_vellumGrain!, VELLUM_TUNING.vellumGrain)
    gl.uniform1f(locs.u_verticalTaper!, VELLUM_TUNING.verticalTaper)
    gl.uniform4f(locs.u_inkColor!, rgb.r, rgb.g, rgb.b, 1)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }, [
    progress,
    spotlight.x,
    spotlight.y,
    body.x,
    body.y,
    action.x,
    action.y,
    color,
    width,
    height,
  ])

  return (
    <canvas
      ref={canvasRef}
      className="contextual-tip-vellum"
      aria-hidden="true"
    />
  )
}
