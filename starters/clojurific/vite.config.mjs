import cljf from 'clojurific/vite';

// The program, a Node.js program: `vite build` (npm run build) compiles
// the namespaces of src/ and bundles the entry below, a module exporting
// `main` of app.main, into dist/main.js, one file with every npm package
// it uses folded in. `run` imports it. Nothing here changes for a new
// program; the entry's export does when the entry namespace is renamed.
export default {
  // no page, no public folder
  publicDir: false,
  plugins: [
    cljf({
      outputDir: 'target/cljs',
      compilerOptions: {
        'js-entries': { main: { exports: { main: "'app.main/main" } } },
      },
      // no page, no manifest of its scripts
      manifest: false,
    }),
  ],
  build: {
    // a Node.js build: node builtins stay imports, everything else is bundled
    ssr: true,
    outDir: 'dist',
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
