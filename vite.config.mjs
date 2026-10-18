import cljf from 'clojurific/vite';

// A Node.js program, no pages: the :js-entries module below is the bundle's
// only input (the plugin adds it to the build's inputs itself).
export default ({ command }) => ({
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      compilerOptions: {
        'js-entries': { rragent: { exports: { main: "'rragent.main/main" } } },
        // the dev server starts an nREPL server, its port in .nrepl-port
        repl: { 'nrepl-port': 0 },
      },
      // the dev server runs the entry in its ssr environment once compiled
      ssrEntries: ['rragent'],
      // no pages, no manifest of their scripts
      manifest: false,
    }),
  ],
  build: {
    // a Node.js build: node builtins stay imports, everything else is bundled
    ssr: true,
    outDir: 'dist',
    // one file: the provider SDKs pi-ai loads lazily are folded in instead of split into chunks
    rolldownOptions: { output: { entryFileNames: '[name].js', codeSplitting: false } },
  },
  ssr: {
    target: 'node',
    // Only the build bundles node_modules. In dev the module runner would have
    // to transform them too, and fails on CommonJS ones (partial-json:
    // "exports is not defined"); left external, Node loads them itself.
    noExternal: command === 'build' ? true : undefined,
  },
});
