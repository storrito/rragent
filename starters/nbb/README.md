# A program in ClojureScript

Every program on this machine is ClojureScript run by nbb, a Node
interpreter for ClojureScript: no build, no configuration, npm packages
at hand. A program is a folder `programs/<name>/` in your working
directory with its `.cljs` files; this folder is an example to copy and
reshape. In the copy:

- `./run <command> …` runs it from anywhere: `./run import
  artifacts/reviews.json` keeps the file's items as they came and notes
  the import, `./run new` prints the items not posted yet, `./run posted
  r123 instagram` notes a posting. From your working directory that is
  `programs/<name>/run import …`: a file you name is relative to where
  you are, the program's own files are found through the classpath
  `run` passes to nbb.
- `nbb --classpath programs/<name> programs/<name>/main_test.cljs` runs
  the tests (in the folder, `nbb main_test.cljs` does); exit code 1
  when one failed.
- `npm init -y && npm install <pkg>` in the folder when the program
  needs an npm package; then `["pkg" :as pkg]` in the ns form, or
  `["pkg$default" :as pkg]` for a package whose export is a default one.

## State

No database of your own. Three things, and nothing else is kept:

- **Files say what came in.** A fetch, a scrape, an export lands as it
  came under `imports/<source>/<stamp>.json` in your working directory,
  beside `programs/` and out of the person's sight, never changed and
  never deleted. Overlaps and repeats are fine: the replay sorts them
  out.
- **Events say what happened.** One `imported` per file, naming it; one
  per thing you did with an item: `posted`, `sent`, `answered`,
  `skipped`, with the item's key. They go into the bot's log,
  `events.edn`, the file your `remember` and `recall` use, so what a
  program notes you can recall in the chat, and what you note the
  program sees.
- **Everything else is computed.** "Not posted yet" is the items of
  every import, the newest version of each, minus the posted keys,
  rebuilt on every run. A rule you fix (how a row is told from another,
  what counts as a repeat) applies to the whole past on the next run,
  because the files are still there. Reduce over the log, never hold
  it: `(transduce (events/kind "posted") … (events/reducible file))`
  makes each event as it goes, as `replay` and `new-items` do. Replaying is
  fast enough for years of imports; a database is state that drifts,
  `node:sqlite` is in Node when a job truly needs one, and it is not
  the first choice.

## The pieces

- `main.cljs`: the entry. `main` gets the command line's words and does
  one command; an error is one line on stderr and exit code 1. The last
  form runs `main` when this file is the one nbb was started with, not
  when a test requires it. `import!` keeps a file and notes it,
  `replay` builds the items from the log and the files, `new-items`
  takes the posted ones away, `key-of` tells an item from the others
  (its `id`, or a hash of its content when there is none, as a bank's
  export has none).
- `events.cljs`: the log, appended and read in the harness's format:
  `:event`, `:at` in milliseconds, your keys. `reducible` is what you
  `reduce`, `transduce` or `into`: the events made one at a time as you
  consume them, never all at once, a `reduced` value stops the rest; it
  is seqable too, for `filter` and friends. `kind` is a transducer for
  one kind of event.
  A path in an event is relative to the log's folder.
- `main_test.cljs` and `events_test.cljs`: `cljs.test`, in a folder
  laid out like a bot's; the first runs both. A test that waits for a
  promise is `(async done …)` around a promesa chain ending in
  `(p/finally done)`.
- `items.json`: the example's input.

## Writing it

- Promises: nbb has no `await`; promesa does it. `(p/let [a (f) b (g
  a)] …)` waits for each binding in turn, `(p/catch chain (fn [error]
  …))` takes the error, `(p/finally chain f)` runs last. `main` returns
  its chain, so every error of a command ends in one `p/catch`.
- Node and npm: `(:require ["node:fs/promises" :as fs])`, `(.method
  object arg)`, `(.-field object)`, `#js {…}` for an object a package
  wants, `js->clj` and `clj->js` at the edges (`:keywordize-keys
  true`). Prefer what Node has (`fetch`, `node:fs`, `node:crypto`) to
  a package that does the same.
- The browser: Chrome is on this machine as `google-chrome`; `npm
  install puppeteer-core` in the folder, then `(.launch puppeteer #js
  {:executablePath "google-chrome" :headless true})` with
  `["puppeteer-core$default" :as puppeteer]`. Only when the job needs a
  browser. What a page gave you is an import like any other: keep it.
- Arguments: `*command-line-args*`, the words after the file.
- Namespaces: a file `thing.cljs` in the folder is the namespace
  `thing`, required as `[thing]`; a folder `a/b.cljs` is `a.b`. Small
  functions, a docstring on each, a test for what can go wrong.
- Traps: a missing field of a JS object is `undefined`, which `nil?`
  takes as nil; `deftype` does not exist in nbb, a map or a `defrecord`
  does the same; nbb finds a program's namespaces only on its classpath
  (the folder it starts in, or `--classpath`), which `run` takes care
  of; `*file*` is the file being loaded, so a path beside the program
  is taken at the top level of the file, as `defaults` is; in a test,
  `(is (thrown? …))` catches nothing inside a `p/let` body, so an error
  expected there is taken with `try`/`catch` and checked as a value.
