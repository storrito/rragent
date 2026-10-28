// The touch-up of the webcam (rragent.client.effects, its worker
// worker.js): the skin of the face smoothed, as Google Meet's portrait
// touch-up does, in two strengths. The face comes from MediaPipe's face
// landmarker as 478 points; the skin is the face's oval without the
// eyes, the eyebrows and the lips, each a polygon of landmark indices
// made here from the landmarker's connections. Pure functions, tested
// from rragent.client.effects-test.

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
 * The polygons the landmarker's connections ({start, end} pairs of
 * landmark indices) outline, as arrays of indices. The connections
 * come in runs: a closed loop (the face's oval) is a polygon by
 * itself; two open runs side by side (the lower and the upper line of
 * an eye, of the lips, of an eyebrow) are one polygon, the second run
 * walked back; the lips are two such pairs, the outer and the inner.
 */
export function polygons(connections) {
  const chains = [];
  const used = new Set();
  for (let i = 0; i < connections.length; i++) {
    if (used.has(i)) continue;
    used.add(i);
    const chain = [connections[i].start, connections[i].end];
    let found = true;
    while (found) {
      found = false;
      for (let j = 0; j < connections.length; j++) {
        if (used.has(j) || connections[j].start !== chain[chain.length - 1]) continue;
        used.add(j);
        chain.push(connections[j].end);
        found = true;
        break;
      }
    }
    chains.push(chain);
  }
  const out = [];
  for (let i = 0; i < chains.length; i++) {
    const chain = chains[i];
    if (chain[0] === chain[chain.length - 1]) {
      out.push(chain.slice(0, -1));
    } else if (i + 1 < chains.length) {
      out.push(chain.concat(chains[i + 1].slice().reverse()));
      i++;
    } else {
      out.push(chain);
    }
  }
  return out;
}

/** The box around the points of `indices`, in the landmarks' 0..1. */
export function box(landmarks, indices) {
  let left = 1, top = 1, right = 0, bottom = 0;
  for (const i of indices) {
    const p = landmarks[i];
    if (!p) continue;
    if (p.x < left) left = p.x;
    if (p.x > right) right = p.x;
    if (p.y < top) top = p.y;
    if (p.y > bottom) bottom = p.y;
  }
  return { left, top, right, bottom, width: Math.max(0, right - left), height: Math.max(0, bottom - top) };
}
