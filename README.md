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
its instructions, its tools, its schedule. The file is read again before
every run (`rragent.reload`): an edit of it, or the agent's own rewrite
of it when it takes a skill, is the definition of the next turn, the
model and the name on the page with it, without a restart. The agent runs on
[OpenRouter](https://openrouter.ai), through the connector of its Sprite or
with a key of its own. It reaches the outside world through typed tools, a
REPL whose functions are the organization's connectors, a schedule it writes
itself as a cron line, and named hooks that other systems call.

## Status

The harness runs (Clojurific, Vite, Node 26, pi-durable on SQLite), the page
serves the conversation, the tools, schedules and hooks are there, and so
is the REPL with the connectors of the Sprite's gateway as functions
(Storrito's procedures and Composio's meta tools among them, see
[Connectors and the REPL](#connectors-and-the-repl)). The artifacts pane
shows what the agent makes, runs the projects of the templates (a motion
design, a HyperFrames composition) and renders their videos.

## The page

`rragent serve` opens the agent of `RRAGENT_HOME` (`./agent`) and serves its
page on `PORT` (8080): the conversation as a chat, user messages, the
agent's answers rendered from their markdown (`rragent.markdown`:
markdown-it's tokens walked into hiccup, so a tag the model writes is
text, never HTML of the page), its tool calls folded with their output,
the answer streaming while a run goes, a composer at the bottom and a
Stop that aborts the run, a program of the REPL in it ended with its
worker thread. The texts are English or German, by the browser's
language or `?lang=de`.

A chat with nothing in it opens with the skill's welcome
(`rragent.onboarding`), from the `metadata` of its SKILL.md or AGENT.md:
`title`, the task's name; `ask`, the first message, what the person
should bring; `placeholder`, the composer's while the chat is empty.
Each is a single-line value, the English one; `ask-de` and so on hold
the German. The welcome is the page's only: the model does not see it,
the skill's instructions say what comes first. Without an `ask` the chat
starts empty.

An answer can carry a part of the page: a ```hiccup block holds EDN
hiccup, which `rragent.genui` lets through its allow-lists once the
fence is closed (a block still being written shows as code). Links and
files point at `artifacts/…`, `uploads/…` or https; `:send "text"` on a
button sends the text as the person's next message, `{checked}` in it
the values of the checkboxes and radio buttons ticked in the same block
(the client's `:ui/send-checked` fills them in); `:open
"artifacts/<name>"` shows an artifact. A block that does not read shows
as code with the reason. It is pchat's contract, which the agentitos
skills write against, with the inputs added:

    ```hiccup
    [:div [:p "Where should the shorts go?"]
     [:label [:input {:type "checkbox" :value "Reel @anna" :checked true}] " Reel · @anna"]
     [:label [:input {:type "checkbox" :value "Story @anna"}] " Story · @anna"]
     [:button {:send "Post the shorts on: {checked}"} "Take these accounts"]]
    ```

A message can carry files, the way pchat's can: the attach button
takes an image, a video or an audio file, a picture can be pasted, and
files dragged over the page bring up a veil that takes them anywhere
(the way ChatGPT, LibreChat and Open WebUI do; a file dropped beside it
never replaces the page in the tab). A picture goes up scaled
to 1600 pixels on its long side and is stored content-addressed; a clip
goes up in pieces of 8 MB (`POST /upload?id=<hex>&offset=<n>&total=<n>`,
appended to a part file and renamed when the last piece is in, ten
gigabytes at most), with its progress on its chip. A message can be
files alone, without a text; sent while clips go up, it goes once the
last is in, and the button says so (a click on it, a failed upload or a
clip taken off keeps the message instead). The files land in `work/uploads/` of the agent's
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
artifact with a page of its own (`rragent.projects`). The agent makes
one with the `project` tool from a template (`rragent.templates`, see
[Templates](#templates)): the template's `project/` folder copied into
`artifacts/<name>/`, the package.json naming the template. What runs
the project is the template's `entry`: an executable of the template,
started as `<entry> <project-dir>` with `PORT` in its environment and
the programs the template needs, while the project is on screen in a
connected page or was changed in the last ten minutes, stopped after
fifteen idle minutes, restarted when it dies, its output in `.log` in
the directory, its page proxied under `/artifacts/<name>/` into the
iframe (`rragent.proxy`, node:http, the upstream response streamed
through); or a page of the project (`index.html`), served as it is, no
process. A project without a template runs with Node on the `main` of
its package.json (`server.mjs`), the way it did before the templates.

The `render` tool (`rragent.render`) runs the template's `render`
script on the project and the file it writes, `artifacts/<name>.mp4`
for a template that renders an mp4, shows up beside the chat like any
video; the pane has the same as a button, with the progress and a Stop
while it runs. The render's lines are the running output of the tool
call. Three more tools make material for a project through OpenRouter
(`rragent.tools.media`): `image`, a picture from a description
(`RRAGENT_IMAGE_MODEL`), `voice`, text to speech into the project's
folder (`RRAGENT_VOICE_MODEL`, `RRAGENT_VOICE`), and `music`, a clip or
a whole song (`RRAGENT_MUSIC_MODEL`, `RRAGENT_MUSIC_MODEL_LONG`). The
`eval` tool with `project` evaluates in a project's process, for a
template that takes an eval (below); without `project` it is the
agent's REPL (`rragent.codemode`, see [Connectors and the REPL](#connectors-and-the-repl)).

## Templates

The harness knows what an artifact is, not how one kind is made: a
template is a folder with its own technology, `templates/<name>/`,
and the harness reads three things of it.

- `TEMPLATE.md`, a frontmatter and a body. The frontmatter says `name`,
  `description` (the `project` tool's description names every template
  the agent may use with it, so the model knows what it can make),
  `renders` (the media the render makes, `mp4`; the first one is the
  extension of the file), `needs` (the programs it needs on the
  machine, `chrome, ffmpeg`) and `entry` (what the pane shows for a
  project, `index.html` by default). The body is what the agent needs
  to know to work with the template, in the voice of an `AGENT.md`;
  the `project` tool answers `create` with it.
- `project/`, the start of a new project, copied whole.
- `render`, an executable called as `render <project-dir> <output-path>`
  in the project's folder: it writes the file, its exit status says
  whether it worked, its stderr says why not (the tool's error carries
  it), and a render that runs longer than `RRAGENT_RENDER_TIMEOUT_MIN`
  minutes (thirty) is ended. A line `progress <done>/<total>` or
  `progress <n>%` on its output is the progress the pane shows, every
  other line is the render's output.

The `entry` is either a page of the project, `index.html` or another
`.html`, which the pane serves as it is with the files beside it, or
an executable of the template, `serve`, started as `serve
<project-dir>` with `PORT` in its environment, serving the project's
page on `127.0.0.1:PORT`; the pane proxies it. Both scripts get every
program of `needs` in their environment as its name in capitals, a
path (`CHROME=/usr/bin/google-chrome`, `FFMPEG=...`,
`HYPERFRAMES=...`), found by `rragent.programs` (`chrome` is `CHROME`
or the usual names on the PATH, anything else is on the PATH or named
by `RRAGENT_<NAME>`), and the Node that runs rragent first on their
PATH, so a `#!/usr/bin/env node` finds it. A need that is missing
makes the render answer why there is no file; `rragent serve` says at
start what it found for each template. The rest of the folder is the
template's own: sources, tests, dependencies, a build; the harness's
build copies each template without them into `dist/templates`. A
folder without a `TEMPLATE.md` is no template.

The agent's definition names the templates it may use,
`metadata.templates: [motion, hyperframes]` in its `AGENT.md`; every
installed one when it names none, none with `[]` (the page watcher of
`deploy/agent/AGENT.md`). The `project` tool refuses the others, with
the allowed ones in the error.

The two templates that come with rragent:

- `motion`: a motion design, pchat's template on Node. `design.cljs` is
  the namespace `design` with `size`, `duration`, `design-view` (the
  frame at a time, a pure function of the data and the time, hiccup)
  and `controls`, with [fnmotion](https://github.com/simplemono/fnmotion)'s
  `core`, `timeline` and `captions` at hand; `design.edn` the data, kept
  across restarts and written by the controls. The template's program
  (`rragent.motion.*` under `templates/motion/src`, built into
  `templates/motion/dist/motion.js` with the page's bundle in
  `dist/public`, see the Layout) evaluates the file with SCI
  (`rragent.motion.design`), plays it on a page of rframes with
  fnmotion's server-side player ported to Node (`rragent.motion.player`:
  play, a scrubber, the audio tracks in step), loads the file again when
  it is saved, an error shown on the page, and takes an eval into the
  design's namespace. Its `render` is fnmotion's render half ported to
  Node (`rragent.motion.render`): every frame as HTML by Replicant's
  string renderer, a few headless Chromes driven over the DevTools
  protocol with Node's WebSocket screenshot them side by side, ffmpeg
  encodes the screenshots in order and mixes the `:audio` tracks of
  `design.edn` in (`{:src "music.mp3" :volume 0.6 :fade-out 1}`, with
  `:start`, `:duration`, `:media-start`, `:fade-in`, `:loop?`). Needs:
  chrome, ffmpeg.
- `hyperframes`: a [HyperFrames](https://github.com/heygen-com/hyperframes)
  composition, `composition/index.html` with GSAP, shown in the
  HyperFrames player by the template's `serve`, a small Node server
  without dependencies, with one control per variable the composition
  declares (`data-composition-variables`: text, number, colour, yes/no,
  a choice). The values go into the project's `variables.json` and reach
  the composition as `window.__hfVariables`, the way `hyperframes render
  --variables-file` gives them, so the preview and the video agree. The
  page polls `rev` and loads the composition again at the same position
  when a file or a value changes. Its `render` runs the HyperFrames CLI,
  `hyperframes render` with the Chrome of the machine, and turns its
  `@hf-progress` lines into the progress. Needs: chrome, ffmpeg,
  hyperframes (`npm install -g hyperframes @hyperframes/player`; the
  player and the runtime of a project's page come from the same
  install). It takes no eval.

### A template's page and the eval into it

A template's page lives in the iframe of the pane under
`/artifacts/<name>/` of rragent's origin, so it reaches its own
process through that path: a relative `assets/app.js` on the page is
proxied to `/assets/app.js` of the process, a stream the process holds
open comes through as it comes (the motion page is an rframes page: it
opens its frame stream on its own path and gets a frame on every
change). A command the page posts to the origin's `/command` goes to
the project's process, which the Referer names, so an rframes page in
the pane works unchanged. A picture, a video or a sound in the
project's folder is served as the file it is, by rragent.

Live coding against the pane is a founding decision, and the harness
keeps the channel for it generic: the `eval` tool with `project`
posts the code to `POST /eval` of the project's process (started when
it does not run), the body the code as text. A template takes an eval
when its `serve` answers that path: `200` with the printed value as
text, an error status with the message; a process that does not serve
it answers `404` or `405`, and the tool says the template takes no
eval. What
the eval changes shows on the page because the page follows the
process. The motion template evaluates in the namespace of
`design.cljs` as it runs, so `(defn design-view ...)` or `(def
duration 3)` shows at once, until the file is saved; HyperFrames does
not take an eval.

## Layout

Two Vite builds, one dev server:

- `vite.config.mjs` builds the server, a Node.js program, into
  `dist/rragent.js` (one file) and copies `templates/` next to it, each
  template without its sources, tests and build files. Its dev server
  compiles the page's bundle besides and runs the server entry in its ssr
  environment, so `npm run dev` serves the page at :8080 with the scripts
  coming from Vite at :5173, which hot reloads them; an nREPL server
  evaluates in the running server.
- `vite.client.config.mjs` builds the page's bundle (`rragent.client`, the
  rframes client with Replicant) into `dist/public/assets/` next to
  `public/app.css` and a `manifest.json` that names the bundle's scripts.
  The server serves `dist/public` and reads the manifest for the head of the
  page (`rragent.assets`); under the dev server it reads Vite's manifest in
  `dist/` instead and points the scripts at Vite.
- The motion template has two builds of its own, `templates/motion/
  vite.config.mjs` (its program, `rragent.motion.main`, into
  `templates/motion/dist/motion.js`, which its `serve` and `render` import)
  and `vite.client.config.mjs` (its page's bundle, `rragent.motion.client`,
  into `templates/motion/dist/public`, served by the project's process).
  They compile from the root's classpath, `templates/motion/src` is on it
  for that; the harness's bundle requires nothing of it. `npm run
  build:templates` runs them, `npm run build` runs them first.

## Development

Node 26 (`mise install`), Java 17 or later for the compiler.

    npm ci
    npm run build:templates  # the motion template's program and page, once; the dev server runs them as built
    npm run dev      # the dev server serves the page at :8080, hot reloads it, nREPL in .nrepl-port
    npm test         # the ClojureScript tests under Node, the templates' too
    npm run build    # the templates, then dist/rragent.js and dist/public, run with npm start

An `.env` next to the project is never committed. On a Sprite the agent
needs no key at all: the models and the integrations come through the
gateway (below). On a laptop, `OPENROUTER_API_KEY` is the one variable the
agent needs to talk to a model.

## Connectors and the REPL

Every credential of the agent lives in a
[connector](https://fly.io/docs/sprites/concepts/connectors) of the
organization at Sprites, never on the machine. A connector is a credential
(an OAuth token, a key, a managed provider), an access policy that says
which sprites may use it, and a gateway endpoint: inside the sprite, a
request to `http://sprite.internal/v1/gateway/<provider>/<connection id>/<path>`
is forwarded to the provider with the credential added, the gateway
knowing the sprite by the request, no `Authorization` header anywhere.
`GET http://sprite.internal/v1/gateway/list` answers the connections the
sprite may use (`connections`: each with its `id`, `provider`,
`gateway_base_url`, `notes` and `provider_info`) and the providers that
could be set up (`available`).

rragent reads that list when it opens (`rragent.gateway`), and again
whenever a program asks, and makes three things of it:

- The models. With an `openrouter` connection, the harness's OpenRouter
  provider sends its requests to that connection's base and `/v1`, with
  a placeholder key the gateway replaces; without one, to OpenRouter
  itself with `OPENROUTER_API_KEY` (a laptop). `rragent serve` prints
  which, and the connections it found.
- Composio, the organization's third-party services (Google Drive,
  Gmail, Notion, Slack and the like through Composio's managed OAuth):
  a `custom_api` connection whose base is `backend.composio.dev`. The
  agent reaches them through the four meta tools of a tool-router
  session (`rragent.composio`), each one `POST
  /api/v3/tool_router/session/<id>/execute_meta` through that
  connection, the one path its policy allows. The session is
  `COMPOSIO_SESSION_ID`, made for the agent by whoever provisions it;
  rragent never makes one.
- Storrito, the organization's own API: a `custom_api` connection whose
  base is the organization's host (`https://acme.storrito.com`, or
  `/api/v1` of it), found among the custom API connections by the
  catalog at its base, `/documentation/api/v1/catalog.json`, public, or
  by a note of the connection that carries the catalog's link. Every
  procedure of the catalog becomes a function of the REPL, with a
  docstring from the catalog (`rragent.storrito`).

The `eval` tool is the agent's REPL (`rragent.codemode`): a Clojure program
evaluated by SCI in a worker thread of the agent's process
(`rragent.codemode-worker`, below), one context for the life of the
worker, so a `def` of one program is there for the next; what the
program printed and its value, cut at ten thousand characters, are the
answer, an error the tool's error. The context is closed: no `js/`, no
interop on values, no `require` of anything but Clojure's own namespaces,
no reading of the environment, and `aget`, `js-obj` and their kind say
that they are not there. What a program reaches, it reaches through the
namespace `agent`, referred in `user`, with `composio/*` and `storrito/*`
beside it; `(help)` lists them and `(doc f)` explains each:

- `(connectors)`: the gateway's list, read now, as data; `(connector
  "github")`: one connection, by provider name or by id.
- `(call "github" "/user/repos" {:method "GET" :query {:per_page 5}})`:
  an HTTP call through the gateway, by provider name or connection id
  (several `custom_api` connections: the id), `:body` as JSON when it is
  data; answers `{:status :headers :body}`, the body as data when it is
  JSON.
- `(composio/search "send an email" ["gmail"])`, `(composio/schemas
  ["GMAIL_SEND_EMAIL"])`, `(composio/execute "GMAIL_SEND_EMAIL" {:to
  "a@example.com"})`, `(composio/connections ["gmail"])`: the four meta
  tools, a completed run's data, a failed one a throw with Composio's
  error.
- `(storrito/procedures)`: the catalog's procedures, each a function
  `storrito/<name>` that takes a map and answers the API's data, a
  failed call a throw with what the API said; a 429 or a 5xx is tried
  again.
- `(remember "review-posted" {:id 4711})` and `(recall {:event
  "review-posted"})`: the facts (`rragent.facts`), the same entries the
  `remember` and `recall` tools make; a program sees the facts as they
  were when it started, what it remembers is committed when it ends.

A call blocks the thread it runs in until it is answered: SCI evaluates
a program synchronously, so a call in the middle of a loop cannot
await, and the request runs in a helper thread instead while the
program's thread waits on a shared flag (`rragent.sync-fetch`:
`Atomics.wait` and `receiveMessageOnPort`); a call has two minutes.
That is why the programs run in a worker thread of their own
(`rragent.codemode-worker`, `node:worker_threads`), one per agent,
started at the first program from what the main thread discovered of
the gateway: the main thread stays free meanwhile, the page renders,
the hooks and the schedules fire, Stop reaches the agent. The worker
runs the bundle itself, told apart by its `workerData`, so there is one
file to deploy; it holds the SCI context, and the `eval` tool posts each
program over the port with the facts the program sees and the
environment the bindings read, and gets what it printed and its value,
or its error, and what it remembered, which the tool commits. Stop
while a program runs terminates the worker, and so does a program
longer than `RRAGENT_PROGRAM_TIMEOUT_MIN` minutes (ten): the stopped
call fails with the reason, the next program starts a new worker with a
fresh context, and its answer says first that the defs are gone. The
tests' fake gateway lives in a worker thread of its own as well, since
the tests of the bindings evaluate in their own thread
(`rragent.fake-gateway`).

Three variables: `RRAGENT_GATEWAY`, the gateway's base
(`http://sprite.internal`, which answers only inside a sprite; elsewhere
the list is empty and says why), `COMPOSIO_SESSION_ID`, the session
of the Composio functions, and `RRAGENT_PROGRAM_TIMEOUT_MIN`, how long
a program may run.

## Releases

`npm version <x.y.z>` and `git push --tags`: the tag `v<x.y.z>` builds the
bundle, publishes a GitHub release with `rragent.tar.gz` (the `dist/`
folder: `rragent.js`, `public/` and `templates/`) and its checksum, and
builds the Sprite images from that same `dist/` (below). A machine image
built elsewhere downloads `releases/latest/download/rragent.tar.gz`
instead of building the app.

## Deploy

An agent runs on a [Sprite](https://sprites.dev) made from an image of
rragent. `deploy/` has what the Sprite needs around the bundle, and the
release builds two images of it (`.github/actions/sprite-image`, run by
the release workflow after the tarball is published) into the private
package `ghcr.io/storrito/rragent-sprite`:

- `ghcr.io/storrito/rragent-sprite:<x.y.z>` from `deploy/Dockerfile.full`:
  the bundle on the Sprites base image (`s2-default`, which brings Node
  through mise) with what the templates need, ffmpeg with the fonts,
  Google Chrome and the HyperFrames CLI. The image of an agent that
  renders videos. `latest` is the newest release of it, for a try by
  hand; a provisioner pins the version (below).
- `ghcr.io/storrito/rragent-sprite:<x.y.z>-plain` from `deploy/Dockerfile`:
  the bundle alone, 1.3 GB smaller. The image of an agent that renders
  no video: a render answers why there is none, everything else works.

Both have the bundle in `/home/sprite/rragent/dist/`, `start.sh` beside
it and the page watcher's definition in `agent/`, and neither carries a
secret: `/home/sprite/rragent/.env` is written when the sprite is made,
`start.sh` refuses to start without it, and what the provisioner writes
into it is the contract between the two:

- `RRAGENT_TOKEN`, the secret of the page's link.
- `RRAGENT_URL`, the sprite's public URL, the base of the hooks' addresses.
- `COMPOSIO_SESSION_ID`, when the agent has a Composio session.
- `OPENROUTER_API_KEY`, only when no OpenRouter connector of the
  organization covers the sprite.

The sprite itself: `sprite create <name> ghcr.io/storrito/rragent-sprite:<x.y.z>
--image-auth <auth> --service http:8080
--service-command http=/home/sprite/rragent/start.sh --service-restart
http=always`, the `.env` written by an exec, then `sprite update <name>
--public`; the page's link is `<url>?token=<RRAGENT_TOKEN>`.

The package is private, so a pull needs `<auth>`, which Sprites takes
as `image_auth` at the create only and never stores: the base64 of
`user:token`, the token a classic personal access token with
`read:packages` of a user who reads the organization's packages
(`printf '%s:%s' "$USER" "$TOKEN" | base64 -w0`). Storrito's
provisioner holds the two as `AGENT_IMAGE_PULL_USER` and
`AGENT_IMAGE_PULL_TOKEN` and makes the value the same way.

The workflows push with the job's `GITHUB_TOKEN`, which reaches a
package only when the package's settings grant the repository's actions
write access (Manage Actions access, once; a package first pushed by a
workflow has it from the start).

Sprites imports an image tag once per organization (two minutes, which
the first sprite of it waits for) and never pulls it again: a sprite
made later from the same tag gets the bytes of that first import,
whatever the registry holds by then. So every build gets a tag of its
own, which the version guarantees, and the workflows refuse a tag that
exists instead of pushing over it. A branch gets to a sprite without a
release through the Image workflow: "Run workflow" on the branch with a
tag that names it and the commit, `codemode-96b18a8`, pushes
`rragent-sprite:<tag>` and `<tag>-plain` from that checkout. Locally,
`npm run build && docker build -f deploy/Dockerfile.full -t
rragent-sprite:dev .` builds the full image, `-f deploy/Dockerfile` the
plain one.

## License

MIT, see [LICENSE](LICENSE).
