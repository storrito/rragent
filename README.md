# rragent

One durable agent on one machine. A Replicant chat on the left, the live
artifacts on the right, pi-durable underneath, written in ClojureScript and
compiled by Clojurific for Node.js. "rr" is Replicant and REPL.

## The idea

Agents should be plenty, and each should have one job. An rragent is one
conversation that never ends: what the agent did is its memory, a job and a
chat are the same thing, and the machine it runs on belongs to it alone. No
list of chats, no second agent on the same machine, nothing an earlier
conversation left behind for a later one to stumble over.

The runtime is [pi-durable](https://github.com/earendil-works/pi/tree/main/packages/durable):
every turn, tool call and piece of state is committed to SQLite before it is
shown, and a restarted process picks up where it stopped. That fits a machine
that sleeps when nothing happens and restarts its processes when it wakes.

The agent's definition is a folder with an `AGENT.md` (`SKILL.md` works too):
its instructions, its tools, its schedule. The agent runs on
[OpenRouter](https://openrouter.ai) with a key of its own. It reaches the
outside world through typed tools, a schedule it writes itself as a cron line,
and named hooks that other systems call.

## Status

The harness runs (Clojurific, Vite, Node 26, pi-durable on SQLite), the page
serves the conversation, the tools, schedules and hooks are there. The
artifacts pane is empty for now.

## The page

`rragent serve` opens the agent of `RRAGENT_HOME` (`./agent`) and serves its
page on `PORT` (8080): the conversation as a chat, user messages, the
agent's answers, its tool calls folded with their output, the answer
streaming while a run goes, a composer at the bottom and a Stop that aborts
the run. The texts are English or German, by the browser's language or
`?lang=de`.

The page is served the way pchat does
it with [rframes](https://github.com/simplemono/rframes): the server renders
the whole page as hiccup and pushes it as a transit frame over an SSE
connection it holds open per tab, Replicant diffs the frame into the DOM,
and the browser posts commands back. `rragent.rframes.*` is rframes' server
half ported to Node (`node:http`, `node:zlib`, transit-cljs), with the same
protocol as the Clojure original, so the unchanged rframes client runs in the
browser (`rragent.client`). The page is a function of the conversation's
view (pi-durable's `viewState`): every commit of the conversation pushes a
new frame.

The login is a secret link, as pchat's: `RRAGENT_TOKEN` is the secret, one is
generated and printed in the link when the variable is absent. `?token=<t>`
on any path sets a cookie and redirects, everything else behind the cookie
answers 403. The hooks of the agent, `POST /hook/<name>/<secret>`, are outside
the token; their secrets are their keys.

## Layout

Two Vite builds, one dev server:

- `vite.config.mjs` builds the server, a Node.js program, into
  `dist/rragent.js` (one file). Its dev server compiles the page's bundle
  besides and runs the server entry in its ssr environment, so `npm run dev`
  serves the page at :8080 with the scripts coming from Vite at :5173, which
  hot reloads them; an nREPL server evaluates in the running server.
- `vite.client.config.mjs` builds the page's bundle (`rragent.client`, the
  rframes client with Replicant) into `dist/public/assets/` next to
  `public/app.css` and a `manifest.json` that names the bundle's scripts.
  The server serves `dist/public` and reads the manifest for the head of the
  page (`rragent.assets`); under the dev server it reads Vite's manifest in
  `dist/` instead and points the scripts at Vite.

## Development

Node 26 (`mise install`), Java 17 or later for the compiler.

    npm ci
    npm run dev      # the dev server serves the page at :8080, hot reloads it, nREPL in .nrepl-port
    npm test         # the ClojureScript tests under Node
    npm run build    # dist/rragent.js and dist/public, run with npm start

An `.env` next to the project is never committed; `OPENROUTER_API_KEY` is
the one variable the agent needs to talk to a model.

## License

MIT, see [LICENSE](LICENSE).
