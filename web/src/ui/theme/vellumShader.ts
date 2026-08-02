/**
 * Progressive vellum fragment shader — Android `VellumFieldShader` ported to GLSL.
 *
 * AGSL uses top-left Y-down fragCoords; we flip `gl_FragCoord` to match.
 * Noise runs in CSS-pixel space so grain scale matches Android density pixels
 * rather than device pixels. Hash avoids `sin` of large values — that path
 * collapses to a visible lattice under WebGL mediump / software GL.
 */

/** Defaults mirrored from Android `ContextualGuideTuning`. */
export const VELLUM_TUNING = {
  bodyEdge: 0.5,
  featherWidth: 0.2819,
  fadeSoftness: 1.3329,
  blurRadiusPx: 24,
  blurStrength: 1,
  vellumGrain: 0.0297,
  verticalTaper: 0.24,
} as const

export const VELLUM_VERT = `
attribute vec2 a_pos;
void main() {
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
`

export const VELLUM_FRAG = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 u_resolution;
uniform float u_dpr;
uniform vec2 u_spotlight;
uniform vec2 u_bodyCenter;
uniform vec2 u_actionCenter;
uniform float u_progress;
uniform vec2 u_flow;
uniform float u_bodyEdge;
uniform float u_featherWidth;
uniform float u_fadeSoftness;
uniform float u_blurRadius;
uniform float u_blurStrength;
uniform float u_vellumGrain;
uniform float u_verticalTaper;
uniform vec4 u_inkColor;

float smoother(float value) {
  return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

vec2 orientedCoordinates(vec2 point) {
  vec2 flow = u_flow;
  vec2 resolution = u_resolution;
  vec2 crossAxis = vec2(-flow.y, flow.x);
  if (abs(crossAxis.y) > 0.0001) {
    if (crossAxis.y < 0.0) crossAxis = -crossAxis;
  } else if (crossAxis.x < 0.0) {
    crossAxis = -crossAxis;
  }
  float alongMin = min(0.0, flow.x * resolution.x)
    + min(0.0, flow.y * resolution.y);
  float crossMin = min(0.0, crossAxis.x * resolution.x)
    + min(0.0, crossAxis.y * resolution.y);
  float alongSpan = max(
    abs(flow.x) * resolution.x + abs(flow.y) * resolution.y,
    1.0
  );
  float crossSpan = max(
    abs(crossAxis.x) * resolution.x + abs(crossAxis.y) * resolution.y,
    1.0
  );
  return vec2(
    (dot(point, flow) - alongMin) / alongSpan,
    (dot(point, crossAxis) - crossMin) / crossSpan
  );
}

// Precision-safe hash — no large-argument sin() (that grids under mediump).
float hash(vec2 point) {
  vec3 p3 = fract(vec3(point.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 point) {
  vec2 cell = floor(point);
  vec2 part = fract(point);
  // Quintic fade — softer cell seams than cubic, reads as pigment not tiles.
  part = part * part * part * (part * (part * 6.0 - 15.0) + 10.0);
  return mix(
    mix(hash(cell), hash(cell + vec2(1.0, 0.0)), part.x),
    mix(hash(cell + vec2(0.0, 1.0)), hash(cell + vec2(1.0, 1.0)), part.x),
    part.y
  );
}

// Multi-octave value noise — breaks residual lattice into brush tooth.
float fbm(vec2 point) {
  float value = 0.0;
  float amp = 0.5;
  mat2 rot = mat2(0.8, -0.6, 0.6, 0.8);
  for (int i = 0; i < 4; i++) {
    value += amp * noise(point);
    point = rot * point * 2.02 + vec2(17.3, 9.1);
    amp *= 0.5;
  }
  return value;
}

float brushedPigment(vec2 fragCoord) {
  vec2 warp = vec2(
    fbm(fragCoord * 0.0027),
    fbm((fragCoord + vec2(173.0, 91.0)) * 0.0031)
  ) - 0.5;
  vec2 point = fragCoord + warp * 36.0;
  float pooling = fbm(point * 0.0045) * 0.55
    + fbm((point + vec2(47.0, 113.0)) * 0.011) * 0.3
    + fbm((point + vec2(211.0, 29.0)) * 0.026) * 0.15;
  // Directional fibres — long in Y, short in X, like laid paper.
  float fibres = fbm(vec2(
    point.x * 0.021 + fbm(point * 0.004) * 1.7,
    point.y * 0.006
  ));
  float tooth = fbm((point + vec2(61.0, 157.0)) * 0.057);
  // Fine speckled grain on top of the fibre field.
  float speck = hash(floor(point * 0.85)) * 0.35
    + hash(floor(point * 1.7 + 19.0)) * 0.2;
  return pooling * 0.36 + fibres * 0.28 + tooth * 0.22 + speck * 0.14;
}

float vellumDensity(vec2 fragCoord) {
  float progress = u_progress;
  float featherWidth = u_featherWidth;
  float bodyEdge = u_bodyEdge;
  float verticalTaper = u_verticalTaper;
  float vellumGrain = u_vellumGrain;

  vec2 oriented = orientedCoordinates(fragCoord);
  vec2 target = orientedCoordinates(u_spotlight);
  vec2 body = orientedCoordinates(u_bodyCenter);
  float fromEdge = oriented.x;
  float cross = oriented.y;
  float targetCross = target.y;
  float riseEnd = clamp(targetCross + 0.05, 0.30, 0.42);
  float rise = smoother(clamp((cross + 0.02) / riseEnd, 0.0, 1.0));
  float tail = 1.0 - 0.68 * smoother(clamp((cross - 0.72) / 0.28, 0.0, 1.0));
  float silhouette = rise * tail;
  float taper = verticalTaper * (1.0 - silhouette);
  float lessonDistance = (cross - targetCross) / 0.17;
  float lessonReservoir = verticalTaper * 0.85
    * exp(-lessonDistance * lessonDistance);
  float bodyFloor = body.x + featherWidth * 0.08;
  float spotlightCeiling = target.x - featherWidth * 0.12;
  float midpoint = clamp(
    bodyEdge + featherWidth * 0.32 - taper + lessonReservoir,
    min(bodyFloor, spotlightCeiling),
    max(bodyFloor, spotlightCeiling)
  ) * progress;
  float diffusion = max(featherWidth * 0.28, 0.025);
  float clouds = fbm(fragCoord * vec2(0.006, 0.008)) * 0.65
    + fbm(fragCoord * vec2(0.017, 0.021)) * 0.35;
  float fibres = fbm(fragCoord * vec2(0.055, 0.071));
  float texture = (clouds - 0.5) + (fibres - 0.5) * 0.18;
  float absorbed = midpoint - fromEdge + texture * vellumGrain * 0.18;
  float lessonDensity = 1.0 / (1.0 + exp(-absorbed / diffusion));

  float actionCross = orientedCoordinates(u_actionCenter).y;
  float buttonDistance = (cross - actionCross) / 0.11;
  float buttonEnvelope = exp(-buttonDistance * buttonDistance);
  float buttonMidpoint = clamp(
    bodyEdge + featherWidth * 0.18,
    min(bodyFloor, spotlightCeiling),
    max(bodyFloor, spotlightCeiling)
  ) * progress;
  float buttonDiffusion = max(featherWidth * 0.22, 0.02);
  float buttonAbsorbed = buttonMidpoint - fromEdge
    + texture * vellumGrain * 0.12;
  float buttonDensity = buttonEnvelope
    / (1.0 + exp(-buttonAbsorbed / buttonDiffusion));
  return 1.0 - (1.0 - lessonDensity) * (1.0 - buttonDensity);
}

float vellumCoverage(float density) {
  float wash = pow(density, max(0.7, u_fadeSoftness * 0.58));
  return 1.0 - pow(1.0 - wash, 1.18);
}

void main() {
  // Device → CSS pixels; Y-flip to AGSL top-left space.
  vec2 fragCoord = vec2(gl_FragCoord.x, gl_FragCoord.y) / max(u_dpr, 1.0);
  fragCoord.y = u_resolution.y - fragCoord.y;

  float density = vellumDensity(fragCoord);
  float coverage = vellumCoverage(density);
  float edge = 4.0 * density * (1.0 - density);
  float radius = u_blurRadius * (0.35 + 0.65 * edge);
  vec2 axis = u_flow * radius;
  float softened = coverage * 0.36;
  softened += vellumCoverage(vellumDensity(fragCoord + axis)) * 0.24;
  softened += vellumCoverage(vellumDensity(fragCoord - axis)) * 0.24;
  softened += vellumCoverage(vellumDensity(fragCoord + axis * 2.0)) * 0.08;
  softened += vellumCoverage(vellumDensity(fragCoord - axis * 2.0)) * 0.08;
  coverage = mix(coverage, softened, u_blurStrength * edge);

  // Paper tooth varies pigment load; fine dither only on the translucent ramp.
  float brush = brushedPigment(fragCoord) - 0.5;
  float dither = (hash(floor(fragCoord)) - 0.5) * edge / 255.0;
  coverage = clamp(
    coverage * (1.0 + brush * u_vellumGrain * (1.4 + 2.0 * edge)) + dither,
    0.0,
    1.0
  );
  float fromSource = orientedCoordinates(fragCoord).x;
  float sourcePool = smoother(clamp((0.5 - fromSource) / 0.5, 0.0, 1.0));
  coverage = 1.0 - pow(1.0 - coverage, 1.0 + 0.75 * sourcePool);
  float alpha = u_inkColor.a * coverage * u_progress;
  gl_FragColor = vec4(u_inkColor.rgb * alpha, alpha);
}
`
