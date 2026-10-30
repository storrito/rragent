# A program in ClojureScript

Every program on this machine is ClojureScript run by nbb, a Node
interpreter for ClojureScript: no build, no configuration, npm packages
at hand. A program is a folder `programs/<name>/` in your working
directory with its `.cljs` files; this folder is an example to copy and
reshape. In the copy:

- `./run <command> …` runs it from anywhere: `./run import items.json`
  prints the items of the file that are new, `./run seen` the ids it
  remembers. From your working directory that is
  `programs/<name>/run import artifacts/reviews.json`: a file you name
  is relative to where you are, the program's own files are found
  through the classpath `run` passes to nbb.
- `nbb --classpath programs/<name> programs/<name>/main_test.cljs` runs
  the tests (in the folder, `nbb main_test.cljs` does); exit code 1
  when one failed.
- `npm init -y && npm install <pkg>` in the folder when the program
  needs an npm package; then `["pkg" :as pkg]` in the ns form, or
  `["pkg$default" :as pkg]` for a package whose export is a default one.

## The pieces

- `main.cljs`: the entry. `main` gets the command line's words and does
  one command; an error is one line on stderr and exit code 1. The last
  form runs `main` when this file is the one nbb was started with, not
  when a test requires it. The example reads JSON items from a file or
  an address and prints the new and the changed ones: `items-of`
  fetches or reads them, `import!` tells them apart with the store.
- `store.cljs`: state that outlives a run, in SQLite through
  `node:sqlite`, which comes with Node, no package to install. The file
  is `state.sqlite` beside the program unless `--db` names another;
  keep it with the program, not under `artifacts/`.
- `main_test.cljs`: `cljs.test`. A test that waits for a promise is
  `(async done …)` around a promesa chain ending in `(p/finally done)`,
  as `import-test` shows.
- `items.json`: the example's input.

## Writing it

- Promises: nbb has no `await`; promesa does it. `(p/let [a (f) b (g
  a)] …)` waits for each binding in turn, `(p/catch chain (fn [error]
  …))` takes the error, `(p/finally chain f)` runs last. `main` returns
  its chain, so every error of a command ends in one `p/catch`.
- Node and npm: `(:require ["node:fs/promises" :as fs])`, `(.method
  object arg)`, `(.-field object)`, `#js {…}` for an object a package
  wants, `js->clj` and `clj->js` at the edges (`:keywordize-keys
  true`). Prefer what Node has (`fetch`, `node:sqlite`, `node:fs`,
  `node:crypto`) to a package that does the same.
- The browser: Chrome is on this machine as `google-chrome`; `npm
  install puppeteer-core` in the folder, then `(.launch puppeteer #js
  {:executablePath "google-chrome" :headless true})` with
  `["puppeteer-core$default" :as puppeteer]`. Only when the job needs a
  browser.
- Arguments: `*command-line-args*`, the words after the file.
- Namespaces: a file `thing.cljs` in the folder is the namespace
  `thing`, required as `[thing]`; a folder `a/b.cljs` is `a.b`. Small
  functions, a docstring on each, a test for what can go wrong.
- Traps: a missing field of a JS object is `undefined`, which `nil?`
  takes as nil; `deftype` does not exist in nbb, a map or a `defrecord`
  does the same; nbb finds a program's namespaces only on its classpath
  (the folder it starts in, or `--classpath`), which `run` takes care
  of; `*file*` is the file being loaded, so a path beside the program
  is taken at the top level of the file, as `state-file` is.
