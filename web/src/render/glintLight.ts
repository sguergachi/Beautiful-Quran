/** Android ui/theme/GlintColor.kt: pulse in linear light, never opacity strobing. */
const clamp = (x: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, x))
export const GLINT_REST_LIGHT = 0.8858
export function glintLightLevel(
  glow: number,
  brightness = 1,
  rise = 0.3,
  fall = 0.1101,
): number {
  const b = clamp(brightness, 0, 2),
    scale = b <= 1 ? b : 1 + (b - 1) * 0.5
  return 1 + scale * (glow >= 0 ? rise : fall) * glow
}
export function glintLetterLight(
  level: number,
  rest = GLINT_REST_LIGHT,
): number {
  return clamp(clamp(rest) * level)
}
export function glintGlowAlpha(
  resting: number,
  level: number,
  gain = 8.6696,
  rest = GLINT_REST_LIGHT,
): number {
  if (rest <= 0) return 0
  const letters = glintLetterLight(level, rest) / Math.min(1, rest)
  return letters <= 0
    ? 0
    : clamp(
        resting *
          Math.pow(Math.max(0, 1 + gain * (level - 1)) / letters, 1 / 2.2),
      )
}
export function glintLightLagMs(
  smoothMs: number,
  rateHz: number,
  speed = 1,
): number {
  if (smoothMs <= 0 || speed <= 0) return 0
  const omega = 2 * Math.PI * (rateHz > 0 ? rateHz : 6) * speed
  return (Math.atan((omega * smoothMs) / 1000) / omega) * 1000 * speed
}
export function glintResonance(pulse: number, gain: number): number {
  const raw = clamp(pulse, -1.5, 1.5)
  const swing = clamp((1.4142 * raw) / Math.sqrt(1 + raw * raw), -1, 1)
  return Math.min(1, clamp(gain) * 4) * swing
}
