// The webcam's effects, in a worker (rragent.client.effects starts it):
// the person cut out of the picture, the background behind them blurred
// or a flat colour, the light of the picture corrected (levels.js), and
// the skin of the face smoothed (a shader averages each skin pixel with
// the neighbours of its colour). The picture is made here, on the GPU in
// one WebGL 2 context (gpu.js), which MediaPipe's selfie segmenter (the
// landscape model, 256 x 144, beside this file) shares: every frame gets
// its own mask, which stays a texture and is never read back, as Google
// Meet keeps it on the GPU; read back, it cost about 20 ms a frame. The
// face is found in a worker of its own (models.js, BlazeFace), which gets a small copy of a frame whenever it is free; every
// frame is laid over the last skin that came back, a frame or two old,
// so that the picture never waits for it. The frames come as a
// stream of VideoFrames from the camera's track
// (MediaStreamTrackProcessor, handed over by the page) and go back as a
// stream into the track the page records and shows
// (MediaStreamTrackGenerator). A worker, not the page: the camera keeps
// sending while the tab is in the background, and a worker keeps working
// then, where the page's animation frames stop.
//
// Messages in: {type: "start", readable, writable, settings} once, then
// {type: "settings", settings} as the person changes them, and
// {type: "preview", every} while the webcam records as it is (its
// effects come after): only every so many frames of the preview are
// made then, the others dropped, which leaves the computer to the
// recording. Or, for a recorded file (rragent.client.effects'
// apply-to-file!), {type: "offline", settings} once, then
// {type: "frame", id, frame} for each frame, answered {type: "frame",
// id, frame} with the frame made (the one sent closed), or {type:
// "frame", id, error} when it could not be; each frame waits for its own
// skin there. Settings: {background: "off" | "blur" | "green"
// | "black", autoLight: boolean, touchUp: "off" | "subtle" | "smooth",
// log: boolean, the time of each step on the console every second}.
// Messages out: {type: "ready"} once the models the settings need run,
// {type: "loading"} when new settings need a model not loaded yet,
// {type: "failed", message} when one could not be loaded; the frames
// pass through as they are until it is ready, and when it failed. Each
// model is loaded when a setting first asks for it. {type: "rate",
// frames, ms, stages} every second: how many frames went out in how
// long, for the panel and recording.json, and the milliseconds a frame
// spent in each step on average (`stages`), on the console too with
// `log`.

import { ImageSegmenter } from "./vision_bundle.mjs";
import { fileset, withFactory, onEither, base } from "./mediapipe.js";
import { neutral } from "./levels.js";
import { levels as touchUps } from "./skin.js";
import { compositor } from "./gpu.js";

/** The segmenter's input, what the mask comes out as. */
const MASK_WIDTH = 256;
const MASK_HEIGHT = 144;

/** The copy of the frame the face is found on (models.js). */
const FACE_WIDTH = 512;
const FACE_HEIGHT = 288;

/** The confidence a pixel needs to be the person: soft between the two. */
const EDGE_LOW = 0.3;
const EDGE_HIGH = 0.7;

/** How much of a new mask shows at once: the edges do not flicker. */
const MASK_RATE = 0.65;

/** The light is measured on every so many copies the models get. */
const LIGHT_EVERY = 8;

/**
 * The face is looked for on every so many copies the models get: it
 * moves little in a tenth of a second, and the skin found last stays
 * meanwhile.
 */
const FACE_EVERY = 3;

/** The flat backgrounds, as RGB 0..1: the green is the one to key out. */
const COLORS = { green: [0, 1, 0], black: [0, 0, 0] };

/** How long a recorded file's frame waits for the face's worker at most. */
const MODELS_WAIT_MS = 10000;

/** How often the frames that went out are told. */
const RATE_MS = 1000;

/**
 * Whether the segmenter's mask texture has the picture's bottom as its
 * first row, as GL draws; the mask pass turns it over then.
 */
const MASK_FLIPPED = false;

let settings = { background: "off", autoLight: false, touchUp: "off" };
let started = false;
// a recorded file's frames, not the camera's: each waits for its own skin
let offline = false;

// the canvas of the picture, whose WebGL context the segmenter shares,
// and what draws on it
const canvas = new OffscreenCanvas(1, 1);
let gpu = null;
let gpuFailed = false;
let width = 0;
let height = 0;

// the segmenter, here
let segmenter = null;
let segmenterLoading = null;
let lastTimestamp = 0;
let maskOnGpu = null;

// the face's worker, whether its BlazeFace runs, and what came back last
const models = new Worker(new URL("./models.js", import.meta.url), { type: "module" });
let detector = false;
let modelsPending = false;
// the settings told the face's worker last: its "ready" for older ones is not theirs
let settingsSeq = 0;
let busy = false;
let waiting = null;
let skin = null;
let skinNew = false;
let faceSeen = false;
let light = neutral;
let lightTick = 0;
let faceTick = 0;

// the copies of a frame for the face's worker, handed over as ImageBitmaps
const small = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const smallCtx = small.getContext("2d");
const face = new OffscreenCanvas(FACE_WIDTH, FACE_HEIGHT);
const faceCtx = face.getContext("2d");

// the frames that went out since the rate was last told, the skins that
// came back meanwhile, and the time spent in each step
let rateFrames = 0;
let rateSince = 0;
let skinsBack = 0;
let stages = { segmenter: 0, copies: 0, picture: 0 };

/** `f()`, its milliseconds added to the step `name`. */
function timed(name, f) {
  const start = performance.now();
  const value = f();
  stages[name] += performance.now() - start;
  return value;
}

function needsMask() {
  return settings.background !== "off";
}

function needsFace() {
  return Boolean(touchUps[settings.touchUp]);
}

/** The compositor; null, the page told once, without WebGL 2. */
function compositorOf() {
  if (!gpu && !gpuFailed) {
    try {
      gpu = compositor(canvas, MASK_WIDTH, MASK_HEIGHT);
    } catch (error) {
      gpuFailed = true;
      console.warn("[effects] no WebGL 2:", error);
      self.postMessage({ type: "failed", message: String((error && error.message) || error) });
    }
  }
  return gpu;
}

// --- loading --------------------------------------------------------

/**
 * The segmenter, made on the picture's canvas: its masks are textures of
 * the picture's context.
 */
function loadSegmenter() {
  segmenterLoading ??= (async () => {
    const files = await fileset();
    segmenter = await onEither("segmenter", (delegate) =>
      withFactory(files, () =>
        ImageSegmenter.createFromOptions(files, {
          baseOptions: { modelAssetPath: base + "selfie_segmenter_landscape.tflite", delegate },
          canvas,
          runningMode: "VIDEO",
          outputConfidenceMasks: true,
          outputCategoryMask: false,
        })
      )
    );
  })().catch((error) => {
    segmenterLoading = null;
    throw error;
  });
  return segmenterLoading;
}

/**
 * The models the settings need, loaded: the segmenter here, the
 * face's BlazeFace in its worker; the page told when it is to wait
 * (`announce`, on new settings and a model not loaded yet), once they
 * run, and when one could not be loaded.
 */
function loadNeeded(announce) {
  const loadingHere = needsMask() && !segmenter;
  modelsPending = true;
  settingsSeq++;
  models.postMessage({ type: "settings", settings, announce: announce && !loadingHere, seq: settingsSeq });
  if (loadingHere && announce) self.postMessage({ type: "loading" });
  (needsMask() ? loadSegmenter() : Promise.resolve()).then(
    () => maybeReady(),
    (error) => self.postMessage({ type: "failed", message: String((error && error.message) || error) })
  );
}

/** The page told it is ready once both workers run what the settings need. */
function maybeReady() {
  if (!modelsPending && (!needsMask() || segmenter)) self.postMessage({ type: "ready" });
}

models.onmessage = (event) => {
  const data = event.data || {};
  if (data.type === "result") {
    busy = false;
    skinsBack++;
    if (data.skin !== undefined) {
      if (skin) skin.close();
      skin = data.skin;
      faceSeen = data.faceSeen;
      skinNew = true;
    }
    if (data.light) light = data.light;
    if (waiting) {
      const done = waiting;
      waiting = null;
      done();
    }
  } else if (data.type === "ready") {
    detector = data.detector;
    if (data.seq !== settingsSeq) return;
    modelsPending = false;
    maybeReady();
  } else {
    // loading, failed: the page's
    self.postMessage(data);
  }
};

models.onerror = (event) => {
  self.postMessage({ type: "failed", message: (event && event.message) || "the face's worker did not start" });
};

// --- a frame --------------------------------------------------------

/** The mask of `frame`, on the GPU. */
function segment(g, frame) {
  // the model wants the time to go forward
  const ts = Math.max(frame.timestamp / 1000, lastTimestamp + 1);
  lastTimestamp = ts;
  segmenter.segmentForVideo(frame, ts, (result) => {
    const masks = result.confidenceMasks;
    if (!masks || !masks.length) return;
    const options = { low: EDGE_LOW, high: EDGE_HIGH, rate: MASK_RATE, flip: MASK_FLIPPED };
    const onGpu = masks[0].hasWebGLTexture();
    if (onGpu !== maskOnGpu) {
      maskOnGpu = onGpu;
      if (settings.log) console.info(onGpu ? "[effects] the mask stays on the GPU" : "[effects] the mask comes from the CPU");
    }
    if (onGpu) {
      g.maskFrom(masks[0].getAsWebGLTexture(), options);
    } else {
      // on the CPU: the confidences as bytes
      const values = masks[0].getAsFloat32Array();
      const bytes = new Uint8Array(values.length);
      for (let i = 0; i < values.length; i++) bytes[i] = Math.round(Math.min(1, Math.max(0, values[i])) * 255);
      g.maskFromBytes(bytes, options);
    }
  });
}

/** Copies of `frame` to the face's worker, which is free; whether any went. */
function toModels(g, frame) {
  const message = { type: "frame", timestamp: frame.timestamp / 1000, small: null, face: null, mask: null };
  const transfer = [];
  timed("copies", () => {
    if (settings.autoLight && lightTick++ % LIGHT_EVERY === 0) {
      smallCtx.drawImage(frame, 0, 0, MASK_WIDTH, MASK_HEIGHT);
      message.small = small.transferToImageBitmap();
      transfer.push(message.small);
      if (needsMask()) message.mask = g.readMask();
    }
    if (needsFace() && detector && (offline || faceTick++ % FACE_EVERY === 0 || !faceSeen)) {
      faceCtx.drawImage(frame, 0, 0, FACE_WIDTH, FACE_HEIGHT);
      message.face = face.transferToImageBitmap();
      transfer.push(message.face);
    }
  });
  if (!transfer.length) return false;
  busy = true;
  models.postMessage(message, transfer);
  return true;
}

/** The frames that went out, told every RATE_MS. */
function counted() {
  const now = performance.now();
  if (!rateSince) rateSince = now;
  rateFrames++;
  if (now - rateSince >= RATE_MS) {
    const ms = now - rateSince;
    const each = {};
    for (const [name, total] of Object.entries(stages)) each[name] = Math.round((total / rateFrames) * 10) / 10;
    self.postMessage({ type: "rate", frames: rateFrames, ms, stages: each });
    const gpuMs = gpu ? gpu.gpuTime() : null;
    const dropped = gpu ? gpu.readsDropped() : 0;
    if (settings.log) {
      const glError = gpu ? gpu.error() : null;
      console.info(
        `[effects] ${((rateFrames * 1000) / ms).toFixed(1)} fps, ${((skinsBack * 1000) / ms).toFixed(1)} skins a second; a frame: ` +
          Object.entries(each).map(([name, t]) => `${name} ${t} ms`).join(", ") +
          (gpuMs === null ? "" : `, gpu ${gpuMs} ms`) +
          (dropped ? `, ${dropped} not read back (the GPU behind)` : "") +
          (glError ? `, WebGL error ${glError}` : "")
      );
    }
    rateFrames = 0;
    skinsBack = 0;
    rateSince = now;
    stages = { segmenter: 0, copies: 0, picture: 0 };
  }
}

/** Whether there is something to do: a model that runs, or the light. */
function active() {
  return (needsMask() && segmenter) || settings.autoLight || (needsFace() && detector);
}

/**
 * The frame's size; the compositor's passes made for it. Null without
 * a compositor.
 */
function prepared(frame) {
  const g = compositorOf();
  if (!g) return null;
  width = frame.displayWidth || frame.codedWidth;
  height = frame.displayHeight || frame.codedHeight;
  g.resize(width, height);
  return g;
}

/**
 * The picture of `frame` with its mask and the last skin, `frame`
 * closed: live (`read`), the pictures of the frames before whose copies
 * into memory are done (none, one, now and then two), else a VideoFrame
 * of the canvas; `[frame]` itself on an error, live; a recorded file's
 * frame throws instead, as its raw picture must not go up.
 */
function draw(g, frame, read) {
  try {
    if (needsMask() && segmenter) timed("segmenter", () => segment(g, frame));
    if (skinNew && skin) {
      g.skin(skin);
      skinNew = false;
    }
    const level = touchUps[settings.touchUp];
    const made = timed("picture", () => {
      // the pictures done taken before the next is read into a buffer
      const done = read ? g.pictures() : null;
      g.draw(frame, {
        background: needsMask() && g.maskSeen ? settings.background : "off",
        color: COLORS[settings.background],
        light: settings.autoLight ? light : neutral,
        touchUp: level && faceSeen && needsFace() && skin ? level : null,
        read,
        timestamp: frame.timestamp,
      });
      return read ? done : [new VideoFrame(canvas, { timestamp: frame.timestamp, alpha: "discard" })];
    });
    frame.close();
    return made;
  } catch (error) {
    console.error("[effects]", error);
    if (offline) {
      frame.close();
      throw error;
    }
    return [frame];
  }
}

/**
 * A live frame: copies to the face's worker when it is free, the
 * picture drawn and read back; the frames to send on.
 */
function paint(frame) {
  if (!active()) {
    if (gpu) gpu.forget();
    return [frame];
  }
  const g = prepared(frame);
  if (!g) return [frame];
  if (!busy) toModels(g, frame);
  return draw(g, frame, true);
}

/** A frame of a recorded file: its own skin waited for, then the picture. */
async function paintWaiting(frame) {
  counted();
  const g = active() ? prepared(frame) : null;
  if (!g) {
    frame.close();
    throw new Error("the effects do not run");
  }
  const back = new Promise((resolve, reject) => {
    waiting = resolve;
    setTimeout(() => reject(new Error("the face's worker did not answer")), MODELS_WAIT_MS);
  });
  try {
    if (toModels(g, frame)) await back;
  } catch (error) {
    frame.close();
    throw error;
  } finally {
    waiting = null;
  }
  return draw(g, frame, false)[0];
}

// while the webcam records as it is, only every so many frames of the preview
let previewEvery = 1;
let previewTick = 0;

function transform(frame, controller) {
  if (previewEvery > 1 && previewTick++ % previewEvery !== 0) {
    frame.close();
    return;
  }
  for (const made of paint(frame)) {
    counted();
    controller.enqueue(made);
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

// the frames of a recorded file, one after the other
let frames = Promise.resolve();

self.onmessage = (event) => {
  const data = event.data || {};
  if (data.type === "start") {
    settings = data.settings || settings;
    start(data.readable, data.writable);
  } else if (data.type === "settings") {
    settings = data.settings || settings;
    if (!needsFace()) faceSeen = false;
    if (!needsMask() && gpu) gpu.forgetMask();
    if (started) loadNeeded(true);
  } else if (data.type === "preview") {
    previewEvery = Math.max(1, data.every || 1);
  } else if (data.type === "offline") {
    settings = data.settings || settings;
    started = true;
    offline = true;
    loadNeeded(false);
  } else if (data.type === "frame") {
    frames = frames.then(async () => {
      try {
        const made = await paintWaiting(data.frame);
        self.postMessage({ type: "frame", id: data.id, frame: made }, [made]);
      } catch (error) {
        self.postMessage({ type: "frame", id: data.id, error: String((error && error.message) || error) });
      }
    });
  }
};
