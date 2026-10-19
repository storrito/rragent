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
are set. The artifacts pane shows what the agent makes, runs the projects
of the templates (a motion design, a HyperFrames composition) and renders
their videos.

## The page

`rragent serve` opens the agent of `RRAGENT_HOME` (`./agent`) and serves its
page on `PORT` (8080): the conversation as a chat, user messages, the
agent's answers rendered from their markdown (`rragent.markdown`:
markdown-it's tokens walked into hiccup, so a tag the model writes is
text, never HTML of the page), its tool calls folded with their output,
the answer streaming while a run goes, a composer at the bottom and a
Stop that aborts the run. The texts are English or German, by the
browser's language or `?lang=de`.

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
template that takes an eval (below).

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
