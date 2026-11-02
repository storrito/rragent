// The face of the webcam's effects, in a worker of its own beside the one
// that makes the picture (worker.js starts it): MediaPipe's BlazeFace
// finds it as six points (the eyes, the nose, the mouth, the ears), on
// the GPU, else on the CPU; its skin is drawn as ellipses laid by the
// face's usual proportions (skin.js), and the light is measured (levels.js). The picture never
// waits for it: worker.js sends a small copy of a frame whenever this
// worker is free and lays every frame over the last skin that came back,
// a frame or two old. A recorded file waits for each, frame by frame.
// The person's mask is worker.js's own, on the GPU.
//
// Messages in: {type: "settings", settings, announce, seq} (the first one
// too), then {type: "frame", small, face, timestamp, mask}: `small` the
// frame at 256 x 144 (or null) to measure the light on, over the person
// where `mask` (bytes, 255 the person, 256 x 144) says; `face` the frame
// at 512 x 288 (or null, the face not looked for this time),
// ImageBitmaps, closed here. Answered {type: "result", skin, faceSeen,
// light}: `skin` an ImageBitmap whose alpha is the skin (undefined when
// the face was not looked for), `light` the correction eased towards (or
// null). Messages out besides: {type: "ready", detector, seq} once
// BlazeFace runs when the settings need it (and after every new
// settings, `seq` theirs),
// {type: "loading"} when new settings need it and it is not loaded yet
// (`announce`), {type: "failed", message} when it could not be.

import { FaceDetector } from "./vision_bundle.mjs";
import { fileset, withFactory, onEither, base } from "./mediapipe.js";
import { measure, correction, ease, neutral } from "./levels.js";
import { levels as touchUps, ellipses } from "./skin.js";

/** The copy of the frame the light is measured on, and the mask's size. */
const MASK_WIDTH = 256;
const MASK_HEIGHT = 144;

/** The copy of the frame the face is found on. */
const FACE_WIDTH = 512;
const FACE_HEIGHT = 288;

/** How fast the light eases towards the one measured. */
const LIGHT_RATE = 0.12;

/** How often the time spent is told on the console. */
const RATE_MS = 1000;

let settings = { background: "off", autoLight: false, touchUp: "off" };
let detector = null;
let delegate = null;
let loading = null;
let lastFaceTimestamp = 0;

// the small copy, drawn here when the light is measured on it
const small = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const smallCtx = small.getContext("2d", { willReadFrequently: true });
// the person's mask as weights of the light
const maskValues = new Float32Array(MASK_WIDTH * MASK_HEIGHT);

// the skin as a picture, its alpha where the smoothing goes: drawn sharp, then softened
const skinSharp = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const skinSharpCtx = skinSharp.getContext("2d");
const skin = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const skinCtx = skin.getContext("2d");

let light = neutral;

// the time the models took since it was last told
let results = 0;
let since = 0;
let spent = { detect: 0, draw: 0 };

function needsFace() {
  return Boolean(touchUps[settings.touchUp]);
}

// --- loading --------------------------------------------------------

/**
 * BlazeFace, on the GPU, else on the CPU. MediaPipe takes every image as
 * a WebGL texture, so on the CPU it reads the image back, waiting for the
 * GPU: measured on an Intel laptop, 26 ms a face on the GPU, 33 on the
 * CPU (up to 45 when the CPU was busy), the picture's frames the same.
 */
function loadDetector() {
  loading ??= (async () => {
    const files = await fileset();
    const make = (on) =>
      withFactory(files, () =>
        FaceDetector.createFromOptions(files, {
          baseOptions: { modelAssetPath: base + "blaze_face_short_range.tflite", delegate: on },
          runningMode: "VIDEO",
          minDetectionConfidence: 0.5,
        })
      ).then((made) => {
        delegate = on;
        return made;
      });
    detector = await onEither("BlazeFace", make);
    if (settings.log) console.info(`[effects] BlazeFace on the ${delegate}`);
  })().catch((error) => {
    loading = null;
    throw error;
  });
  return loading;
}

/**
 * BlazeFace loaded when the settings need it; the picture's worker told
 * when it is to wait (`announce`, on new settings and BlazeFace not
 * loaded yet), once it runs, and when it could not be.
 */
function loadNeeded(announce, seq) {
  const wanted = needsFace() && !detector ? [loadDetector()] : [];
  if (wanted.length && announce) self.postMessage({ type: "loading" });
  Promise.all(wanted).then(
    () => self.postMessage({ type: "ready", detector: Boolean(detector), seq }),
    (error) => self.postMessage({ type: "failed", message: String((error && error.message) || error) })
  );
}

// --- the light ------------------------------------------------------

/** The light measured on `image`, over the person where `mask` says. */
function measureLight(image, mask) {
  smallCtx.drawImage(image, 0, 0);
  const data = smallCtx.getImageData(0, 0, MASK_WIDTH, MASK_HEIGHT).data;
  if (mask) for (let i = 0; i < maskValues.length; i++) maskValues[i] = mask[i] / 255;
  const m = measure(data, mask ? maskValues : null);
  light = ease(light, correction(m), LIGHT_RATE);
}

// --- the skin -------------------------------------------------------

/**
 * The skin as an ImageBitmap: the face's oval without the eyes, the
 * eyebrows and the mouth (`shapes`, skin.js), the edges softened. Empty
 * without a face.
 */
function drawSkin(shapes) {
  const ctx = skinSharpCtx;
  ctx.globalCompositeOperation = "source-over";
  ctx.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  if (shapes) {
    const trace = (e) => {
      ctx.beginPath();
      ctx.ellipse(e.x, e.y, e.rx, e.ry, e.angle, 0, 2 * Math.PI);
      ctx.fill();
    };
    ctx.fillStyle = "#fff";
    trace(shapes.oval);
    ctx.globalCompositeOperation = "destination-out";
    shapes.holes.forEach(trace);
    ctx.globalCompositeOperation = "source-over";
  }
  skinCtx.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  skinCtx.filter = "blur(5px)";
  skinCtx.drawImage(skinSharp, 0, 0);
  skinCtx.filter = "none";
  return skin.transferToImageBitmap();
}

/** The face found on `image` (512 x 288): `{skin, faceSeen}`. */
function findFace(image, timestamp) {
  const ts = Math.max(timestamp, lastFaceTimestamp + 1);
  lastFaceTimestamp = ts;
  const result = timed("detect", () => detector.detectForVideo(image, ts));
  const found = result && result.detections && result.detections[0];
  const shapes = found ? ellipses(found.keypoints, FACE_WIDTH, FACE_HEIGHT) : null;
  return { skin: timed("draw", () => drawSkin(shapes)), faceSeen: Boolean(shapes) };
}

// --- a frame ----------------------------------------------------------

function timed(name, f) {
  const start = performance.now();
  const value = f();
  spent[name] += performance.now() - start;
  return value;
}

/** The time the models took, on the console every RATE_MS with `log`. */
function told() {
  const now = performance.now();
  if (!since) since = now;
  results++;
  if (now - since >= RATE_MS) {
    const each = (t) => Math.round((t / results) * 10) / 10;
    if (settings.log)
      console.info(
        `[effects] face: ${((results * 1000) / (now - since)).toFixed(1)} results a second; each: ` +
          `BlazeFace ${each(spent.detect)} ms (${delegate}), skin drawn ${each(spent.draw)} ms`
      );
    results = 0;
    since = now;
    spent = { detect: 0, draw: 0 };
  }
}

function run({ small: image, face, timestamp, mask }) {
  const answer = { type: "result", light: null };
  const transfer = [];
  try {
    if (image) {
      measureLight(image, mask);
      answer.light = light;
    }
    if (face && needsFace() && detector) {
      const found = findFace(face, timestamp);
      answer.skin = found.skin;
      answer.faceSeen = found.faceSeen;
      transfer.push(found.skin);
    }
  } catch (error) {
    console.error("[effects] models", error);
  } finally {
    if (image) image.close();
    if (face) face.close();
  }
  told();
  self.postMessage(answer, transfer);
}

self.onmessage = (event) => {
  const data = event.data || {};
  if (data.type === "settings") {
    settings = data.settings || settings;
    loadNeeded(Boolean(data.announce), data.seq);
  } else if (data.type === "frame") {
    run(data);
  }
};
