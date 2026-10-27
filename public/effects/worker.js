// The webcam's effects, in a worker (rragent.client.effects starts it):
// the person cut out of the picture by MediaPipe's selfie segmenter
// (the landscape model, 256 x 144, beside this file), the background
// behind them blurred or a flat colour, and the light of the picture
// corrected (levels.js). The frames come as a stream of VideoFrames
// from the camera's track (MediaStreamTrackProcessor, handed over by
// the page) and go back as a stream into the track the page records
// and shows (MediaStreamTrackGenerator). A worker, not the page: the
// camera keeps sending while the tab is in the background, and a
// worker keeps working then, where the page's animation frames stop.
//
// Messages in: {type: "start", readable, writable, settings} once, then
// {type: "settings", settings} as the person changes them. Settings:
// {background: "off" | "blur" | "green" | "black", autoLight: boolean}.
// Messages out: {type: "ready"} once the model runs, {type: "failed",
// message} when it could not be loaded; the frames pass through as
// they are until it is ready, and when it failed.

import { FilesetResolver, ImageSegmenter } from "./vision_bundle.mjs";
import { measure, correction, ease, filterOf, neutral } from "./levels.js";

/** The model's input, what the mask comes out as. */
const MASK_WIDTH = 256;
const MASK_HEIGHT = 144;

/** The confidence a pixel needs to be the person: soft between the two. */
const EDGE_LOW = 0.3;
const EDGE_HIGH = 0.7;

/** How much of a new mask shows at once: the edges do not flicker. */
const MASK_RATE = 0.65;

/** The light is measured every so many frames and eased towards. */
const LIGHT_EVERY = 8;
const LIGHT_RATE = 0.12;

const COLORS = { green: "#00ff00", black: "#000000" };

let settings = { background: "off", autoLight: false };
let segmenter = null;
let segmenting = false;
let lastTimestamp = 0;

// the small copy of the frame the model sees and the light is measured on
const small = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const smallCtx = small.getContext("2d", { willReadFrequently: true });
// the mask as a picture, its alpha the person
const maskCanvas = new OffscreenCanvas(MASK_WIDTH, MASK_HEIGHT);
const maskCtx = maskCanvas.getContext("2d");
const maskImage = maskCtx.createImageData(MASK_WIDTH, MASK_HEIGHT);
const maskValues = new Float32Array(MASK_WIDTH * MASK_HEIGHT);
let maskSeen = false;

// the frame's size: the person, the background and the picture that goes out
let width = 0;
let height = 0;
let out, outCtx, person, personCtx, bg, bgCtx;

let light = neutral;
let frames = 0;

function needsMask() {
  return settings.background !== "off";
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
}

async function load() {
  const base = new URL("./", import.meta.url).href;
  const fileset = await FilesetResolver.forVisionTasks(base + "wasm", true);
  const options = (delegate) => ({
    baseOptions: { modelAssetPath: base + "selfie_segmenter_landscape.tflite", delegate },
    runningMode: "VIDEO",
    outputConfidenceMasks: true,
    outputCategoryMask: false,
  });
  try {
    segmenter = await ImageSegmenter.createFromOptions(fileset, options("GPU"));
  } catch (error) {
    console.warn("[effects] no GPU delegate, using the CPU:", error);
    segmenter = await ImageSegmenter.createFromOptions(fileset, options("CPU"));
  }
}

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

function compose(frame) {
  const filter = settings.autoLight ? filterOf(light) : "none";
  outCtx.filter = "none";
  outCtx.globalCompositeOperation = "source-over";
  if (!needsMask() || !maskSeen) {
    outCtx.filter = filter;
    outCtx.drawImage(frame, 0, 0, width, height);
    outCtx.filter = "none";
    return;
  }
  // the background
  if (settings.background === "blur") {
    bgCtx.filter = "blur(6px)";
    bgCtx.drawImage(frame, 0, 0, bg.width, bg.height);
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
  personCtx.drawImage(frame, 0, 0, width, height);
  personCtx.filter = "blur(2px)";
  personCtx.globalCompositeOperation = "destination-in";
  personCtx.drawImage(maskCanvas, 0, 0, width, height);
  personCtx.filter = "none";
  personCtx.globalCompositeOperation = "source-over";
  outCtx.drawImage(person, 0, 0);
}

function transform(frame, controller) {
  const active = needsMask() || settings.autoLight;
  if (!active || (!segmenter && needsMask() && !settings.autoLight)) {
    controller.enqueue(frame);
    return;
  }
  try {
    const w = frame.displayWidth || frame.codedWidth;
    const h = frame.displayHeight || frame.codedHeight;
    sizeTo(w, h);
    smallCtx.drawImage(frame, 0, 0, MASK_WIDTH, MASK_HEIGHT);
    if (needsMask() && segmenter) segment(frame.timestamp / 1000);
    if (settings.autoLight && frames++ % LIGHT_EVERY === 0) measureLight();
    compose(frame);
    const made = new VideoFrame(out, { timestamp: frame.timestamp, alpha: "discard" });
    frame.close();
    controller.enqueue(made);
  } catch (error) {
    console.error("[effects]", error);
    controller.enqueue(frame);
  }
}

function start(readable, writable) {
  if (!segmenting) {
    segmenting = true;
    load().then(
      () => self.postMessage({ type: "ready" }),
      (error) => {
        segmenter = null;
        self.postMessage({ type: "failed", message: String((error && error.message) || error) });
      }
    );
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
  }
};
