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
serves the conversation, the tools, schedules and hooks are there, and so
are Storrito's procedures and Composio's meta tools where their variables
are set. The artifacts pane shows what the agent makes, runs motion
designs and HyperFrames projects and renders their videos.

## The page

`rragent serve` opens the agent of `RRAGENT_HOME` (`./agent`) and serves its
page on `PORT` (8080): the conversation as a chat, user messages, the
agent's answers, its tool calls folded with their output, the answer
streaming while a run goes, a composer at the bottom and a Stop that aborts
the run. The texts are English or German, by the browser's language or
`?lang=de`.

A message can carry files, the way pchat's can: the attach button
takes an image, a video or an audio file, a picture can be pasted or
dropped onto the composer, a dropped clip too. A picture goes up scaled
to 1600 pixels on its long side and is stored content-addressed; a clip
goes up in pieces of 8 MB (`POST /upload?id=<hex>&offset=<n>&total=<n>`,
appended to a part file and renamed when the last piece is in, a
gigabyte at most), with its progress on its chip, and Send waits until
every clip is in. The files land in `work/uploads/` of the agent's
folder (`rragent.uploads`) and the message names each one on a line of
its own, `video: uploads/ab12.mp4`, `audio: …` or `image: …`, so the
agent reaches the file from its working directory with its tools; a
picture goes to the model as an image block besides, when the model
takes images (pi-ai puts a placeholder where one was when it does not,
so a picture then goes as its line and a note). The transcript shows a
picture as a thumbnail and a clip as its name, linked to `/uploads/<name>`,
which serves the files with ranges, behind the token.

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

## The artifacts

The right half of the page is the pane of the artifacts, the way pchat has
it: the files under `work/artifacts/` of the agent's folder, where the
agent's tools write, shown by kind (`rragent.artifacts`). A page in an
iframe that reloads in place when its file changes, an image, a video or
an audio file as themselves, anything else as a download, a chooser over
all of them. The newest thing the agent wrote takes the pane; a pick of
the person holds until the agent writes something newer. A recursive
watch on the folder reports every change, and the folder is read again
every two seconds besides.

A directory under `artifacts/` with a `package.json` is a project, an
artifact with a process of its own (`rragent.projects`): rragent starts
it with Node (`node server.mjs`, or the `main` of its package.json, `PORT`
in its environment) while it is on screen in a connected page or was
changed in the last ten minutes, stops it after fifteen idle minutes,
restarts it when it dies, writes its output to `.log` in the directory and
proxies its page under `/artifacts/<name>/` into the iframe
(`rragent.proxy`, node:http, the upstream response streamed through). The
agent makes one with the `project` tool from a template under
`templates/` (`rragent.templates`; built, they lie in `dist/templates`):

- `motion`: a motion design, pchat's template on Node. `design.cljs` is
  the namespace `design` with `size`, `duration`, `design-view` (the frame
  at a time, a pure function of the data and the time, hiccup) and
  `controls`, with [fnmotion](https://github.com/simplemono/fnmotion)'s
  `core`, `timeline` and `captions` at hand; `design.edn` the data, kept
  across restarts and written by the controls. The project folder holds
  the design alone: rragent runs it as `rragent motion <dir>`, a process
  of this program (`"rragent": "motion"` in the package.json), which
  evaluates the file with SCI (`rragent.motion.design`), plays it on a
  page of rframes with fnmotion's server-side player ported to Node
  (`rragent.motion.player`: play, a scrubber, the audio tracks in step)
  and loads the file again when it is saved, an error shown on the page.
- `hyperframes`: a [HyperFrames](https://github.com/heygen-com/hyperframes)
  composition, `composition/index.html` with GSAP, shown in the HyperFrames
  player by `server.mjs`, a small Node server without dependencies, with
  one control per variable the composition declares
  (`data-composition-variables`: text, number, colour, yes/no, a choice).
  The values go into the project's `variables.json` and reach the
  composition as `window.__hfVariables`, the way `hyperframes render
  --variables-file` gives them, so the preview and the video agree. The
  page polls `rev` and loads the composition again at the same position
  when a file or a value changes. The template's README says the rest.

The `render` tool (`rragent.render`) turns a project into
`artifacts/<name>.mp4`, which shows up beside the chat like any video; the
pane has the same as a button, with the progress and a Stop while it
runs. A motion design renders through `rragent motion-render <dir> <out>`,
fnmotion's render half ported to Node (`rragent.motion.render`): every
frame as HTML by Replicant's string renderer, a few headless Chromes
driven over the DevTools protocol with Node's WebSocket screenshot them
side by side, ffmpeg encodes the screenshots in order and mixes the
`:audio` tracks of `design.edn` in (`{:src "music.mp3" :volume 0.6
:fade-out 1}`, with `:start`, `:duration`, `:media-start`, `:fade-in`,
`:loop?`), `frame n/m` lines as the progress. A HyperFrames project
renders through the HyperFrames CLI, `hyperframes render` with the Chrome
of the machine, its `@hf-progress` lines as the progress. Either way the
render's lines are the running output of the tool call. Three more tools make material for a project through
OpenRouter (`rragent.tools.media`): `image`, a picture from a description
(`RRAGENT_IMAGE_MODEL`), `voice`, text to speech into the project's
folder (`RRAGENT_VOICE_MODEL`, `RRAGENT_VOICE`), and `music`, a clip or a
whole song (`RRAGENT_MUSIC_MODEL`, `RRAGENT_MUSIC_MODEL_LONG`).

Needed on the machine for the videos: a Chrome (`google-chrome` or
`chromium` on the PATH, or `CHROME`) and ffmpeg; for the HyperFrames
template `npm install -g hyperframes @hyperframes/player` besides
(`RRAGENT_HYPERFRAMES` names the CLI when it is not on the PATH; the
player and the runtime of a project's page come from the same install). `rragent serve` says at start what it
found; without them the render answers why there is no video and
everything else works.

## Layout

Two Vite builds, one dev server:

- `vite.config.mjs` builds the server, a Node.js program, into
  `dist/rragent.js` (one file) and copies `templates/` next to it. Its dev
  server compiles the page's bundle
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
the one variable the agent needs to talk to a model. The integrations
come with their own, each optional:

- `STORRITO_API_URL`, the organization's Storrito host
  (`https://acme.storrito.com`), and `STORRITO_CREDENTIAL`, the agent's
  API credential as `<id>:<secret>`. Every procedure of the
  organization's API catalog becomes a tool with the procedure's schema,
  deferred: the model finds the one it needs through `tool_search`
  (`rragent.storrito`).
- `COMPOSIO_URL` and `COMPOSIO_USER_ID`: the four meta tools of a Composio
  tool-router session for that user, which search, run and connect the
  organization's third-party services (`rragent.composio`). On a Sprite
  the URL is the Composio connector's gateway,
  `https://api.sprites.dev/v1/gateway/composio/<connection id>`, which
  adds the key itself; elsewhere it is `https://backend.composio.dev`
  with `COMPOSIO_API_KEY`.

## Releases

`npm version <x.y.z>` and `git push --tags`: the tag `v<x.y.z>` builds the
bundle and publishes a GitHub release with `rragent.tar.gz` (the `dist/`
folder: `rragent.js` and `public/`) and its checksum. A machine image
downloads `releases/latest/download/rragent.tar.gz` instead of building
the app; `deploy/` has what a Sprite needs around it.

## License

MIT, see [LICENSE](LICENSE).
