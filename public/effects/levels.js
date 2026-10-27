// The automatic light of the webcam (rragent.client.effects, its worker
// worker.js): what a small copy of the frame says of its brightness,
// contrast and colour, and the correction that brings them to a
// pleasant middle, as a CSS filter the canvas draws the frame through.
// Pure functions, tested from rragent.client.effects-test.

/** Where the picture should be, and how far a correction may go. */
export const targets = {
  luma: 0.47,          // the mean brightness of the person, 0..1
  spread: 0.2,         // the standard deviation of the brightness
  saturation: 0.36,    // the mean saturation of the person, 0..1
  brightness: [0.7, 1.9],
  contrast: [0.9, 1.35],
  saturate: [0.85, 1.4],
};

/** No correction. */
export const neutral = { brightness: 1, contrast: 1, saturate: 1 };

/**
 * The brightness, contrast and saturation of `data` (RGBA bytes of a
 * frame), each pixel weighted by `weights` (0..1 per pixel, the person's
 * mask) when given, else every pixel alike. Nil when nothing weighs.
 */
export function measure(data, weights) {
  const n = data.length >> 2;
  let sum = 0;
  let sumSquares = 0;
  let sat = 0;
  let total = 0;
  for (let i = 0; i < n; i++) {
    const w = weights ? weights[i] : 1;
    if (w <= 0.01) continue;
    const r = data[i * 4] / 255;
    const g = data[i * 4 + 1] / 255;
    const b = data[i * 4 + 2] / 255;
    const luma = 0.299 * r + 0.587 * g + 0.114 * b;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    sum += w * luma;
    sumSquares += w * luma * luma;
    sat += w * (max > 0 ? (max - min) / max : 0);
    total += w;
  }
  if (total < 1) return null;
  const luma = sum / total;
  const variance = Math.max(0, sumSquares / total - luma * luma);
  return { luma, spread: Math.sqrt(variance), saturation: sat / total };
}

function clamp(x, [lo, hi]) {
  return Math.min(hi, Math.max(lo, x));
}

/**
 * The correction a measurement asks for: the brightness that brings the
 * mean to the target, more contrast for a flat picture, more colour for
 * a dull one and a little less for a loud one. Each within its bounds.
 */
export function correction(m) {
  if (!m) return neutral;
  const brightness = clamp(targets.luma / Math.max(m.luma, 0.02), targets.brightness);
  // a brighter picture spreads its values too: the contrast is judged after
  const spread = m.spread * brightness;
  const contrast = clamp(spread < targets.spread ? targets.spread / Math.max(spread, 0.02) : 1, targets.contrast);
  const saturate = clamp(targets.saturation / Math.max(m.saturation, 0.02), targets.saturate);
  return { brightness, contrast, saturate };
}

/**
 * `wanted` approached from `current` by `rate` (0..1): the picture
 * changes slowly, never flickers.
 */
export function ease(current, wanted, rate) {
  const step = (a, b) => a + (b - a) * rate;
  return {
    brightness: step(current.brightness, wanted.brightness),
    contrast: step(current.contrast, wanted.contrast),
    saturate: step(current.saturate, wanted.saturate),
  };
}

/** The correction as the filter the canvas draws through. */
export function filterOf(c) {
  const near = (x) => Math.abs(x - 1) < 0.005;
  if (near(c.brightness) && near(c.contrast) && near(c.saturate)) return "none";
  return `brightness(${c.brightness.toFixed(3)}) contrast(${c.contrast.toFixed(3)}) saturate(${c.saturate.toFixed(3)})`;
}
