import cljf from 'clojurific/vite';

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
  ],
  build: {
    outDir: 'dist/public',
    // the namespace is the input, the plugin adds it
    rolldownOptions: { input: {} },
  },
};
