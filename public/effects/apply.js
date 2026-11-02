// The webcam's effects put on a recorded file, frame after frame, with no
// hurry: after a recording, when the settings say so
// (rragent.client.effects' apply-to-file!). Every
// frame goes through a worker of its own (worker.js, {type: "offline"}),
// the file is read, encoded anew and written in order by Mediabunny
// (WebCodecs; its bundle beside this file, copied from node_modules by
// the build), the sound kept as it is where the container takes it.

import { Input, Output, BlobSource, StreamTarget, Conversion, VideoSample, ALL_FORMATS, Mp4OutputFormat, WebMOutputFormat } from "./mediabunny.mjs";

/** Mediabunny's codec for a recorder's mime: H.264 for MP4, VP9 where it says so, VP8 otherwise. */
export function codecOf(mime) {
  const m = String(mime || "");
  if (m.startsWith("video/mp4")) return "avc";
  if (m.includes("vp9")) return "vp9";
  return "vp8";
}

/**
 * A worker with its models loaded for `settings`: `paint(frame)` answers
 * the promise of the frame made, the one given closed.
 */
async function offlineWorker(settings) {
  const worker = new Worker(new URL("./worker.js", import.meta.url), { type: "module" });
  const waiting = new Map();
  let nextId = 0;
  await new Promise((resolve, reject) => {
    worker.onmessage = (event) => {
      const data = event.data || {};
      if (data.type === "ready") resolve();
      else if (data.type === "failed") reject(new Error(data.message));
      else if (data.type === "frame") {
        const done = waiting.get(data.id);
        waiting.delete(data.id);
        if (done) done(data.frame);
      }
    };
    worker.onerror = (event) => reject(new Error((event && event.message) || "the worker did not start"));
    worker.postMessage({ type: "offline", settings });
  });
  return {
    worker,
    paint(frame) {
      const id = ++nextId;
      return new Promise((resolve) => {
        waiting.set(id, resolve);
        worker.postMessage({ type: "frame", id, frame }, [frame]);
      });
    },
  };
}

/**
 * The effects of `settings` (the worker's: {background, autoLight,
 * touchUp}) put on `file` (a Blob), encoded anew as `codec` ("vp8",
 * "vp9", "avc") at `bits`; the new file written in order, `write(bytes)`
 * called with each piece and waited for; `onProgress(0..1)`. Answers
 * {frames, seconds} of the file made.
 */
export async function applyToFile(file, settings, { codec, bits, write, onProgress }) {
  const { worker, paint } = await offlineWorker(settings);
  let frames = 0;
  let written = 0;
  try {
    const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
    const target = new StreamTarget(
      new WritableStream({
        write(chunk) {
          if (chunk.position !== written) throw new Error("the new webcam file was not written in order");
          written += chunk.data.byteLength;
          return write(chunk.data);
        },
      }),
      { chunked: true, chunkSize: 1024 * 1024 }
    );
    const output = new Output({
      format: codec === "avc" ? new Mp4OutputFormat({ fastStart: "fragmented" }) : new WebMOutputFormat({ appendOnly: true }),
      target,
    });
    const conversion = await Conversion.init({
      input,
      output,
      video: {
        codec,
        bitrate: bits,
        forceTranscode: true,
        process: async (sample) => {
          frames++;
          return new VideoSample(await paint(sample.toVideoFrame()));
        },
      },
    });
    if (!conversion.isValid) {
      throw new Error("the webcam's file cannot be converted: " + conversion.discardedTracks.map((t) => t.reason).join(", "));
    }
    if (onProgress) conversion.onProgress = (progress) => onProgress(progress);
    await conversion.execute();
    return { frames, seconds: await input.computeDuration() };
  } finally {
    worker.terminate();
  }
}
