import type { Frame } from './pagePicture'
import type { LeafPose } from './pageTurnMotion'

/**
 * The leaf in the air, drawn as one bent sheet.
 *
 * It used to be eighteen flat strips of DOM hinged in CSS 3D: some forty
 * composited layers of text per turn, creased at every joint, and frames of
 * half a second while they were built and rastered. Here the leaf is a mesh
 * the vertex shader bends along the curve in pageTurnMotion, carrying a
 * picture of each of its two pages (pagePicture). A frame is three draw
 * calls and nothing is laid out, so the turn runs at the display's rate.
 *
 * WebGL 2, not WebGPU: one textured mesh asks nothing of a GPU that WebGL
 * cannot give, and WebGL 2 is in every browser the app runs in.
 */

/** Everything about one turn that does not change while it runs. */
export interface TurnScene {
  /** The canvas's box, in CSS pixels of the viewport. */
  box: Frame
  /** Device pixels to the CSS pixel. */
  scale: number
  /** The bound edge, and the leaf's top. */
  hingeX: number
  top: number
  /** The leaf's size, flat. */
  width: number
  height: number
  /** The side of the bound edge the leaf lies on before it turns: -1 left, 1 right. */
  side: -1 | 1
  /** The page the leaf lifts off with, and where that picture lies. */
  front: { picture: HTMLCanvasElement; frame: Frame }
  /** The page it lands as; a leaf with nowhere to land has a bare back. */
  back: { picture: HTMLCanvasElement; frame: Frame } | null
  /** The thickness of the pile the turn carries, in CSS pixels; 0 for a leaf. */
  wad: number
  /** The eye: where it looks, and how far off it stands. */
  originX: number
  originY: number
  perspective: number
  paper: Rgba
  /** What the sheet darkens toward as it stands up out of the light. */
  shade: Rgba
  /** A pile's edges: the lines of its leaves, and the shade toward its underside. */
  edgeLine: Rgba
  edgeShade: Rgba
}

export type Rgba = [number, number, number, number]

/** Columns bend the sheet; rows only carry the corner's lead. */
const COLUMNS = 72
const ROWS = 12

/** Above this many device pixels the canvas goes without multisampling. */
const MSAA_PIXEL_LIMIT = 4_200_000

const VERTEX = `#version 300 es
precision highp float;
in vec3 aPlace; // share of width from the bound edge, share of height, depth into the pile
in float aKind; // 0 a face, 1 fore-edge, 2 head, 3 foot
uniform float uTurn;
uniform float uCurl;
uniform float uTaper;
uniform float uSink;
uniform float uSide;
uniform float uWad;
uniform float uUnder; // a face drawn as the pile's underside
uniform vec2 uHinge;
uniform vec2 uLeaf;
uniform vec4 uFront;
uniform vec4 uBack;
uniform vec4 uEye; // origin x, origin y, distance, depth range
uniform vec4 uBox; // left, top, 1/width, 1/height
out vec2 vFront;
out vec2 vBack;
out float vShade;
out float vKind;
out float vDepth;
const float PI = 3.141592653589793;

void main() {
  float u = aPlace.x;
  float v = aPlace.y;
  // The foot corner leads; no row passes flat on the page it lands on.
  float curl = min(uCurl * (1.0 + uTaper * (2.0 * v - 1.0)), PI - uTurn);
  float half_ = 0.5 * curl * u;
  float sinc = abs(half_) < 1e-4 ? 1.0 : sin(half_) / half_;
  float along = uLeaf.x * u * cos(uTurn + half_) * sinc;
  float above = uLeaf.x * u * sin(uTurn + half_) * sinc;
  // The surface's own direction here, and its normal on the lifted side.
  float slope = uTurn + curl * u;
  float depth = (aKind < 0.5 ? uUnder : aPlace.z) * uWad;
  along += sin(slope) * depth;
  above -= cos(slope) * depth + uSink;

  vec3 at = vec3(uHinge.x + uSide * along, uHinge.y + v * uLeaf.y, above);
  float flatX = uHinge.x + uSide * uLeaf.x * u;
  float landedX = uHinge.x - uSide * uLeaf.x * u;
  vFront = vec2((flatX - uFront.x) * uFront.z, (at.y - uFront.y) * uFront.w);
  vBack = vec2((landedX - uBack.x) * uBack.z, (at.y - uBack.y) * uBack.w);
  vShade = 1.0 - abs(cos(slope));
  vKind = aKind;
  vDepth = depth;

  // The CSS perspective the book was drawn in: the page's plane is unmoved.
  float w = (uEye.z - at.z) / uEye.z;
  vec2 eye = uEye.xy - uBox.xy;
  vec2 on = at.xy - uBox.xy;
  gl_Position = vec4(
    2.0 * (eye.x * w + on.x - eye.x) * uBox.z - w,
    w - 2.0 * (eye.y * w + on.y - eye.y) * uBox.w,
    -at.z / uEye.w * w,
    w
  );
}`

const FRAGMENT = `#version 300 es
precision highp float;
in vec2 vFront;
in vec2 vBack;
in float vShade;
in float vKind;
in float vDepth;
uniform sampler2D uFrontPicture;
uniform sampler2D uBackPicture;
uniform float uFace; // 0 the lifted face, 1 the underside
uniform float uHasBack;
uniform float uWad;
uniform vec4 uPaper;
uniform vec4 uShade;
uniform vec4 uEdgeLine;
uniform vec4 uEdgeShade;
out vec4 color;

void main() {
  vec3 ink;
  if (vKind < 0.5) {
    ink = uFace < 0.5
      ? texture(uFrontPicture, vFront).rgb
      : mix(uPaper.rgb, texture(uBackPicture, vBack).rgb, uHasBack);
  } else {
    // Leaves seen edge-on: a line to each two pixels of thickness, lost to
    // an even tone where they are too fine to tell apart.
    float line = step(0.5, fract(vDepth * 0.5));
    float fine = clamp(fwidth(vDepth) - 0.5, 0.0, 1.0);
    ink = mix(uPaper.rgb, uEdgeLine.rgb, uEdgeLine.a * mix(line, 0.5, fine));
    ink = mix(ink, uEdgeShade.rgb, uEdgeShade.a * clamp(vDepth / max(uWad, 1.0), 0.0, 1.0));
  }
  // The sheet's own shading as it stands up out of the light.
  ink = mix(ink, uShade.rgb, uShade.a * vShade);
  color = vec4(ink, 1.0);
}`

function buildMesh(): Float32Array {
  const out: number[] = []
  const quad = (
    a: [number, number, number],
    b: [number, number, number],
    c: [number, number, number],
    d: [number, number, number],
    kind: number,
  ) => {
    for (const point of [a, b, c, b, d, c]) out.push(point[0], point[1], point[2], kind)
  }
  for (let row = 0; row < ROWS; row++) {
    for (let column = 0; column < COLUMNS; column++) {
      const u0 = column / COLUMNS
      const u1 = (column + 1) / COLUMNS
      const v0 = row / ROWS
      const v1 = (row + 1) / ROWS
      quad([u0, v0, 0], [u1, v0, 0], [u0, v1, 0], [u1, v1, 0], 0)
    }
  }
  for (let row = 0; row < ROWS; row++) {
    const v0 = row / ROWS
    const v1 = (row + 1) / ROWS
    quad([1, v0, 0], [1, v0, 1], [1, v1, 0], [1, v1, 1], 1)
  }
  for (let column = 0; column < COLUMNS; column++) {
    const u0 = column / COLUMNS
    const u1 = (column + 1) / COLUMNS
    quad([u0, 0, 0], [u1, 0, 0], [u0, 0, 1], [u1, 0, 1], 2)
    quad([u0, 1, 0], [u1, 1, 0], [u0, 1, 1], [u1, 1, 1], 3)
  }
  return new Float32Array(out)
}

const FACE_VERTICES = ROWS * COLUMNS * 6
const EDGE_VERTICES = (ROWS + 2 * COLUMNS) * 6

function compile(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type)!
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(shader) ?? 'shader failed to compile')
  }
  return shader
}

/** One canvas and one context, kept for every turn. */
export class PageTurnGl {
  readonly canvas: HTMLCanvasElement
  private readonly gl: WebGL2RenderingContext
  private readonly program: WebGLProgram
  private readonly mesh: WebGLVertexArrayObject
  private readonly uniforms = new Map<string, WebGLUniformLocation | null>()
  /** Pictures already on the GPU: one kept ahead of its turn costs nothing at the lift. */
  private readonly held = new Map<HTMLCanvasElement, WebGLTexture>()
  private readonly bare: WebGLTexture
  private scene: TurnScene | null = null
  lost = false

  private constructor(canvas: HTMLCanvasElement, gl: WebGL2RenderingContext) {
    this.canvas = canvas
    this.gl = gl
    const program = gl.createProgram()!
    gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, VERTEX))
    gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, FRAGMENT))
    gl.bindAttribLocation(program, 0, 'aPlace')
    gl.bindAttribLocation(program, 1, 'aKind')
    gl.linkProgram(program)
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      throw new Error(gl.getProgramInfoLog(program) ?? 'program failed to link')
    }
    this.program = program

    const vao = gl.createVertexArray()!
    const buffer = gl.createBuffer()!
    gl.bindVertexArray(vao)
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, buildMesh(), gl.STATIC_DRAW)
    gl.enableVertexAttribArray(0)
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 16, 0)
    gl.enableVertexAttribArray(1)
    gl.vertexAttribPointer(1, 1, gl.FLOAT, false, 16, 12)
    gl.bindVertexArray(null)
    this.mesh = vao

    this.bare = this.texture()
    canvas.addEventListener('webglcontextlost', (event) => {
      event.preventDefault()
      this.lost = true
    })
  }

  /** Null where the browser has no WebGL 2 to give: a turn is then skipped. */
  static create(pixels: number): PageTurnGl | null {
    try {
      const canvas = document.createElement('canvas')
      canvas.className = 'mushaf-flip-canvas'
      const gl = canvas.getContext('webgl2', {
        alpha: true,
        premultipliedAlpha: true,
        depth: true,
        stencil: false,
        // Multisampling a 4K canvas costs more memory than its edges repay.
        antialias: pixels <= MSAA_PIXEL_LIMIT,
        preserveDrawingBuffer: false,
      })
      return gl ? new PageTurnGl(canvas, gl) : null
    } catch {
      return null
    }
  }

  private texture(): WebGLTexture {
    const gl = this.gl
    const texture = gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    // A leaf is seen nearly edge-on for half its turn: mip levels, sampled
    // along the slant, keep its lines of text from shimmering.
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR)
    const slant = gl.getExtension('EXT_texture_filter_anisotropic')
    if (slant) {
      const most = gl.getParameter(slant.MAX_TEXTURE_MAX_ANISOTROPY_EXT) as number
      gl.texParameterf(gl.TEXTURE_2D, slant.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(16, most))
    }
    // Complete from the start: a leaf with a bare back still binds this.
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    return texture
  }

  private uniform(name: string): WebGLUniformLocation | null {
    let location = this.uniforms.get(name)
    if (location === undefined) {
      location = this.gl.getUniformLocation(this.program, name)
      this.uniforms.set(name, location)
    }
    return location
  }

  /** The longest side a picture may have. */
  get pictureLimit(): number {
    return this.gl.getParameter(this.gl.MAX_TEXTURE_SIZE) as number
  }

  /** Sizes the canvas to [box]. Done ahead of a turn, never as one starts. */
  size(box: Frame, scale: number) {
    const { canvas } = this
    const width = Math.max(1, Math.round(box.width * scale))
    const height = Math.max(1, Math.round(box.height * scale))
    if (canvas.width !== width) canvas.width = width
    if (canvas.height !== height) canvas.height = height
    canvas.style.width = `${width / scale}px`
    canvas.style.height = `${height / scale}px`
  }

  /** Takes the scene, and its pictures if they are not held yet. */
  load(scene: TurnScene) {
    this.scene = scene
    this.size(scene.box, scene.scale)
    this.hold(scene.front.picture)
    if (scene.back) this.hold(scene.back.picture)
  }

  /** Puts [picture] on the GPU, if it is not there yet. */
  hold(picture: HTMLCanvasElement): WebGLTexture {
    const kept = this.held.get(picture)
    if (kept) return kept
    const gl = this.gl
    const texture = this.texture()
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, picture)
    gl.generateMipmap(gl.TEXTURE_2D)
    this.held.set(picture, texture)
    return texture
  }

  /** Takes [picture] off the GPU. */
  release(picture: HTMLCanvasElement) {
    const texture = this.held.get(picture)
    if (!texture) return
    this.held.delete(picture)
    if (!this.lost) this.gl.deleteTexture(texture)
  }

  draw(pose: LeafPose) {
    const scene = this.scene
    if (!scene || this.lost) return
    const gl = this.gl
    gl.viewport(0, 0, this.canvas.width, this.canvas.height)
    gl.clearColor(0, 0, 0, 0)
    gl.clearDepth(1)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    gl.enable(gl.DEPTH_TEST)
    gl.depthFunc(gl.LEQUAL)
    gl.disable(gl.BLEND)
    gl.useProgram(this.program)
    gl.bindVertexArray(this.mesh)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.hold(scene.front.picture))
    gl.activeTexture(gl.TEXTURE1)
    gl.bindTexture(gl.TEXTURE_2D, scene.back ? this.hold(scene.back.picture) : this.bare)

    const frame = (name: string, at: Frame) =>
      gl.uniform4f(this.uniform(name), at.left, at.top, 1 / at.width, 1 / at.height)
    const tone = (name: string, rgba: Rgba) => gl.uniform4f(this.uniform(name), ...rgba)
    gl.uniform1f(this.uniform('uTurn'), pose.turn)
    gl.uniform1f(this.uniform('uCurl'), pose.curl)
    gl.uniform1f(this.uniform('uTaper'), pose.taper)
    // A pile turns about the middle of its own thickness: the face it lifted
    // by starts level with the page, and the face it lands on ends level
    // with the page it lands on.
    gl.uniform1f(this.uniform('uSink'), pose.share * scene.wad)
    gl.uniform1f(this.uniform('uSide'), scene.side)
    gl.uniform1f(this.uniform('uWad'), scene.wad)
    gl.uniform2f(this.uniform('uHinge'), scene.hingeX, scene.top)
    gl.uniform2f(this.uniform('uLeaf'), scene.width, scene.height)
    frame('uFront', scene.front.frame)
    frame('uBack', scene.back?.frame ?? scene.front.frame)
    gl.uniform4f(
      this.uniform('uEye'),
      scene.originX,
      scene.originY,
      scene.perspective,
      // Nothing of the leaf stands further from the page than its own width.
      (scene.width + scene.wad) * 1.25,
    )
    frame('uBox', scene.box)
    gl.uniform1f(this.uniform('uHasBack'), scene.back ? 1 : 0)
    tone('uPaper', scene.paper)
    tone('uShade', scene.shade)
    tone('uEdgeLine', scene.edgeLine)
    tone('uEdgeShade', scene.edgeShade)
    gl.uniform1i(this.uniform('uFrontPicture'), 0)
    gl.uniform1i(this.uniform('uBackPicture'), 1)

    // Each face is drawn only from its own side, so a leaf with no
    // thickness never fights itself for a pixel.
    gl.enable(gl.CULL_FACE)
    gl.frontFace(scene.side > 0 ? gl.CW : gl.CCW)
    gl.cullFace(gl.BACK)
    gl.uniform1f(this.uniform('uFace'), 0)
    gl.uniform1f(this.uniform('uUnder'), 0)
    gl.drawArrays(gl.TRIANGLES, 0, FACE_VERTICES)
    // The same mesh again from behind, set down by the pile's thickness.
    gl.cullFace(gl.FRONT)
    gl.uniform1f(this.uniform('uFace'), 1)
    gl.uniform1f(this.uniform('uUnder'), 1)
    gl.drawArrays(gl.TRIANGLES, 0, FACE_VERTICES)
    if (scene.wad > 0) {
      gl.disable(gl.CULL_FACE)
      gl.drawArrays(gl.TRIANGLES, FACE_VERTICES, EDGE_VERTICES)
    }
    gl.bindVertexArray(null)
  }

  /** Empties the canvas. Pictures stay until they are released. */
  clear() {
    this.scene = null
    if (this.lost) return
    const gl = this.gl
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
  }
}
