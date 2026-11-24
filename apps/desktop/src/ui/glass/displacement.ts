/**
 * Liquid Glass lens model.
 *
 * Builds an feDisplacementMap image for a rounded rectangle. The rim is a convex
 * squircle bevel; each pixel is refracted with Snell's law (single interface,
 * orthogonal view ray) so light bends most where the surface is steepest and the
 * flat interior stays undistorted. Red encodes X, green encodes Y, 128 is neutral.
 */
export interface LensOptions {
  width: number;
  height: number;
  radius: number;
  /** Width of the curved rim in CSS px. */
  bezel: number;
  /** Index of refraction; glass is ~1.5. */
  ior?: number;
}

export interface LensMap {
  data: Uint8ClampedArray;
  width: number;
  height: number;
}

/** Convex squircle profile: 0 at the edge, 1 where the rim meets the flat face. */
export function rimHeight(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  return Math.pow(1 - Math.pow(1 - x, 4), 0.25);
}

/** Normalized refraction magnitude across the rim (0 = edge, 1 = flat). */
export function rimProfile(ior = 1.5, samples = 128): Float32Array {
  const out = new Float32Array(samples + 1);
  let peak = 0;
  for (let i = 0; i <= samples; i++) {
    const t = i / samples;
    const dt = 1 / samples;
    const slope =
      (rimHeight(Math.min(1, t + dt)) - rimHeight(Math.max(0, t - dt))) /
      (Math.min(1, t + dt) - Math.max(0, t - dt));
    const incidence = Math.atan(slope);
    const refracted = Math.asin(Math.sin(incidence) / ior);
    const value = rimHeight(t) * Math.tan(incidence - refracted);
    out[i] = Number.isFinite(value) ? value : 0;
    peak = Math.max(peak, out[i]);
  }
  if (peak > 0) for (let i = 0; i <= samples; i++) out[i] /= peak;
  return out;
}

export function buildLensMap({
  width,
  height,
  radius,
  bezel,
  ior = 1.5,
}: LensOptions): LensMap {
  const w = Math.max(1, Math.round(width));
  const h = Math.max(1, Math.round(height));
  const r = Math.max(0, Math.min(radius, w / 2, h / 2));
  const rim = Math.max(1, Math.min(bezel, w / 2, h / 2));
  const profile = rimProfile(ior);
  const steps = profile.length - 1;
  const data = new Uint8ClampedArray(w * h * 4);
  const hx = w / 2;
  const hy = h / 2;

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      const px = x + 0.5 - hx;
      const py = y + 0.5 - hy;
      const qx = Math.abs(px) - (hx - r);
      const qy = Math.abs(py) - (hy - r);
      const ox = Math.max(qx, 0);
      const oy = Math.max(qy, 0);
      const outside = Math.hypot(ox, oy);
      const sdf = outside + Math.min(Math.max(qx, qy), 0) - r;
      let dx = 0;
      let dy = 0;
      if (sdf <= 0) {
        const depth = -sdf;
        if (depth < rim) {
          let nx: number;
          let ny: number;
          if (outside > 0) {
            nx = (ox / outside) * Math.sign(px);
            ny = (oy / outside) * Math.sign(py);
          } else if (qx > qy) {
            nx = Math.sign(px);
            ny = 0;
          } else {
            nx = 0;
            ny = Math.sign(py);
          }
          const m = profile[Math.round((depth / rim) * steps)];
          // Sample toward the centre so the rim magnifies what sits beneath it.
          dx = -nx * m;
          dy = -ny * m;
        }
      }
      data[i] = Math.round(128 + dx * 127);
      data[i + 1] = Math.round(128 + dy * 127);
      data[i + 2] = 128;
      data[i + 3] = 255;
    }
  }
  return { data, width: w, height: h };
}
