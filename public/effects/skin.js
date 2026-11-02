// The touch-up of the webcam (rragent.client.effects, its worker
// worker.js): the skin of the face smoothed, as Google Meet's portrait
// touch-up does, in two strengths. The face comes from MediaPipe's
// BlazeFace as six points (the eyes, the nose, the mouth, the ears); the
// skin is ellipses laid by the face's usual proportions: the face's
// oval, generous, as what is not skin differs in colour and the
// smoothing leaves it, without the eyes, the eyebrows and the mouth.
// Pure functions, tested from rragent.client.effects-test.

/**
 * The strengths: how much of the smoothed skin shows (`strength`,
 * 0..1), how different in colour a neighbour may be to be averaged in
 * (`range`, 0..1 of the colour's scale; pores and shadows are within,
 * eyelashes and the edge of the face are without), and how far the
 * neighbours lie, in pixels of a 1280-wide picture (`step`, times the
 * three taps each side).
 */
export const levels = {
  subtle: { strength: 0.55, range: 0.07, step: 1.6 },
  smooth: { strength: 0.85, range: 0.11, step: 2.4 },
};

/**
 * The skin of a face BlazeFace found, as ellipses ({x, y, rx, ry,
 * angle}, in pixels of a `width` x `height` picture): the face's oval
 * and the holes in it (the eyes, the eyebrows, the mouth), laid by the
 * usual proportions of a face from its `keypoints` (0..1: the right
 * eye, the left eye, the nose, the mouth, the right ear, the left ear,
 * as BlazeFace gives them). Null without the eyes and the mouth.
 */
export function ellipses(keypoints, width, height) {
  if (!keypoints || keypoints.length < 4) return null;
  const p = (i) => ({ x: keypoints[i].x * width, y: keypoints[i].y * height });
  const [right, left, , mouth] = [p(0), p(1), p(2), p(3)];
  const across = { x: left.x - right.x, y: left.y - right.y };
  const d = Math.hypot(across.x, across.y);
  if (d < 1) return null;
  // along the eyes, and down the face: towards the mouth, whichever eye comes first
  const eyes = { x: (right.x + left.x) / 2, y: (right.y + left.y) / 2 };
  const turn = (mouth.y - eyes.y) * across.x - (mouth.x - eyes.x) * across.y < 0 ? -1 : 1;
  const a = { x: (turn * across.x) / d, y: (turn * across.y) / d };
  const down = { x: -a.y, y: a.x };
  const at = (u, v) => ({ x: eyes.x + a.x * u + down.x * v, y: eyes.y + a.y * u + down.y * v });
  const angle = Math.atan2(a.y, a.x);
  const toMouth = Math.max(0.6 * d, (mouth.x - eyes.x) * down.x + (mouth.y - eyes.y) * down.y);
  // from the forehead to the chin, generous: what is not skin (hair, the
  // ears, the background) differs in colour and the smoothing leaves it
  const top = -1.3 * d;
  const bottom = toMouth + 0.9 * d;
  // between the ears where they were found, the face turned with them
  let middle = 0;
  let half = 1.15 * d;
  if (keypoints.length >= 6) {
    const [earR, earL] = [p(4), p(5)];
    const u = (q) => (q.x - eyes.x) * a.x + (q.y - eyes.y) * a.y;
    const span = Math.abs(u(earL) - u(earR));
    if (span > d) {
      middle = (u(earL) + u(earR)) / 2;
      half = 0.5 * span;
    }
  }
  const centre = at(middle, (top + bottom) / 2);
  const hole = (q, rx, ry, up = 0) => ({ x: q.x - down.x * up, y: q.y - down.y * up, rx, ry, angle });
  return {
    oval: { x: centre.x, y: centre.y, rx: half, ry: (bottom - top) / 2, angle },
    holes: [
      hole(right, 0.32 * d, 0.2 * d),
      hole(left, 0.32 * d, 0.2 * d),
      hole(right, 0.36 * d, 0.13 * d, 0.38 * d),
      hole(left, 0.36 * d, 0.13 * d, 0.38 * d),
      hole(mouth, 0.45 * d, 0.22 * d),
    ],
  };
}
