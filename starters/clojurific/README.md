# A program in ClojureScript

The start of every program on this machine: ClojureScript, compiled by
[clojurific](https://github.com/WhimsicalCode/clojurific) (a fork of the
ClojureScript compiler that writes ES modules and bundles with Vite) and
run by Node, with npm packages at hand. Copy this folder to
`programs/<name>/` in your working directory and, in the copy:

- `npm ci` installs the compiler and the bundler, pinned by
  `package-lock.json`; once per program, the machine has the downloads.
- `npm test` compiles `src/` and `test/` and runs every `*-test`
  namespace under Node; exit code 1 when a test failed.
- `npm run build` bundles the program into `dist/main.js`, one file.
  Build again after every change of `src/`; the tests run from the
  sources.
- `./run <command> …` runs it: `./run import test/items.json` prints
  the items of the file that are new, `./run seen` the ids it
  remembers. From your working directory that is
  `programs/<name>/run import …`.

## The pieces

- `src/app/main.cljs`: the entry. `main` gets the command line's words
  as a vector and does one command; an error is one line on stderr and
  exit code 1. The example reads JSON items from a file or an address
  and prints the new and the changed ones: `items-of` fetches or reads
  them (async), `import!` tells them apart with the store.
- `src/app/store.cljs`: state that outlives a run, in SQLite through
  `node:sqlite`, which comes with Node, no package to install. The file
  is `state.sqlite` in the directory the program runs in, unless `--db`
  names another; keep it with the program, not under `artifacts/`.
- `test/app/*_test.cljs`: `cljs.test`. A test that waits for a promise
  is `(async done …)` around an `^:async` fn, as `main_test.cljs` does.
- `deps.edn`: ClojureScript libraries, from Clojars or git, and the
  source folders. `package.json`: npm packages. `vite.config.mjs`, the
  bundle, and `cljs.edn`, the tests' build, need no change for a new
  program; `run` is the few lines of JavaScript that import the bundle.

## Writing it

- Async: `(defn ^:async f [x] (let [v (await (promise))] …))`, `await`
  anywhere inside an `^:async` fn, in `try`/`catch` too. `main` is one.
- Node and npm: `(:require ["node:fs/promises" :as fs] ["pkg" :as pkg])`
  after `npm install pkg`; `(.method object arg)`, `(.-field object)`,
  `#js {…}` for an object a package wants, `js->clj` and `clj->js` at the
  edges (`:keywordize-keys true`). Prefer what Node has (`fetch`,
  `node:sqlite`, `node:fs`, `node:crypto`) to a package that does the
  same.
- The browser: Chrome is on this machine as `google-chrome`;
  `npm install puppeteer-core` drives it (`#js {:executablePath
  "google-chrome" :headless true}`), no browser download. Only when the
  job needs a browser.
- Namespaces: `app.*` as they are, or your own; a new one goes under
  `src/` and is required from `app.main`. Small functions, a docstring on
  each, a test for what can go wrong.
- Traps: `println` prints nothing without `(enable-console-print!)` once
  (`app.main` has it); a local named `fetch` hides the global one; a file
  mode is the string `"700"`, not `0o700`; a missing field of a JS object
  is `undefined`, which `nil?` takes as nil; a compiler warning names the
  file and line, fix it before the build.
