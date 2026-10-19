import cljf from 'clojurific/vite';

// The motion template's program, a Node.js program of its own:
// rragent.motion.main compiled from rragent's sources and the template's
// (templates/motion/src, on the classpath of the root's deps.edn) and
// bundled into templates/motion/dist/motion.js, which the template's
// `serve` and `render` executables import. Run from rragent's root:
// vite build --config templates/motion/vite.config.mjs (npm run build
// does). The page's bundle is vite.client.config.mjs beside this file.
export default {
  // the stylesheet of rragent's page is not this program's
  publicDir: false,
  plugins: [
    cljf({
      outputDir: 'target/motion',
      compilerOptions: {
        'js-entries': { motion: { exports: { main: "'rragent.motion.main/main" } } },
      },
      manifest: false,
    }),
  ],
  build: {
    ssr: true,
    outDir: 'templates/motion/dist',
    // the client's bundle lies in dist/public, built after this one
    emptyOutDir: true,
    rolldownOptions: {
      input: {},
      output: { entryFileNames: '[name].js', codeSplitting: false },
    },
  },
  ssr: {
    target: 'node',
    noExternal: true,
  },
};
