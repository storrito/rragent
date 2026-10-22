# rragent

Durable agents, called bots, on one machine. A sidebar with the bots, a
Replicant chat of the one picked, the live artifacts on the right,
pi-durable underneath, written in ClojureScript and compiled by
Clojurific for Node.js. "rr" is Replicant and REPL.

## The idea

Agents should be plenty, and each should have one job. A bot is one
conversation that never ends: what the bot did is its memory, a job and a
chat are the same thing. No list of chats, nothing an earlier
conversation left behind for a later one to stumble over. Several bots
share one machine, each a mind of its own (see [Bots](#bots)): its own
conversation, definition, schedules, hooks, facts and work folder; the
machine, its connectors and the templates are what they share.

The runtime is [pi-durable](https://github.com/earendil-works/pi/tree/main/packages/durable):
every turn, tool call and piece of state is committed to SQLite before it is
shown, and a restarted process picks up where it stopped. That fits a machine
that sleeps when nothing happens and restarts its processes when it wakes.

A bot's definition is a folder with an `AGENT.md` (`SKILL.md` works too):
its instructions, its tools, its schedule. The file is read again before
every run (`rragent.reload`): an edit of it, or the bot's own rewrite
of it when it takes a skill, is the definition of the next turn, the
model and the name on the page with it, without a restart. The bot runs on
[OpenRouter](https://openrouter.ai), through the connector of its Sprite or
with a key of its own. It reaches the outside world through typed tools, a
REPL whose functions are the organization's connectors, a schedule it writes
itself as a cron line, and named hooks that other systems call. A long job
it can hand to a subagent (`rragent.tools.subagent`): a conversation owned
by the tool call, with the agent's model, tools and instructions, whose
answer alone enters the transcript. It ends with the call, so it is a
helper of the agent, not a second one.

## Status

The harness runs (Clojurific, Vite, Node 26, pi-durable on SQLite), the page
serves the conversation, the tools, schedules and hooks are there, and so
is the REPL with the connectors of the Sprite's gateway as functions
(Storrito's procedures and Composio's meta tools among them, see
[Connectors and the REPL](#connectors-and-the-repl)). The artifacts pane
shows what the agent makes, runs the projects of the templates (a motion
design, a HyperFrames composition) and renders their videos.

## Bots

`RRAGENT_HOME` (`./agent`) is the machine's folder. `agent.sqlite` in
it is the one pi-durable storage of the machine, and a bot is a
conversation of it: the root conversation is the first bot, every
further bot an ownerless conversation (`createConversation`); a
subagent's conversation is owned by a task and is no bot. Each
conversation is configured from its own definition (`rragent.reload`:
its instructions, its model and thinking level, and its folder's
`work/` as its `cwd`, which pi-durable keeps per conversation and
hands to the environment of every call with the conversation's id).
The bots' folders are named by their conversations (`rragent.bots`,
`rragent.home`):

    agent.sqlite            the one storage: every bot's conversation and state
    bots/<id>/AGENT.md      the bot's definition, read again before every run
    bots/<id>/work/         where its tools work: artifacts/ and uploads/ in it
    shared/                 the folder every bot's bash reads and writes
    assistant.md            the generic definition a new bot starts from
    trash/<id>/             where a deleted bot's folder goes

There is no index file: the bots are the ownerless conversations of
the storage, read through pi-durable at start (`rragent.machine`), in
the order they were made (ascending by id), less the deleted ones,
which the session document `rragent.bots` names. A bot's name is its
definition's (`name:` of its `AGENT.md`); when it was made, the birth
time of its folder; when it was last used, the time of the newest
entry of its conversation. One harness, one page server, one gateway
discovery and one models catalog serve every bot, a REPL worker
thread per bot (its SCI context is the bot's, found by the
conversation of the call), and one sleep watch stops the machine once
every bot is idle and no page is open. One `rragent-wake` automation
is armed for the earliest due schedule across all bots
(`rragent.schedule/rearm!`), and a wake runs whichever bot's
schedules are due; the schedules and hooks are documents of each
conversation, the facts its entries. A hook's address is the
machine's, `POST /hook/<name>/<secret>`: the call is tried on every
bot, the secret says whose it is. Nothing of this reaches Storrito:
the machine's page, token and environment stay what they were.

A bot is made by one click on "New bot" in the sidebar, which asks
for nothing: the bot starts from the generic assistant definition as
it is (`deploy/agent/AGENT.md`, kept at `<home>/assistant.md`; the
bundle carries a copy as `assistant.md`), its page opens, and it
names itself: the generic definition tells it to write a short
fitting name into the `name:` line of its own `AGENT.md` once it knows
what the person wants, after its first answer at the latest, which the
reload shows. Until then the sidebar and the header call it "New bot"
with the time it was made. A rename by the person wins: it sets the
`name:` line and the line `renamed: true` in the frontmatter, which
the bot is told to leave alone, also when it writes the file anew; the
line is in the file, so it survives a restart. A bot makes a bot
through its `bot` tool, which knows both: `create` with a name, the
instructions, and the name of a skill from the clone when the job is
one of them (the instructions go on top of the assistant's text as
the job, the skill line when one was named, so it takes the skill or
asks the person; the tool makes the conversation in its own commit),
and `list`. The first bot of a new machine is the assistant too. A
deleted bot is hidden, not removed: its conversation stays in the
storage, its folder goes to `trash/<id>/`, the sidebar leaves it out;
the last visible bot stays. There is no message between bots in this
version: the person talks to each on its page.

A machine from before the bots ran one agent at the top of the home,
`AGENT.md` and `work/` beside `agent.sqlite`. At start the two are
moved into `bots/1/`, the folder of the root conversation, one rename
each, nothing copied, the storage untouched, and `assistant.md` is
written from that definition when it is the generic one. A home in
the new layout is left alone. A failure undoes the renames made,
leaves the old layout as it was, and the server refuses to start with
the reason (`rragent.bots/migrate!`).

## The page

`rragent serve` opens the bots of `RRAGENT_HOME` (`./agent`) and serves
their page on `PORT` (8080). The sidebar on the left lists the bots with
their name (or "New bot" and the time it was made, while a bot has
none of its own yet) and state (running, idle, asleep until a scheduled
run, failed to open), "New bot", one click, and behind the three dots
of a row a rename (a field in the row) and a delete, which asks once
in the row before the bot's folder goes to the trash; the last bot of a
machine stays.
The bot is in the URL, `/bot/<id>`, the id of its conversation, the way
pchat does it with its chats: a click in the sidebar changes the URL
without a reload and tells the server which bot this tab shows, the
root `/` redirects to the bot last used, and a tab whose bot is gone
shows another with a note. The header shows the
bot's name and model. The rest of the page is the bot's: its
conversation as a chat, user messages, the
agent's answers rendered from their markdown (`rragent.markdown`:
markdown-it's tokens walked into hiccup, so a tag the model writes is
text, never HTML of the page), its tool calls folded with their output,
the answer streaming while a run goes, a composer at the bottom and a
Stop that aborts the run, a program of the REPL in it ended with its
worker thread. Everything of a bot is served under its page,
`/bot/<id>/artifacts/…`, `/bot/<id>/uploads/…`, where the composer
posts too, so a link or a picture in an answer that names
`artifacts/<path>` or `uploads/<name>` points at that bot's file. The
texts are English or German, by the browser's language or `?lang=de`.

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
"artifacts/<name>"` shows an artifact. A button shows as an action, a
tint of the accent; `:class "primary"` fills it, for the one that moves
on. A block that does not read shows
as code with the reason. It is pchat's contract, which the agentitos
skills write against, with the inputs added:

    ```hiccup
    [:div [:p "Where should the shorts go?"]
     [:label [:input {:type "checkbox" :value "Reel @anna" :checked true}] " Reel · @anna"]
     [:label [:input {:type "checkbox" :value "Story @anna"}] " Story · @anna"]
     [:button {:class "primary" :send "Post the shorts on: {checked}"} "Take these accounts"]]
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
clip taken off keeps the message instead). The files land in `work/uploads/` of the bot's
folder (`rragent.uploads`) and the message names each one on a line of
its own, `video: uploads/ab12.mp4`, `audio: …` or `image: …`, so the
agent reaches the file from its working directory with its tools; a
picture goes to the model as an image block besides, when the model
takes images (pi-ai puts a placeholder where one was when it does not,
so a picture then goes as its line and a note). The transcript shows a
picture as a thumbnail and a clip as its name, linked to
`/bot/<id>/uploads/<name>`, which serves the files with ranges, behind
the token.

A message can be dictated: the microphone button beside the attach
button records while it is held, or from a click to the next; Space
held in the text records too, until it is let go, as in Claude Code (a
space typed stays a space). A strip above the text shows
a red dot, the time and the level of the voice, no words in any
language. At every pause in the speech (0.7 s, a piece of 30 s at
most) the page cuts the recording and posts the piece to `POST
/dictation` (`rragent.dictation`), which has a speech-to-text model
behind OpenRouter write it down (`RRAGENT_DICTATION_MODEL`, AssemblyAI's
Universal-3.6 Pro: it detects the language and scores every word; the
request goes the way of every call to OpenRouter, through the gateway's
connector or with the key, see [Connectors and the REPL](#connectors-and-the-repl));
the pieces fill the draft as they come back. After the stop, when a word
scored below 0.85, `POST /dictation/correct` (`rragent.correction`) has
a small model (`RRAGENT_CORRECTION_MODEL`, Gemini 3.1 Flash-Lite) fix
the misheard words with the agent's context: its system prompt and what
the person and the agent wrote, without the tools' calls and results,
never written into the conversation. On a sprite with 12k tokens of it
a correction took 0.8-1 s and cost $0.0004 from the cache, $0.003 when
it was cold; after 4 s the dictation stays as the speech model wrote it.
The draft is the dictation's until the correction is in.

The page is served the way pchat does
it with [rframes](https://github.com/simplemono/rframes): the server renders
the whole page as hiccup and pushes it as a transit frame over an SSE
connection it holds open per tab, Replicant diffs the frame into the DOM,
and the browser posts commands back. `rragent.rframes.*` is rframes' server
half ported to Node (`node:http`, `node:zlib`, transit-cljs), with the same
protocol as the Clojure original, so the unchanged rframes client runs in the
browser (`rragent.client`). The page is a function of the one atom of
`rragent.db`, the one place of mutable state: the machine's harness
map, the updater, the version and the banner, the bot each tab picked,
and every open bot under `[:bots <conversation id>]` with the value
keys the page reads (its conversation's view, pi-durable's
`viewState`, its definition, its state, its pane) beside the handles
of its runtime (the conversation, the worker, the reload's text, the
schedule source, the pane's handles). The watch on it compares old and
new per bot over the value keys (`rragent.page/bot-value-keys`): a
change of a bot's conversation or pane pushes a frame to the tabs
showing that bot; a change of what the sidebar shows of it (its name,
its state, a run starting or ending, a rename or delete under way), a
bot coming or going, or a key of the page's own pushes to every tab; a
handle pushes nothing.

The login is a secret link, as pchat's. On a Sprite the secret is the
sprite's own label `token:<secret>`, read with `sprite-env discover` when
the server starts (`rragent.sprite/discover`, `rragent.auth/token`);
elsewhere it is `RRAGENT_TOKEN`, and one is generated and printed in the
link when neither is there. `rragent serve` says where the token came
from, never what it is. `?token=<t>` on any path sets a cookie and
redirects, everything else behind the cookie answers 403; one token
for the machine, every bot behind it. The hooks of the bots,
`POST /hook/<name>/<secret>`, are outside the token; their secrets are
their keys.

## The artifacts

The right half of the page is the pane of the artifacts, the way pchat has
it: the files under `work/artifacts/` of the bot's folder, where the
bot's tools write, shown by kind (`rragent.artifacts`); the pane shows
the bot picked in the sidebar, nothing of the others. A page in an
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
the directory, its page proxied under `/bot/<id>/artifacts/<name>/` into the
iframe (`rragent.proxy`, node:http, the upstream response streamed
through); or a page of the project (`index.html`), served as it is, no
process. A project without a template runs with Node on the `main` of
its package.json (`server.mjs`), the way it did before the templates.

The `render` tool (`rragent.render`) runs the template's `render`
script on the project and the file it writes, `artifacts/<name>.mp4`
for a template that renders an mp4, shows up beside the chat like any
video; the pane has the same as a button, with the progress and a Stop
while it runs. The render's lines are the running output of the tool
call. Three more tools make material for a project through OpenRouter,
the gateway's connector or the key, as the models reach it
(`rragent.tools.media`, `rragent.openrouter`): `image`, a picture from a description
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
`deploy/examples/watcher/AGENT.md`). The `project` tool refuses the
others, with the allowed ones in the error. The templates are read
when the agent opens, not again with the file.

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
`/bot/<id>/artifacts/<name>/` of rragent's origin (`RRAGENT_BASE` in
the process's environment), so it reaches its own
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

## Screencasts

The 🎥 button of the composer records a screencast the way
[rawcast](https://github.com/maxweber/rawcast) does: the screen and the
webcam as two files, both with the microphone, no layout up front. The
panel picks the microphone and the camera (remembered in the browser),
shows the level of the microphone beside it and the camera live, and makes a test
recording of five seconds to play back. *Choose the screen* opens
Chrome's screen picker and shows the screen live beside the camera; the
test and Start record that screen, and Start opens the picker itself
when none is chosen yet. Chrome's own Stop sharing lets the screen go,
or ends a recording. While it records, the panel is a bar with the
time, when the recording started and Stop, and the live views of all
that is recorded under it, which *Hide live views* folds away
(remembered in the browser); the screen's own live view is in the
screen's recording while it shows. The pieces go to the machine as they are recorded
(`rragent.recordings`), into `artifacts/recordings/<stamp>/`:
`screen.webm`, `webcam.webm` and `recording.json` with the devices and
when each recorder started (remuxed by ffmpeg when the machine has one,
so that the files carry their duration). When the recording ends the
draft names the files; the person tells the agent how the two go
together, and the agent aligns them by their waveforms and composes the
video. Chrome on a desktop only.

A phone can be a camera too, instead of the webcam or besides it: *Pair
a phone* in the panel shows a QR code, the phone opens its link (it is
let in like a browser with the token) and lands on its page, which shows
its camera and waits. Start and Stop on the laptop reach the phone
through the machine (`rragent.phone`, server-sent events); the phone
records its own file at full quality, with its own microphone, and sends
it up itself, as `phone.webm` (`phone.mp4` from an iPhone) of the same
recording. The end waits for the phone's file. Nothing streams between
the two browsers: while its page is open, the phone sends a small still
of its camera every second, which the machine keeps in memory and the
panel shows as the phone's preview. The panel names the phone by its
model when the browser tells it (Chrome's client hints), else by its
system and browser, with the camera it uses. What is recorded is ticked: the screen, the webcam, the
phone, any of them; the phone alone is a vertical video for the agent to
cut, nothing is recorded on the laptop then.

An Android phone's screen comes over a USB cable (*Phone screen (USB)*,
`rragent.client.usb-screen`): no page on the phone can record its
screen, but Chrome on the laptop can talk ADB to the phone itself
(WebUSB, [Tango](https://github.com/yume-chan/ya-webadb)). *Connect the
phone* opens Chrome's USB picker; the phone, with USB debugging on, asks
once to allow this browser, whose key stays in the browser. The page
pushes the [scrcpy](https://github.com/Genymobile/scrcpy) server to the
phone (`public/scrcpy/`, version 3.3.3, the one the packages speak,
Apache-2.0, its license beside it), starts it, and decodes the phone's
H.264 with WebCodecs into a video track: live in the panel, recorded
like the laptop's screen with a copy of the microphone into
`phone-screen.webm`. Only the picture, no sound of the phone and no
control of it; the display is woken on connecting, the panel says to
wake it when no picture comes; a phone unplugged ends the recording. The
phone's screen and the phone's camera exclude each other, ticking one
unticks the other: while the phone is used, its camera page is in the
background, and Android gives a page in the background the sound only,
a picture a second at most, in any browser.

An `adb` server running on the laptop holds the phone, and Chrome cannot
claim it then. `scripts/adb-bridge.mjs`, run on the laptop, lets the page
go through that server instead, so adb and whatever uses it go on:

    node scripts/adb-bridge.mjs --origin https://<sprite>.sprites.app

It listens on `ws://127.0.0.1:15037` and turns each WebSocket into one
connection to the adb server (port 5037), bytes both ways; adb is a
shell on the phone, so only pages of the origins named are let in, and
only from the laptop. *Connect the phone* tries the bridge first and
goes over USB itself when no bridge answers. Chrome asks once whether
the page may reach apps on this device. Over USB on Linux, Chrome needs
a udev rule for the phone.

## Development

Node 26 (`mise install`), Java 17 or later for the compiler.

    npm ci
    npm run build:templates  # the motion template's program and page, once; the dev server runs them as built
    npm run dev      # the dev server serves the page at :8080, hot reloads it, nREPL in .nrepl-port
    npm test         # the ClojureScript tests under Node, the templates' too
    npm run build    # the templates, then dist/rragent.js and dist/public, run with npm start

An `.env` next to the project is never committed. On a Sprite the agent
needs no key at all: the models, dictation, the media tools and the
integrations come through the gateway (below). On a laptop,
`OPENROUTER_API_KEY` is the one variable the agent needs: the models,
dictation and the media tools all use it.

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
whenever a program asks, and makes three things of it. The gateway of
a fresh sprite comes up after the service that runs the agent, so a
list that times out or whose connection is closed under it is tried
again, after 1, 2, 4 and 8 seconds, before rragent concludes that
there is no gateway (a port that refuses at once, a laptop, costs one
try); `rragent serve` says how many tries it took when more than one.

- OpenRouter, one route for the models, dictation and the media tools
  (`rragent.openrouter`). With an `openrouter` connection, every
  request goes to that connection's base and `/v1`: the models' with a
  placeholder key the gateway replaces, dictation's and the media
  tools' with no key at all, the gateway adds the credential; without
  one, to OpenRouter itself with `OPENROUTER_API_KEY` (a laptop;
  `OPENROUTER_BASE_URL` points dictation and the media tools at a
  test's fake). `rragent serve` prints which, and the connections it
  found. Nothing of it is fixed for the life of the process: while no
  `openrouter` connection is known, a call asks the gateway again
  first, at most every ten seconds, so the first model call,
  dictation or picture after the gateway came up goes through it, and
  the REPL's `(connectors)` has the list from then on.
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
(`rragent.codemode-worker`, `node:worker_threads`), one per bot,
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

A pushed tag is the release: `git tag v0.1.3 <commit> && git push origin
v0.1.3`, nothing else. The workflow takes the version from the tag and
sets it into `package.json` for that build alone (`npm version
--no-git-tag-version`, nothing committed: `main` keeps `0.0.0`), so that
the bundle says the tag's version (`scripts/version.mjs` writes
`src/rragent/version.cljs` from `package.json` before a build); it
builds the bundle, publishes a GitHub release with `rragent.tar.gz` (the
`dist/` folder: `rragent.js`, `public/` and `templates/`) and its
checksum, and builds the Sprite image from that same `dist/` (below). A
machine image built elsewhere downloads
`releases/latest/download/rragent.tar.gz` instead of building the app,
and a running agent takes the release by itself (Updates, below).

## Updates

A running rragent looks the newest release up at start, every hour and
when the person clicks the version in the header (`rragent.update`:
`releases/latest` of GitHub's API, no token; a lookup that fails is one
line on stderr). A newer one shows as a banner above the chat: the
version, the first line of its notes and an Update button. The click
downloads `rragent.tar.gz` and its `.sha256` from the release into a
temp folder next to `dist/`, verifies the checksum, unpacks into
`dist.new`, swaps it for `dist/` and ends the process with exit code
75; `deploy/start.sh` starts it again on that code, from the new
`dist/`, and the page reconnects as it does after any restart. A
download or a checksum that fails leaves `dist/` as it was and the
banner says why. Nothing is applied while a run goes. With
`RRAGENT_UPDATE=auto`, which `start.sh` sets, a known update goes in by
itself once the agent is idle the way the sleep watch sees it (no run,
no render, no page connected), so an agent nobody opens profits too;
the banner says so. `RRAGENT_UPDATE=off` switches the lookup off.

The `dist/` replaced is the one the running bundle lies in, the parent
of its own folder (`/home/sprite/rragent/dist`), never the working
directory. This is for machines. A checkout on a laptop sees the banner
too (its build says `0.0.0`), and `npm start` replaces its own `dist/`
on a click and ends with 75, to be started again by hand; `git pull`
and a build is the way there, and under the dev server nothing is
applied.

## Deploy

An agent runs on a [Sprite](https://sprites.dev) made from an image of
rragent. `deploy/` has what the Sprite needs around the bundle, and the
release builds the image of it (`.github/actions/sprite-image`, run by
the release workflow after the tarball is published) into the private
package `ghcr.io/storrito/rragent-sprite`: `rragent-sprite:<x.y.z>`
from `deploy/Dockerfile.full`, the bundle on the Sprites base image
(`s2-default`, which brings Node through mise) with what the templates
need, ffmpeg with the fonts, Google Chrome and the HyperFrames CLI.
`latest` is the newest release of it, for a try by hand; a provisioner
pins the version (below). `deploy/Dockerfile` is the same without the
templates' programs, 1.3 GB smaller, for a build on a laptop (a render
answers why there is no video there); no release publishes it.

The image has the bundle in `/home/sprite/rragent/dist/`, `start.sh` beside
it and the generic definition in `agent/` (`deploy/agent/AGENT.md`),
which the first start moves into `agent/bots/1/`, the first bot's
folder (see [Bots](#bots)): every new machine starts as an
assistant that offers the skills of `/home/sprite/skills` and becomes
the one the person picks, by writing that skill's `SKILL.md` body into
its own `AGENT.md`, or works from the job the person describes when no
skill fits; every further bot starts the same way. The skills folder is a
clone of a GitHub repository in the Agent Skills format, one folder per
skill with a `SKILL.md`, made when the image is built: the build
arguments `SKILLS_REPO` (`storrito/agentitos`) and `SKILLS_BRANCH`
(`main`) of both Dockerfiles, the inputs `skills-repo` and
`skills-branch` of the action. `start.sh` pulls the clone before it
starts rragent (20 seconds at most, a failure said on stderr and the
clone from the image stands), so a machine that wakes offers the
current skills, and starts rragent again when it ended with 75 after
taking a release (Updates, above), without the pull. The image carries
no secret. The secret of the
page's link is a label of the sprite, `token:<hex>`, set with the
sprite's other labels when it is made and read by rragent at start
(`sprite-env discover`); `RRAGENT_TOKEN` is the laptop's way and the
fallback of a sprite without the label. The rest is
`/home/sprite/rragent/.env`, written when the sprite is made,
`start.sh` refuses to start without it, and what the provisioner
writes into it is the contract between the two:

- `RRAGENT_URL`, the sprite's public URL, the base of the hooks' addresses.
- `RRAGENT_NAME`, optional: the name the page shows for a bot while its
  definition is still the generic one (`name: Assistant`) and nobody
  named it; once the bot has taken a skill or a job, the name in its
  `AGENT.md` wins, and a name given in the sidebar or by the `bot` tool
  wins over both.
- `COMPOSIO_SESSION_ID`, when the agent has a Composio session.
- `OPENROUTER_API_KEY`, only when no OpenRouter connector of the
  organization covers the sprite: the models, dictation and the media
  tools all go through the connector, and all fall back to the key
  (`rragent.openrouter`).

The sprite itself: `sprite create <name> ghcr.io/storrito/rragent-sprite:<x.y.z>
--image-auth <auth> --service http:8080
--service-command http=/home/sprite/rragent/start.sh --service-restart
http=always` with the label `token:<hex>` among its labels, the `.env`
written by an exec, then `sprite update <name> --public`; the page's
link is `<url>?token=<hex>`.

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
`rragent-sprite:<tag>` from that checkout. Locally, `npm run build &&
docker build -f deploy/Dockerfile.full -t rragent-sprite:dev .` builds
the image, `-f deploy/Dockerfile` the one without the templates'
programs, `--build-arg SKILLS_REPO=<owner/name>` with another skills
repository.

## License

MIT, see [LICENSE](LICENSE).
