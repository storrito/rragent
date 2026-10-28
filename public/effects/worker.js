// The webcam's effects, in a worker (rragent.client.effects starts it):
// the person cut out of the picture by MediaPipe's selfie segmenter
// (the landscape model, 256 x 144, beside this file), the background
// behind them blurred or a flat colour, the light of the picture
// corrected (levels.js), and the skin of the face smoothed (skin.js:
// MediaPipe's face landmarker finds the face, a WebGL shader averages
// each skin pixel with the neighbours of its colour). The frames come
// as a stream of VideoFrames from the camera's track
// (MediaStreamTrackProcessor, handed over by the page) and go back as a
// stream into the track the page records and shows
// (MediaStreamTrackGenerator). A worker, not the page: the camera keeps
// sending while the tab is in the background, and a worker keeps
// working then, where the page's animation frames stop.
//
// Messages in: {type: "start", readable, writable, settings} once, then
// {type: "settings", settings} as the person changes them. Settings:
// {background: "off" | "blur" | "green" | "black", autoLight: boolean,
// touchUp: "off" | "subtle" | "smooth"}. Messages out: {type: "ready"}
// once the models the settings need run, {type: "loading"} when new
// settings need a model not loaded yet, {type: "failed", message} when
// one could not be loaded; the frames pass through as they are until
// it is ready, and when it failed. Each model is loaded when a setting
// first asks for it.

import { FilesetResolver, ImageSegmenter, FaceLandmarker } from "./vision_bundle.mjs";
import { measure, correction, ease, filterOf, neutral } from "./levels.js";
import { levels as touchUps, polygons, box } from "./skin.js";

/** The segmenter's input, what the mask comes out as. */
const MASK_WIDTH = 256;
const MASK_HEIGHT = 144;

/** The copy of the frame the face is found on. */
const FACE_WIDTH = 512;
const FACE_HEIGHT = 288;

/** The confidence a pixel needs to be the person: soft between the two. */
const EDGE_LOW = 0.3;
const EDGE_HIGH = 0.7;

/** How much of a new mask shows at once: the edges do not flicker. */
const MASK_RATE = 0.65;

/** The light is measured every so many frames and eased towards. */
const LIGHT_EVERY = 8;
const LIGHT_RATE = 0.12;

const COLORS = { green: "#00ff00", black: "#000000" };

let settings = { background: "off", autoLight: false, touchUp: "off" };
let segmenter = null;
let landmarker = null;
let loads = { segmenter: null, landmarker: null };
let started = false;
let lastTimestamp = 0;
let lastFaceTimestamp = 0;

// the small copy of the frame the segmenter sees and the light is measured on
const small = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const smallCtx = small.getContext("2d", { willReadFrequently: true });
// the mask as a picture, its alpha the person
const maskCanvas = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const maskCtx = maskCanvas.getContext("2d");
const maskImage = maskCtx.createImageData(MASK_WIDTH, MASK_HEIGHT);
const maskValues = new Float32Array(MASK_WIDTH * MASK_HEIGHT);
let maskSeen = false;

// the copy of the frame the face is found on, and the skin as a picture,
// its alpha where the smoothing goes: drawn sharp, then softened
const face = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const faceCtx = face.getContext("2d");
const skinSharp = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const skinSharpCtx = skinSharp.getContext("2d");
const skin = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const skinCtx = skin.getContext("2d");
let faceSeen = false;

// the frame's size: the person, the background and the picture that goes out
let width = 0;
let height = 0;
let out, outCtx, person, personCtx, bg, bgCtx;

let light = neutral;
let frames = 0;

function needsMask() {
  return settings.background !== "off";
}

function needsFace() {
  return Boolean(touchUps[settings.touchUp]);
}

function sizeTo(w, h) {
  if (w === width && h === height) return;
  width = w;
  height = h;
  out = new OffscreenCanvas(w, h);
  outCtx = out.getContext("2d");
  person = new OffscreenCanvas(w, h);
  personCtx = person.getContext("2d");
  // the background at a quarter: blurred small and scaled up, cheap and soft
  bg = new OffscreenCanvas(Math.max(1, Math.round(w / 4)), Math.max(1, Math.round(h / 4)));
  bgCtx = bg.getContext("2d");
  smoother = null;
}

// --- the models -----------------------------------------------------

function fileset() {
  const base = new URL("./", import.meta.url).href;
  return FilesetResolver.forVisionTasks(base + "wasm", true);
}

// MediaPipe takes its wasm's factory from self.ModuleFactory and clears it
// after every task it makes; it sets it by importing the wasm's loader,
// an ES module, which a second import does not run again. So the second
// model, or the CPU attempt after a failed GPU one, found no factory
// ("ModuleFactory not set."). The factory is kept here and set before
// every attempt, one attempt at a time.
let factory = null;
let creating = Promise.resolve();

function withFactory(files, make) {
  const run = creating.then(async () => {
    factory ??= (await import(files.wasmLoaderPath)).default;
    self.ModuleFactory = factory;
    return make();
  });
  creating = run.catch(() => {});
  return run;
}

/** `make(delegate)` tried on the GPU, then on the CPU. */
async function onEither(name, make) {
  try {
    return await make("GPU");
  } catch (error) {
    console.warn(`[effects] no GPU delegate for the ${name}, using the CPU:`, error);
    return await make("CPU");
  }
}

function loadSegmenter() {
  if (!loads.segmenter) {
    const base = new URL("./", import.meta.url).href;
    loads.segmenter = (async () => {
      const files = await fileset();
      segmenter = await onEither("segmenter", (delegate) =>
        withFactory(files, () =>
          ImageSegmenter.createFromOptions(files, {
            baseOptions: { modelAssetPath: base + "selfie_segmenter_landscape.tflite", delegate },
            runningMode: "VIDEO",
            outputConfidenceMasks: true,
            outputCategoryMask: false,
          })
        )
      );
    })().catch((error) => {
      loads.segmenter = null;
      throw error;
    });
  }
  return loads.segmenter;
}

function loadLandmarker() {
  if (!loads.landmarker) {
    const base = new URL("./", import.meta.url).href;
    loads.landmarker = (async () => {
      const files = await fileset();
      landmarker = await onEither("face landmarker", (delegate) =>
        withFactory(files, () =>
          FaceLandmarker.createFromOptions(files, {
            baseOptions: { modelAssetPath: base + "face_landmarker.task", delegate },
            runningMode: "VIDEO",
            numFaces: 1,
            outputFaceBlendshapes: false,
            outputFacialTransformationMatrixes: false,
          })
        )
      );
    })().catch((error) => {
      loads.landmarker = null;
      throw error;
    });
  }
  return loads.landmarker;
}

/**
 * The models the settings need and that do not run yet, loaded; the
 * page told when it is to wait (`announce`, on new settings), when
 * they run, and when one could not be loaded.
 */
function loadNeeded(announce) {
  const wanted = [];
  if (needsMask() && !segmenter) wanted.push(loadSegmenter());
  if (needsFace() && !landmarker) wanted.push(loadLandmarker());
  if (!wanted.length) return;
  if (announce) self.postMessage({ type: "loading" });
  Promise.all(wanted).then(
    () => self.postMessage({ type: "ready" }),
    (error) => self.postMessage({ type: "failed", message: String((error && error.message) || error) })
  );
}

// --- the person -----------------------------------------------------

/** The mask of the small copy into maskValues and maskCanvas. */
function segment(timestamp) {
  if (!segmenter) return;
  // the model wants the time to go forward
  const ts = Math.max(timestamp, lastTimestamp + 1);
  lastTimestamp = ts;
  segmenter.segmentForVideo(small, ts, (result) => {
    const masks = result.confidenceMasks;
    if (!masks || !masks.length) return;
    const values = masks[0].getAsFloat32Array();
    const pixels = maskImage.data;
    const n = MASK_WIDTH * MASK_HEIGHT;
    const rate = maskSeen ? MASK_RATE : 1;
    for (let i = 0; i < n; i++) {
      const soft = Math.min(1, Math.max(0, (values[i] - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)));
      const v = maskValues[i] + (soft - maskValues[i]) * rate;
      maskValues[i] = v;
      pixels[i * 4 + 3] = Math.round(v * 255);
    }
    maskSeen = true;
    maskCtx.putImageData(maskImage, 0, 0);
  });
}

/** The light measured on the small copy, over the person when there is a mask. */
function measureLight() {
  const data = smallCtx.getImageData(0, 0, MASK_WIDTH, MASK_HEIGHT).data;
  const m = measure(data, maskSeen && needsMask() ? maskValues : null);
  light = ease(light, correction(m), LIGHT_RATE);
}

// --- the skin -------------------------------------------------------

/** The face's regions as polygons of landmark indices, from the landmarker's connections. */
const regions = {
  oval: polygons(FaceLandmarker.FACE_LANDMARKS_FACE_OVAL),
  // taken out of the oval, each grown by so much of the face's width
  holes: [
    { polygons: polygons(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE), grow: 0.08 },
    { polygons: polygons(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE), grow: 0.08 },
    { polygons: polygons(FaceLandmarker.FACE_LANDMARKS_LEFT_EYEBROW), grow: 0.05 },
    { polygons: polygons(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYEBROW), grow: 0.05 },
    { polygons: polygons(FaceLandmarker.FACE_LANDMARKS_LIPS), grow: 0.05 },
  ],
};

function trace(ctx, landmarks, polygon) {
  ctx.beginPath();
  polygon.forEach((i, n) => {
    const p = landmarks[i];
    if (n === 0) ctx.moveTo(p.x * FACE_WIDTH, p.y * FACE_HEIGHT);
    else ctx.lineTo(p.x * FACE_WIDTH, p.y * FACE_HEIGHT);
  });
  ctx.closePath();
}

/**
 * The skin of the face at `landmarks` into `skin`: the oval, the eyes,
 * the eyebrows and the lips taken out with a margin, the edges
 * softened. Cleared without a face.
 */
function drawSkin(landmarks) {
  const ctx = skinSharpCtx;
  ctx.globalCompositeOperation = "source-over";
  ctx.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  if (landmarks) {
    const faceWidth = box(landmarks, regions.oval[0]).width * FACE_WIDTH;
    ctx.fillStyle = "#fff";
    ctx.strokeStyle = "#fff";
    ctx.lineJoin = "round";
    for (const polygon of regions.oval) {
      trace(ctx, landmarks, polygon);
      ctx.fill();
    }
    ctx.globalCompositeOperation = "destination-out";
    for (const hole of regions.holes) {
      ctx.lineWidth = Math.max(1, hole.grow * faceWidth);
      for (const polygon of hole.polygons) {
        trace(ctx, landmarks, polygon);
        ctx.fill();
        ctx.stroke();
      }
    }
    ctx.globalCompositeOperation = "source-over";
  }
  skinCtx.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  skinCtx.filter = "blur(3px)";
  skinCtx.drawImage(skinSharp, 0, 0);
  skinCtx.filter = "none";
}

/** The face found on the frame's copy; the skin drawn. */
function findFace(timestamp) {
  if (!landmarker) return;
  const ts = Math.max(timestamp, lastFaceTimestamp + 1);
  lastFaceTimestamp = ts;
  const result = landmarker.detectForVideo(face, ts);
  const landmarks = result && result.faceLandmarks && result.faceLandmarks[0];
  drawSkin(landmarks && landmarks.length ? landmarks : null);
  faceSeen = Boolean(landmarks && landmarks.length);
}

// --- the smoothing, on the GPU ---------------------------------------

const VERTEX = `#version 300 es
in vec2 a_pos;
out vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

// a bilateral filter: each skin pixel the average of the neighbours
// near it in place and in colour, so that a pore or a shadow of the
// skin fades while an eyelash or the edge of the face stays sharp
const FRAGMENT = `#version 300 es
precision mediump float;
uniform sampler2D u_frame;
uniform sampler2D u_skin;
uniform vec2 u_texel;
uniform float u_step;
uniform float u_range;
uniform float u_strength;
in vec2 v_uv;
out vec4 o_color;
void main() {
  vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y);
  vec4 c = texture(u_frame, uv);
  float m = texture(u_skin, uv).a * u_strength;
  if (m < 0.01) { o_color = c; return; }
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  float inv = 1.0 / (2.0 * u_range * u_range);
  for (int y = -3; y <= 3; y++) {
    for (int x = -3; x <= 3; x++) {
      vec2 d = vec2(float(x), float(y));
      vec3 s = texture(u_frame, uv + d * u_step * u_texel).rgb;
      vec3 diff = s - c.rgb;
      float w = exp(-dot(d, d) / 8.0) * exp(-dot(diff, diff) * inv);
      sum += s * w;
      weights += w;
    }
  }
  o_color = vec4(mix(c.rgb, sum / weights, m), 1.0);
}`;

let smoother = null;
let smootherFailed = false;
// the frame drawn here when the GPU does not take a VideoFrame as it is
let copy = null;
let copyCtx = null;

function shader(gl, type, source) {
  const s = gl.createShader(type);
  gl.shaderSource(s, source);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    throw new Error("the shader did not compile: " + gl.getShaderInfoLog(s));
  }
  return s;
}

function texture(gl, unit) {
  const t = gl.createTexture();
  gl.activeTexture(gl.TEXTURE0 + unit);
  gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  return t;
}

/** The canvas and the program that smooth a frame of the current size. */
function makeSmoother() {
  const canvas = new OffscreenCanvas(width, height);
  const gl = canvas.getContext("webgl2", { premultipliedAlpha: false, antialias: false });
  if (!gl) throw new Error("no WebGL 2");
  const program = gl.createProgram();
  gl.attachShader(program, shader(gl, gl.VERTEX_SHADER, VERTEX));
  gl.attachShader(program, shader(gl, gl.FRAGMENT_SHADER, FRAGMENT));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error("the program did not link: " + gl.getProgramInfoLog(program));
  }
  gl.useProgram(program);
  // one triangle over the whole canvas
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const pos = gl.getAttribLocation(program, "a_pos");
  gl.enableVertexAttribArray(pos);
  gl.vertexAttribPointer(pos, 2, gl.FLOAT, false, 0, 0);
  const frameTexture = texture(gl, 0);
  const skinTexture = texture(gl, 1);
  gl.uniform1i(gl.getUniformLocation(program, "u_frame"), 0);
  gl.uniform1i(gl.getUniformLocation(program, "u_skin"), 1);
  gl.uniform2f(gl.getUniformLocation(program, "u_texel"), 1 / width, 1 / height);
  gl.viewport(0, 0, width, height);
  return {
    canvas,
    gl,
    frameTexture,
    skinTexture,
    step: gl.getUniformLocation(program, "u_step"),
    range: gl.getUniformLocation(program, "u_range"),
    strength: gl.getUniformLocation(program, "u_strength"),
  };
}

function upload(gl, frame) {
  try {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, frame);
  } catch (_error) {
    if (!copy || copy.width !== width || copy.height !== height) {
      copy = new OffscreenCanvas(width, height);
      copyCtx = copy.getContext("2d");
    }
    copyCtx.drawImage(frame, 0, 0, width, height);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, copy);
  }
}

/**
 * The frame with its skin smoothed by the level of the settings, as a
 * canvas; the frame itself when there is no face, or no GPU.
 */
function smooth(frame) {
  const level = touchUps[settings.touchUp];
  if (!level || !faceSeen || smootherFailed) return frame;
  if (!smoother) {
    try {
      smoother = makeSmoother();
    } catch (error) {
      smootherFailed = true;
      console.warn("[effects] no smoothing:", error);
      return frame;
    }
  }
  const { gl } = smoother;
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, smoother.frameTexture);
  upload(gl, frame);
  gl.activeTexture(gl.TEXTURE1);
  gl.bindTexture(gl.TEXTURE_2D, smoother.skinTexture);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, skin);
  gl.uniform1f(smoother.step, level.step * (width / 1280));
  gl.uniform1f(smoother.range, level.range);
  gl.uniform1f(smoother.strength, level.strength);
  gl.drawArrays(gl.TRIANGLES, 0, 3);
  return smoother.canvas;
}

// --- the picture that goes out ----------------------------------------

/** `source` is the frame, or the frame with its skin smoothed. */
function compose(source) {
  const filter = settings.autoLight ? filterOf(light) : "none";
  outCtx.filter = "none";
  outCtx.globalCompositeOperation = "source-over";
  if (!needsMask() || !maskSeen) {
    outCtx.filter = filter;
    outCtx.drawImage(source, 0, 0, width, height);
    outCtx.filter = "none";
    return;
  }
  // the background
  if (settings.background === "blur") {
    bgCtx.filter = "blur(6px)";
    bgCtx.drawImage(source, 0, 0, bg.width, bg.height);
    bgCtx.filter = "none";
    outCtx.filter = filter;
    outCtx.drawImage(bg, 0, 0, width, height);
    outCtx.filter = "none";
  } else {
    outCtx.fillStyle = COLORS[settings.background] || COLORS.black;
    outCtx.fillRect(0, 0, width, height);
  }
  // the person: the frame, kept where the mask is, its edge softened
  personCtx.globalCompositeOperation = "source-over";
  personCtx.filter = filter;
  personCtx.clearRect(0, 0, width, height);
  personCtx.drawImage(source, 0, 0, width, height);
  personCtx.filter = "blur(2px)";
  personCtx.globalCompositeOperation = "destination-in";
  personCtx.drawImage(maskCanvas, 0, 0, width, height);
  personCtx.filter = "none";
  personCtx.globalCompositeOperation = "source-over";
  outCtx.drawImage(person, 0, 0);
}

function transform(frame, controller) {
  // something to do with a model that runs, or the light
  const can = (needsMask() && segmenter) || settings.autoLight || (needsFace() && landmarker);
  if (!can) {
    controller.enqueue(frame);
    return;
  }
  try {
    const w = frame.displayWidth || frame.codedWidth;
    const h = frame.displayHeight || frame.codedHeight;
    sizeTo(w, h);
    const timestamp = frame.timestamp / 1000;
    smallCtx.drawImage(frame, 0, 0, MASK_WIDTH, MASK_HEIGHT);
    if (needsMask() && segmenter) segment(timestamp);
    if (settings.autoLight && frames++ % LIGHT_EVERY === 0) measureLight();
    let source = frame;
    if (needsFace() && landmarker) {
      faceCtx.drawImage(frame, 0, 0, FACE_WIDTH, FACE_HEIGHT);
      findFace(timestamp);
      source = smooth(frame);
    }
    compose(source);
    const made = new VideoFrame(out, { timestamp: frame.timestamp, alpha: "discard" });
    frame.close();
    controller.enqueue(made);
  } catch (error) {
    console.error("[effects]", error);
    controller.enqueue(frame);
  }
}

function start(readable, writable) {
  if (!started) {
    started = true;
    loadNeeded(false);
  }
  readable
    .pipeThrough(new TransformStream({ transform }))
    .pipeTo(writable)
    .catch((error) => console.warn("[effects] the stream ended:", error));
}

self.onmessage = (event) => {
  const data = event.data || {};
  if (data.type === "start") {
    settings = data.settings || settings;
    start(data.readable, data.writable);
  } else if (data.type === "settings") {
    settings = data.settings || settings;
    if (!needsMask()) maskSeen = false;
    if (!needsFace()) faceSeen = false;
    if (started) loadNeeded(true);
  }
};
