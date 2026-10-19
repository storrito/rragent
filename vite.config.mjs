import { cpSync } from 'node:fs';
import cljf from 'clojurific/vite';

// The server, a Node.js program: the :js-entries module below is the
// bundle's only input (the plugin adds it to the build's inputs itself),
// `vite build` writes it to dist/rragent.js. The page's bundle is a build
// of its own, vite.client.config.mjs, into dist/public. The motion
// template's program and page are two more builds, templates/motion/
// vite.config.mjs and vite.client.config.mjs, run after these two by npm
// run build, so that the template's dist/ is there when the plugin below
// copies the templates.
//
// The dev server compiles the page's bundle besides and runs the entry
// (`rragent serve`, see rragent.main) in its ssr environment: the page at
// :8080 loads its scripts from the dev server at :5173, which hot reloads
// them, and the REPL evaluates in the server.
const devPort = 5173;

export default ({ command }) => {
  const serving = command === 'serve';
  if (serving) {
    // read by rragent.assets: the page's scripts come from the dev server,
    // named by the manifest the plugin writes to dist/manifest.json
    process.env.RRAGENT_VITE ??= `http://localhost:${devPort}`;
  }
  return {
    server: { port: devPort, strictPort: true },
    // public/app.css: served here in development, built by the page's build
    publicDir: serving ? 'public' : false,
    plugins: [
      cljf({
        outputDir: 'target/cljs',
        compilerOptions: {
          'js-entries': { rragent: { exports: { main: "'rragent.main/main" } } },
          // the dev server compiles the page's bundle next to the server
          ...(serving ? { main: "'rragent.client" } : {}),
          // the dev server starts an nREPL server, its port in .nrepl-port
          repl: { 'nrepl-port': 0 },
        },
        // the dev server runs the entry in its ssr environment once compiled
        ssrEntries: ['rragent'],
        manifest: serving ? 'manifest.json' : false,
      }),
      // The templates next to the bundle, read by rragent.templates: each
      // folder as the harness and the agent need it (TEMPLATE.md, project/,
      // the executables, a built dist/), without its sources, tests and
      // build files.
      {
        name: 'rragent-templates',
        closeBundle() {
          if (!serving) {
            cpSync('templates', 'dist/templates', {
              recursive: true,
              filter: (source) => !/(^|\/)(src|test|node_modules|target|\.[^/]+|vite[^/]*\.mjs)$/.test(source),
            });
          }
        },
      },
    ],
    build: {
      // a Node.js build: node builtins stay imports, everything else is bundled
      ssr: true,
      outDir: 'dist',
      rolldownOptions: {
        // no page in this build
        input: {},
        // one file: the provider SDKs pi-ai loads lazily are folded in instead of split into chunks
        output: { entryFileNames: '[name].js', codeSplitting: false },
      },
    },
    ssr: {
      target: 'node',
      // Only the build bundles node_modules. In dev the module runner would
      // have to transform them too, and fails on CommonJS ones (partial-json:
      // "exports is not defined"); left external, Node loads them itself.
      noExternal: command === 'build' ? true : undefined,
    },
  };
};
