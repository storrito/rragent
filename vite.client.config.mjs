import { cpSync, mkdirSync } from 'node:fs';
import cljf from 'clojurific/vite';

// The webcam's effects (rragent.client.effects, public/effects/worker.js)
// run MediaPipe in a worker: its bundle and its wasm (the module build,
// the one a module worker imports) come from node_modules into
// public/effects beside the worker, the model and the levels, which Vite
// copies from public/. Served by the server at /effects/* (rragent.page).
const mediapipe = 'node_modules/@mediapipe/tasks-vision';
const effects = {
  name: 'rragent-effects',
  closeBundle() {
    mkdirSync('dist/public/effects/wasm', { recursive: true });
    cpSync(`${mediapipe}/vision_bundle.mjs`, 'dist/public/effects/vision_bundle.mjs');
    for (const file of ['vision_wasm_module_internal.js', 'vision_wasm_module_internal.wasm']) {
      cpSync(`${mediapipe}/wasm/${file}`, `dist/public/effects/wasm/${file}`);
    }
  },
};

// The page's bundle: rragent.client compiled for the browser, built into
// dist/public with public/app.css, which the server serves. The manifest
// names the bundle's scripts by namespace, rragent.assets reads it. The
// dev server of vite.config.mjs serves the same in development.
export default {
  plugins: [
    cljf({
      outputDir: 'target/client',
      compilerOptions: { main: "'rragent.client" },
      manifest: 'manifest.json',
    }),
    effects,
  ],

  build: {
    outDir: 'dist/public',
    // the namespace is the input, the plugin adds it
    rolldownOptions: { input: {} },
  },
};
