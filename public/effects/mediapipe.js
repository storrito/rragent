// Loading MediaPipe's tasks in a worker of the webcam's effects (worker.js
// makes the segmenter, models.js BlazeFace): the fileset of the wasm
// beside this file, one task made at a time, on the GPU, else on the
// CPU.

import { FilesetResolver } from "./vision_bundle.mjs";

/** The wasm of MediaPipe's vision tasks, beside this file. */
export function fileset() {
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

/** `make()` once the wasm's factory is set, after any task being made. */
export function withFactory(files, make) {
  const run = creating.then(async () => {
    factory ??= (await import(files.wasmLoaderPath)).default;
    self.ModuleFactory = factory;
    return make();
  });
  creating = run.catch(() => {});
  return run;
}

/**
 * `make(delegate)` tried on the GPU, then on the CPU. On the CPU (WASM)
 * the segmenter took 50 to 80 ms a frame here, on the GPU far less.
 */
export async function onEither(name, make) {
  try {
    return await make("GPU");
  } catch (error) {
    console.warn(`[effects] no GPU delegate for the ${name}, using the CPU:`, error);
    return await make("CPU");
  }
}

/** Where the models lie: beside this file. */
export const base = new URL("./", import.meta.url).href;
