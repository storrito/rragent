import cljf from 'clojurific/vite';

// The page's bundle of a motion project: rragent.motion.client, the
// rframes client, compiled for the browser into templates/motion/dist/public,
// which the project's process serves under assets/ next to its page. The
// manifest names the bundle's scripts, rragent.motion.app reads it. Run
// from rragent's root after vite.config.mjs beside this file.
export default {
  publicDir: false,
  plugins: [
    cljf({
      outputDir: 'target/motion-client',
      compilerOptions: { main: "'rragent.motion.client" },
      manifest: 'manifest.json',
    }),
  ],
  build: {
    outDir: 'templates/motion/dist/public',
    emptyOutDir: true,
    rolldownOptions: { input: {} },
  },
};
