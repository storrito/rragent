# rragent

Durable agents, called bots, on one machine, with tasks that work for
them and a wiki they share. A sidebar with the bots, a Replicant chat
of the one picked, the live artifacts on the right, pi-durable
underneath, written in ClojureScript and compiled by Clojurific for
Node.js. "rr" is Replicant and REPL. The design:
[docs/bots.md](docs/bots.md).

## The idea

Agents should be plenty, and each should have one job. A bot is one
conversation that never ends: what the bot did is its memory, a job and a
chat are the same thing. No list of chats, nothing an earlier
conversation left behind for a later one to stumble over. Several bots
share one machine, each a mind of its own (see [Bots](#bots)): its own
conversation, definition, schedules, hooks, events and work folder; the
machine, its connectors and the templates are what they share.

The runtime is [pi-durable](https://github.com/earendil-works/pi/tree/main/packages/durable):
every turn, tool call and piece of state is committed to SQLite before it is
shown, and a restarted process picks up where it stopped. That fits a machine
that sleeps when nothing happens and restarts its processes when it wakes.

A bot's definition is a folder with an `AGENT.md` (`SKILL.md` works too):
its name, its model and its job. The rules every bot works by are the
release's shared prompt (`deploy/agent/SYSTEM-PROMPT.md`), put in front
of the file at every read (`rragent.reload`), and a skill the file names
is read after it; nothing of that is copied into the file, so a release
reaches every bot at its next turn. The file is read again before every
run: an edit of it, or the bot's own edit when it takes a skill or a
job, is the definition of the next turn, the model and the name on the
page with it, without a restart. The bot runs on
[OpenRouter](https://openrouter.ai), through the connector of its Sprite or
with a key of its own. It reaches the outside world through typed tools, a
REPL whose functions are the organization's connectors, a schedule it writes
itself as a cron line, and named hooks that other systems call; a fire
or a call starts a *run*, a conversation of its own that does the job
and ends with one line for the bot's activity (`rragent.runs`, see
[docs/runs-and-skills.md](docs/runs-and-skills.md)). A folder of what
it made goes behind a public link when the person wants to show it to
someone (`rragent.shares`, see [docs/shares.md](docs/shares.md)). What
the answers cost is what OpenRouter charged for them, read off every
answer's stream (`rragent.costs`, see [docs/costs.md](docs/costs.md)).
The work that produces
much it hands to a task (`rragent.tasks`, the `task` tool): a
conversation of its own with a task's text and tools, whose result
alone comes back, as the tool's answer when the bot waited for it,
else as a message into the bot's chat when it is done.

## Status

The harness runs (Clojurific, Vite, Node 26, pi-durable on SQLite), the page
serves the conversation, the tools, schedules and hooks are there, and so
is the REPL with the connectors of the Sprite's gateway as functions
(Storrito's procedures and Composio's meta tools among them, see
[Connectors and the REPL](#connectors-and-the-repl)). The artifacts pane
shows what the agent makes, runs the projects of the templates (a
HyperFrames composition) and renders their videos.

## Tasks, the wiki and what came back

([docs/bots.md](docs/bots.md) has the whole of it.) Every bot is
configured at every read from the bundle's
`deploy/agent/SYSTEM-PROMPT.md`, what every conversation works by,
then `deploy/agent/BOT.md`, what a bot is, then its own file, its
skill, and `preferences.md` of the wiki. A bot hands work to tasks
(`rragent.tasks`): a `task` call makes a background task of the bot's
conversation that runs a conversation of its own with
`deploy/agent/TASK.md`, a task's tools, the bot's `work/` and the
bot's model, and delivers the result into the bot's chat as an input
(`rragent.task-done`) that wakes it; with `wait: true` the call takes
the result itself, up to a minute, the `rragent.tasks` document
making sure nothing arrives twice. What a conversation's model sees
is its role's (`rragent.roles`): a bot every tool but `subagent`, a
run of a hook or a schedule every tool but `task`, a task the tools
of work alone.

The machine has one wiki, `shared/wiki/` (`rragent.wiki`), a git
repository of Markdown pages every bot reads and writes and every
bot's pane shows under `wiki/`: what one bot found out, another
looks up. Before a bot's context is compacted it writes there what
must survive (`rragent.compaction`, below).

The releases 0.1.56 to 0.1.58 ran a machine as one conversation, the
bots of before archived as its topics. At the first open of this
version the bots get back what that conversation adopted
(`rragent.migration`, once, the database and the definitions copied
to `<home>.bak-<version>` first): they leave the archived list, the
schedules and hooks the adoption moved are theirs again as runs,
their artifacts come back from `artifacts/<topic>/`, and a note in
each chat says so without starting a turn. The one conversation
stays as a bot named Assistant with every topic's turns; a topic
made since gets no bot. Its wiki becomes the machine's.

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
    bots/<id>/work/         where its tools work: artifacts/, uploads/, programs/ and imports/ in it
    shared/                 the folder every bot's bash reads and writes
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
every bot is idle, none waits for its compaction while the person is
away (below), and no page is open. One `rragent-wake` automation
is armed for the earliest due schedule across all bots
(`rragent.schedule/rearm!`), and a wake starts the runs of whichever
bot's schedules are due, one run at a time on the machine; a hook or
a schedule of the mode `message` reaches the bot's own chat instead.
A bot messages another through its `bot` tool (`message`): a
background task of the sender's conversation (`rragent.messages`,
kind `rragent.message`) puts the text into the other bot's chat as an
input it answers, an entry that names the sender, waits for the answer
and brings it back into the sender's chat the same way, as a message
from the other bot; both submissions carry the task's id as their
request id, so a restart sends nothing twice. The page shows such a
message with the bot's name in place of "You".
The schedules and hooks are documents of each
conversation, the events a file of its folder, `bots/<id>/events.edn`,
one EDN map per line (`rragent.events`). A hook's address is the
machine's, `POST /hook/<name>/<secret>`: the call is tried on every
bot, the secret says whose it is. Nothing of this reaches Storrito:
the machine's page, token and environment stay what they were.

A conversation keeps at most its budget of context: 70 percent of its
model's window, or `RRAGENT_CONTEXT_TOKENS` when set
(`rragent.harness/budget-for`). Once an answer read more, cached or
not, the conversation is compacted while it goes on, its summary
placed at the next boundary (`rragent.harness/context-budget`, a hook
after every model answer, one compaction at a time). A bot writes
first: its compaction is a task (`rragent.compaction`) that submits
one input, "compaction coming, write what must survive into the wiki,
then say ready", waits for the turn and then compacts, the summary
asked to point at the wiki's pages; the same for the compaction while
the person is away and the button in the context ring. A task's, a
run's and a subagent's conversation is compacted at once.
pi-durable's own threshold compacts only near the model's window, 16K
below it, the same settings for every model; the window itself stays
the model's, so the room pi-ai leaves for an answer, and for the
summary of a conversation already past the budget, stays right. Past
the budget every call takes longer and costs more: a bot at 440,000
tokens of a 1,050,000 window waited 12 to 35 seconds for each call,
where 70,000 took two to seven. The ring in the chat's header measures the last
call against that budget (or the model's window, when smaller), so it
fills towards the compaction, and its card says that older messages
are summarized past it. The summarized messages stay on the page: once
the summary is placed, the entries before the first one kept are read
from the storage (`rragent.history`) and shown above a note that says
the model reads a summary from there on, the summary itself folded
under the note. The model's context shrinks, the person's chat does not.

A bot's conversation is compacted while the person is away too: once
its last answer read 50,000 tokens or more and it has been quiet for
ten minutes (`rragent.away`). Without, the next message after a long
pause read all of it again at full price and speed, $2.21 for "What is
1 + 1?" the next morning at 442,000 tokens; after it the person comes
back to a summary and the last turns. Until the compaction is done the
machine counts as busy, so the sleep watch waits for it instead of the
summary being made when the person is back.

A compaction rragent starts (the budget's and the one while the person
is away) reads the conversation from the provider's cache: OpenAI keeps
the prefix of GPT-5.6 and later at least 30 minutes after its last
read, and a cached token of GPT-6.1 Sol costs a twentieth of one read
anew (a first one 1.25 times, the cache write). pi-durable summarizes
in a request of its own, which no cache holds; rragent keeps the last
chat completion that carried a provider session's id
(`rragent.model-requests`, eight sessions at most; a Claude model's
request, a message the Anthropic way, is not kept and forgets what
its session kept) and asks it again with the request for the summary
appended and no tool to call (`rragent.warm-summary`, pi-durable's
`beforeCompact`): on a test of 27,895 tokens 27,767 came from the
cache, $0.0047 instead of $0.0698. Without a kept request (the process
started since) or when it fails, pi-durable summarizes as before.

Every request sends the tool results as less (`rragent.observations`,
pi-durable's `beforeRequest`, for that request only). A text of the
turns before the last two (a turn: a message of the person and what
followed it) past 4,000 characters goes as its first and last
thousand. Pictures stay whole until a compaction takes them into its
summary, which is asked to name their files so the agent can read them
again (`rragent.harness/compaction-focus`): a picture left out of a
request changes its start, and the provider reads everything after it
anew, at the full price and with the wait, while a cached picture
costs little; the compaction while the person is away and the one at
the budget clear them anyway, and a request the gateway refuses as too
large compacts too (`rragent.model-requests`). The conversation, the chat
and a compaction's summary keep every result whole; the agent runs a
tool again for what it needs. What it read of a skill (a file of a
skills folder, a `SKILL.md` or `AGENT.md`) stays whole: instructions,
not results. The line of the texts moves only with a message of the
person, so the requests of one run share their start and the
provider's cache. On the conversation of a bot after 97 turns it takes
the tool results' text from some 188,000 tokens to 68,000.

A tool's text that is a binary file decoded as text never reaches the
model: `read` takes every file that is no picture for UTF-8 text, so a
PDF, a video or a .docx came back as up to 50 KB of replacement
characters, and `cat` or a `fetch` of a PDF did the same. After every
tool call such a text (it begins with a file's signature, `%PDF-`,
`ftyp`, `PK…`, or holds more than one control code in a hundred or a
third replacement characters) is replaced by a line that says what the
file is and how to get at it with the commands of the machine's PATH
(`pdftotext`, `ffprobe`, `unzip`), or that a tool is missing
(`rragent.binary`).

The sidebar's "New bot" makes a bot with one click. It
starts with a file of the frontmatter alone
(`rragent.bots/definition-text`: the generic name, the default model,
no job), its page opens, and the bundle's prompt, which it reads like
every bot (`deploy/agent/SYSTEM-PROMPT.md`, bundled as
`system-prompt.md` with the skills clone's repository filled in for
`SKILLS_REPO` at the read, then `deploy/agent/BOT.md`, bundled as
`bot.md`), tells it to offer the skills, to take one
by naming it in its frontmatter, whose body is then read after the
file at every turn, or to write the job the person describes under
`## Your job`; and to name itself: a short fitting name into the
`name:` line once it knows what the person wants, after its first
answer at the latest, which the reload shows. Until then the sidebar
and the header call it "New bot" with the time it was made. A rename
by the person wins: it sets the `name:` line and the line `renamed:
true` in the frontmatter, which the bot is told to leave alone; the
line is in the file, so it survives a restart. The switch under the
composer sets `metadata.model` and `metadata.thinking` the same way
(`rragent.bots/set-metadata!`): the models to pick from and the
thinking levels are `rragent.models`, a file that names no level
means `high` (`rragent.definition/default-thinking`), and the menu
says what a model costs beside the default, from the catalog's
prices; a new bot starts on `anthropic/claude-opus-5.5`
(`rragent.definition/default-model`). A bot makes a bot through its
`bot` tool: `create` with a name, the instructions, and the name of a
skill when the job is one of them (the instructions are the file's
`## Your job`, the skill its `skill:` line; the tool makes the
conversation in its own commit), on a model the page offers; `list`
names the bots, `message` sends one a message, `transcript` reads
one's chat, folded. The first bot of a new machine is a new bot too.
A deleted bot is hidden, not removed: its conversation stays in the
storage, its folder goes to `trash/<id>/`, the sidebar leaves it out;
the last visible bot stays.

A machine from before the bots ran one agent at the top of the home,
`AGENT.md` (or a skill's `SKILL.md`) and `work/` beside
`agent.sqlite`, the home being the agent's folder. At start everything
at the top but the storage (`agent.sqlite` with its WAL, shared memory
and journal) is moved into `bots/1/`, the folder of the root
conversation: the definition, `work/`, and a skill's `tools/`,
`README.md` … with it, which its instructions name by `<skill>`. One
rename each, nothing copied. A relative symlink keeps its
target text and so points elsewhere one folder deeper; a `shared/` or
`trash/` the old agent made stays at the top as the machine's. A home in
the new layout is left alone. A failure undoes the renames made,
leaves the old layout as it was, and the server refuses to start with
the reason (`rragent.bots/migrate!`).

## The page

`rragent serve` opens the bots of `RRAGENT_HOME` (`./agent`) and
serves their page on `PORT` (8080). The sidebar on the left lists
them with their name (or "New bot" and the time it was made, while a
bot has none of its own yet) and what the person should know at a
glance: "Needs you" while a card of the bot (an answer that asks the
person something and got no answer yet) waits, else its state
(running, idle, asleep until a scheduled run, failed to open), its
dot pulsing while a run of it goes or a task; "New bot", one click,
and behind the three dots of a row a rename (a field in the row) and
a delete, which asks once in the row before the bot's folder goes to
the trash; the last bot of a machine stays. A feed shows its newest
200 messages and "Show earlier messages" above them, and ends in a
row per task of the bot that goes.
The bot is in the URL, `/bot/<id>`, the id of its conversation, the way
pchat does it with its chats: a click in the sidebar changes the URL
without a reload and tells the server which bot this tab shows, the
root `/` redirects to the bot last used, and a tab whose bot is gone
shows another with a note. The header shows the bot's name and how
full its context is, the composer the model and its effort. Settings,
at the bottom of the sidebar, opens in place of the chat for this
tab, its sections Updates, On the phone, Input, Recording, MCP
servers and Branding (Updates and Deploy, below); the version in the header opens
it too, and a bot picked in the sidebar brings the chat back. The rest of the page is the bot's: its
conversation as a chat, user messages, the
agent's answers rendered from their markdown (`rragent.markdown`:
markdown-it's tokens walked into hiccup, so a tag the model writes is
text, never HTML of the page), its tool calls folded to a line, their
command and output fetched when the line is opened (every frame
carries the whole page, so a fold nobody looks at sends nothing),
the answer streaming while a run goes, a composer at the bottom and a
Stop that aborts the run, a program of the REPL in it ended with its
worker thread. A message sent while a run goes steers it (pi-durable's
`whenBusy "steer"`): the model reads it after its current tool call,
not once the run ends, so a change of mind does not wait for work it
makes moot; the page shows it as pending until then. A hook's call, a
schedule's run and a bot's message are follow-ups, each a run of its
own once the current one ends. Everything of a bot is served under its page,
`/bot/<id>/artifacts/…`, `/bot/<id>/uploads/…`, where the composer
posts too, so a link or a picture in an answer that names
`artifacts/<path>` or `uploads/<name>` points at that bot's file; a
link to an artifact opens it in the pane, where it can still be
downloaded, an upload or an outside link in a new tab. The
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
takes a picture, a video, an audio clip, and any file the agent can
read or open: a PDF, an Office document, a text or data file
(Markdown, CSV, JSON, code), a subtitle file, an archive, a font (the
lists are in `rragent.file-kinds`); a picture can be pasted, and
files dragged over the page bring up a veil that takes them anywhere
(the way ChatGPT, LibreChat and Open WebUI do; a file dropped beside it
never replaces the page in the tab). A picture goes up scaled
to 1600 pixels on its long side and is stored content-addressed; a clip,
or any other file, goes up in pieces of 8 MB (`POST /upload?id=<hex>&offset=<n>&total=<n>`,
appended to a part file and renamed when the last piece is in, ten
gigabytes at most), with its progress on its chip. A message can be
files alone, without a text; sent while clips go up, it goes once the
last is in, and the button says so (a click on it, a failed upload or a
clip taken off keeps the message instead). The files land in `work/uploads/` of the bot's
folder (`rragent.uploads`) and the message names each one on a line of
its own, `video: uploads/ab12.mp4`, `audio: …`, `image: …`, `text: …`
(a file the agent reads as it is) or `file: …` (one it opens with the
machine's commands, which `rragent.binary` names when a tool reads
it raw), so the agent reaches the file from its working directory
with its tools; a
picture goes to the model as an image block besides, when the model
takes images (pi-ai puts a placeholder where one was when it does not,
so a picture then goes as its line and a note). A text pasted past
20,000 characters (some 5,000 tokens, a quarter of an hour of a
transcript) goes there too, as `uploads/<hash>.txt` named on a line
`text: uploads/…`; the message keeps its first and last thousand
characters, where the person's words about it stand, and the agent
reads the file when it needs it (`rragent.uploads/keep-long-text!`).
In the message the whole would go with every request until a
compaction, and be gone after it. Every picture the model
gets, uploaded or read by its `read` tool, is a WebP of quality 80 with
at most 2048 pixels on the long edge, made by ffmpeg (`rragent.images`),
the file itself untouched: the Sprites gateway forwards a request of at
most 8,000,000 bytes, and pictures are what fills one. A request still
refused as too large is answered to pi-ai as the overflow on which
pi-durable compacts the conversation, instead of retries
(`rragent.model-requests`), a
chat completion and a Claude model's message alike. The compaction
comes once and keeps the last messages whole: a request that is still
too large after it, pictures of megabytes in those messages, ends the
turn with the sizes. The transcript shows a
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
the tabs with the settings open, and every open bot under `[:bots <conversation id>]` with the value
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

## On the phone

The page is an app on a phone's home screen, and on a desktop
(`rragent.webapp`): a web app manifest names it Storrito, with the
burrito as its icon (`public/icons`), and the head's tags make it open
without the browser's bars. Settings → On the phone shows the page's
secret link as a QR code: the phone's camera opens the page signed in,
and from there Share → Add to Home Screen (Safari) or the menu → Add
to Home screen (Chrome) puts it among the apps; a browser that
installs by itself (Chrome, Edge, on Android and on a desktop) gets a
button instead. The manifest is served behind the token, so it is
the person's, and its start URL is the secret link: an app opened
from the home screen signs itself in with every start. That is for
iOS, where a home screen app keeps a cookie store of its own, apart
from Safari's; Android's and the desktop's share the browser's. A
rotated token leaves the installed app on the access-required page
until it is added again, as it leaves the link. The icons are open
routes, nothing of the person is in them.

## Branding

The top left corner of the page says "rragent" until the machine gets
a name of its own, a logo, or both. Settings → Branding has a field
for the name and a button that uploads a logo, which stands in front
of the name, 24 pixels high (`rragent.branding`). A machine with a
logo and no name shows the logo alone, for a logo that already spells
the name. The name is the title of the browser's tab too. Both belong
to the machine, so everyone with the link sees them, and every open
tab shows a change with its next frame.

`branding.json` in the machine's folder holds the name and the name of
the logo's file. The file itself lies in `branding/` beside it, named
by the hash of its bytes, so a browser keeps it and a new logo has a
new address. A logo is a PNG, JPEG, WebP, GIF or SVG of at most 2 MB.
What a file is its bytes say, not its name: a WebP saved as `logo.png`
is stored as a WebP, and a file that is no picture is refused.
`POST /branding/logo` takes it and `GET /branding/logo/<file>`
serves it, both behind the token. An empty name saved takes the name
away again, and Remove the logo the picture.

The app on the home screen keeps its name and its icon (Storrito, the
burrito), and so do the texts about updates, which name rragent.

## The artifacts

The right half of the page is the pane of the artifacts, the way pchat has
it: the files under `work/artifacts/` of the bot's folder, where the
bot's tools write, shown by kind (`rragent.artifacts`); the pane shows
the bot picked in the sidebar, nothing of the others. A page in an
iframe that reloads in place when its file changes, an image, a video or
an audio file as themselves, anything else as a download, a chooser over
all of them. Nothing but the person's own click moves the pane: a row
of the list, a card or a link in the chat. What the agent writes shows
up on the list, and reloads in place when it is the one on screen; the
agent names what it made in its answer, where a click opens it. A recursive
watch on the folder reports every change, and the folder is read again
every two seconds besides.

The person browses the folder as one on their computer, one level at a
time: the folders of the level first, one row each with how many files
it holds, then its files, both by name, the way back above them;
nothing is hidden but the dot files. The agent is told to keep a level short by putting
what belongs together into a folder, the many small things in one of
their own, folders within folders as the work calls for them, and no
rule beyond that. A `README.md` in a folder opens when the person enters
it, rendered; a relative link in it, `[the video](final.mp4)`, `[the
drafts](drafts/)`, opens that file or folder in the pane
(`rragent.markdown`, `data-artifact`). A CSV shows as a table with its
header row sticky, the first 500 rows and the count above them
(`rragent.csv`, read by PapaParse: a byte order mark, quotes, the
separator detected; a row that does not read as CSV is counted in a
note). Any other text file, the source of a page inside a project, a
script, a JSON, notes, is in an editor (CodeMirror,
`rragent.client.editor`), its syntax colored by its extension,
Clojure's by nextjournal's clojure-mode with its structural editing;
Ctrl+S or the Save under the editor writes the file back
(`:artifacts/save`). What the agent writes to the file meanwhile
replaces the text while nothing is unsaved, and is offered beside the
person's changes otherwise, never forced; unsaved changes survive a
look at another file. Edit, in the bar of a markdown file or a CSV,
opens their text in the editor, Preview brings the view back; the
menu of a text file's row has the same Edit, which opens the file in
the editor from wherever the pane is, and reads Preview while the
file's text is in the editor, the way back. The
filter above a long list finds
a file at any level and names its folder; the server narrows the
list, so that a frame of the page carries the rows in sight and not
every file of the bot, at most a hundred of the ones a text names,
with a word on how many more there are. A recording
(`artifacts/recordings/<stamp>/`, see [Screencasts](#screencasts)) is a
folder titled by its time, with its length. The files under `notes/`
are the agent's working notes: an ordinary folder on the list, but one
that gets no card in the chat.

The agent knows what the pane shows: a message the person sends ends
with the line `on screen beside the chat: artifacts/<path>`, the file
on screen (a project with its slash), else the folder browsed with its
slash, none at the top level (`rragent.artifacts/screen-line`). The
tab tells what its pane shows as it sends, and only while the pane is
in sight, not on a phone in the chat; a button in the chat sends no
line. "Make this one pink" then means that file. The transcript shows
the line as a hint under the message, "Open: <path>", not as its text.

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
process. The project's files are on the list as well: "Files" in its
row's menu, a double click on its row, or the crumb in the bar while
the project is on screen, opens its folder; a link in the chat to a file in it opens the file,
the list browsing inside; the crumb before the file's title leads back
to the project's page. A page of the project (`composition/index.html`)
is shown as text, its source, since the project's row is what plays
it; Open and Download of a file inside a project take the file as it
is (`?raw`), not what the project's process makes of it. The
`node_modules` of a project stay out of the list. A project without a template runs with Node on the `main` of
its package.json (`server.mjs`), the way it did before the templates.

The `render` tool (`rragent.render`) runs the template's `render`
script on the project and the file it writes, `artifacts/<name>.mp4`
for a template that renders an mp4, shows up on the list like any
video; the pane has the same as a button, with the progress and a Stop
while it runs, and "Open the video" once there is one (the pane does
not move to it by itself, not even minutes after the person's own
click). The render's lines are the running output of the tool
call. Three more tools make material for a project through OpenRouter,
the gateway's connector or the key, as the models reach it
(`rragent.tools.media`, `rragent.openrouter`): `image`, a picture from a description
(`RRAGENT_IMAGE_MODEL`), `voice`, text to speech into the project's
folder (`RRAGENT_VOICE_MODEL`, `RRAGENT_VOICE`), and `music`, a clip or
a whole song (`RRAGENT_MUSIC_MODEL`, `RRAGENT_MUSIC_MODEL_LONG`). A
song from Instagram's library for a story's music sticker the person
picks themselves: the `music_pick` tool (`rragent.tools.music-pick`,
`rragent.music-picks`) answers a link to Storrito's music pick page,
`<storrito>/ui/music-pick?callback=…&section-ms=…` (the host of the
Storrito connection, or `RRAGENT_STORRITO_URL`), which the bot gives as
a card. There the person searches the song and drags the part that
plays, like in Storrito's editor; on confirm Storrito posts the choice
to `POST /music-pick/<id>/<secret>` on the machine (`RRAGENT_URL`),
outside the token like a hook, and it reaches the bot's chat as a
message with the `<insta-music search-term track-id start-ms>` element
for the story. Unlike a hook the callback works once and for 24 hours,
since the link passes through the browser, and the message is written
from the fields read, never from the body as it came. The
`transcribe` tool (`rragent.tools.transcribe`) goes the other way: the
words spoken in an audio or a video file, one the person attached
(`uploads/<name>`) or recorded (`artifacts/recordings/<stamp>/audio.mp3`,
see [Screencasts](#screencasts)) or any other under `artifacts/`, written down by
the dictation's speech-to-text model (`RRAGENT_DICTATION_MODEL`, the
language detected) into `artifacts/transcripts/<name>.md`, or where
`out` says, in paragraphs cut at the pauses of the speech, each with
its time when `timestamps` is asked, and answered to the model as
text. OpenRouter takes 25 MB of audio inline, so ffmpeg cuts a file of
any length into pieces of ten minutes (mono, 16 kHz, 48 kbit/s MP3,
the audio alone of a video), each written down on its own, the times
shifted; without ffmpeg a small file in a format the model reads goes
whole. The `youtube` tool (`rragent.tools.youtube`, `rragent.youtube`)
fetches a YouTube video the person has the rights to use: the video as
an mp4 of h264 and aac up to `height` lines (1080 by default) into
`artifacts/downloads/<id>.mp4`, or a `section` of it alone
(`10:00-12:30`), the audio as an m4a, or the subtitles as a transcript
into `artifacts/transcripts/<id>.md` the way `transcribe` writes one,
the written ones in the video's language before its automatic
captions, without a download. yt-dlp does the fetching, with what
YouTube's bot checks ask for: a JavaScript runtime that solves its
challenges, which is the Node that runs rragent, and the bgutil PO
token provider as yt-dlp's plugin, its token script run by that Node
once per token. rragent installs the three into a folder of its own,
`~/.local/share/rragent/youtube`, at the first call on a machine,
since a release swaps `dist/` alone and the image of a Sprite never
changes, and has yt-dlp update itself once a day, since YouTube changes
every few weeks (`RRAGENT_YOUTUBE_PROGRAMS` names a folder of someone
else's with the same layout, in which nothing is installed). A
Sprite's own address is a datacenter's, which YouTube answers with
"Sign in to confirm you're not a bot" whatever the tokens, so every
request, the page, the token and the stream, leaves through
`RRAGENT_YOUTUBE_PROXY`, a mobile proxy paid by the month (a
GlobalByte modem in the USA), whose password the redaction keeps from
the model; a laptop needs none. What YouTube refuses comes back as a
sentence: the bot check, a video locked for the USA, a private or an
age-restricted one. The
`machine` tool runs the agent's programs (`rragent.codemode`, see
[Connectors and the REPL](#connectors-and-the-repl)).

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
`metadata.templates: [hyperframes]` in its `AGENT.md`; every
installed one when it names none, none with `[]` (the page watcher of
`deploy/examples/watcher/AGENT.md`). The `project` tool refuses the
others, with the allowed ones in the error. The templates are read
when the agent opens, not again with the file.

The template that comes with rragent:

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
  install). Its notes begin with the HyperFrames skills, the
  framework's own how-tos, which the agent reads before it writes a
  composition: the image installs them beside the CLI (`hyperframes
  skills`, into `~/.agents/skills`, see [Deploy](#deploy)).

### A template's page

A template's page lives in the iframe of the pane under
`/bot/<id>/artifacts/<name>/` of rragent's origin (`RRAGENT_BASE` in
the process's environment), so it reaches its own
process through that path: a relative `assets/app.js` on the page is
proxied to `/assets/app.js` of the process, a stream the process holds
open comes through as it comes (an rframes page opens its frame
stream on its own path and gets a frame on every change). A command
the page posts to the origin's `/command` goes to
the project's process, which the Referer names, so an rframes page in
the pane works unchanged. A picture, a video or a sound in the
project's folder is served as the file it is, by rragent.

## Starters

The programs a bot writes for its job (a pipeline, a scraper, a checker)
are ClojureScript run by [nbb](https://github.com/babashka/nbb), the
Node interpreter for ClojureScript: the shared prompt says so
(deploy/agent/SYSTEM-PROMPT.md), which every bot and every run of a bot
reads. The
reason is who helps when a program does not work: the people behind the
machine read Clojure, and a model left to itself picks Python. nbb was
chosen over clojurific (a JVM at compile time, a project to set up, a
build after every change), babashka (no npm) and squint (JavaScript
semantics under Clojure syntax, and a misspelled function compiles
without a word): real ClojureScript, no build, no configuration, npm
packages and Node's own modules required directly, `node:sqlite` for
state, `puppeteer-core` for the Chrome on the machine when a job needs
a browser. A one-off command stays bash.

A bot does not start from nothing: `starters/nbb/` is a how-to written
for the bot (`README.md`: the folder layout, `run`, tests, promesa for
promises, npm, the traps) with a working example beside it, a few files
to copy and reshape. The example is event-sourced, and the definition
asks for that shape: a program keeps no database; what came in (a
fetch, a scrape, an export) is kept as it came under `work/imports/
<source>/<stamp>.json`, never changed; what happened is an event in the
bot's log (`imported` with the file, `posted` with the item's key and
the channel), appended by the program in the format of `rragent.events`
(`starters/nbb/events.cljs` mirrors it), so `remember` and `recall`
and the programs share one log; and what the bot needs to know (new
since last time, not posted yet) is replayed from the files and the
log on every run. The events are never all in memory at once:
`rragent.events/reducible` (and the starter's) is an eduction over
the file's lines for `reduce`, `transduce` and `seq`, each event made
as the projection consumes it, a `reduced` value stopping the rest,
and `recall` hands the projection an eduction over it. A reader that
takes the file a chunk at a time was tried and taken out again
(commit b9f5f0b): a log grows by a line per import and per posting,
and the complexity is not worth it below a hundred megabytes. A rule fixed later (how a bank's rows without ids
are told apart) applies to the whole past; nothing is migrated.
Replaying is fast enough at a customer's scale, and a snapshot event
is the escape hatch nobody needs today. The build copies it to
`dist/starters/nbb`, so a release carries it and a bot reads it from
there; a bot's programs live in `programs/<name>/` under its `work/`,
beside `artifacts/`, and the artifacts pane lists that folder as
`programs/` of its root (`rragent.artifacts/programs-folder`), so that
the person sees the code that runs for them and can show it to someone
who reads Clojure; the pane hides nothing but the dot files, and
`node_modules` is no sight for anyone. The images
install nbb (`NBB_VERSION`, the version `package.json` pins for the
tests); the Tests workflow runs the example's tests from the bundle's
copy.

## Layout

Two Vite builds, one dev server:

- `vite.config.mjs` builds the server, a Node.js program, into
  `dist/rragent.js` (one file) and copies `templates/` next to it, each
  template without its sources, tests and build files, and `starters/`,
  the how-to and example of the bots' programs. Its dev server compiles
  the page's bundle besides and runs the server entry in its ssr
  environment, so `npm run dev` serves the page at :8080 with the
  scripts coming from Vite at :5173, which hot reloads them; an nREPL
  server evaluates in the running server.
- `vite.client.config.mjs` builds the page's bundle (`rragent.client`, the
  rframes client with Replicant) into `dist/public/assets/` next to
  `public/app.css` and a `manifest.json` that names the bundle's scripts.
  The server serves `dist/public` and reads the manifest for the head of the
  page (`rragent.assets`); under the dev server it reads Vite's manifest in
  `dist/` instead and points the scripts at Vite.

## Screencasts

The 🎥 button of the composer records a screencast the way
[rawcast](https://github.com/maxweber/rawcast) does: the screen and the
webcam as two files, both with the microphone, no layout up front; or
the microphone alone, a voice memo (below). The
panel picks the microphone and the camera (remembered in the browser),
shows the level of the microphone beside it and the camera live, and makes a test
recording of five seconds to play back. *Choose the screen* opens
Chrome's screen picker and shows the screen live beside the camera; the
test and Start record that screen, and Start opens the picker itself
when none is chosen yet. Chrome's own Stop sharing lets the screen go,
or ends a recording. While it records, the panel is a bar with the
time, when the recording started, Pause and Stop, and the live views of all
that is recorded under it, which *Hide live views* folds away
(remembered in the browser); the screen's own live view is in the
screen's recording while it shows. The pieces go to the machine as they are recorded
(`rragent.recordings`), into `artifacts/recordings/<stamp>/`:
`screen.webm`, `webcam.webm` and `recording.json` with the devices and
when each recorder started (remuxed by ffmpeg when the machine has one,
so that the files carry their duration). Pause holds every recorder of
the recording at once, the laptop's and the phone's (told through the
machine like Start and Stop, and at its next hello when its page
missed it), and Resume lets them go on: the files go on where they
held, without the paused time, the time in the bar stands still, and
`recording.json` names the pauses (`pauses`, the clock times in
milliseconds each began and ended), so that what happened meanwhile,
like the agent's own record, can be set on the recording's time. The
phone holds a moment later, when the word reaches it (at once while it
listens, at its next hello when it missed it): its own times are
`phone-pauses`, so that its file can be laid onto the laptop's piece by
piece. Pause, Resume and Stop go to the machine one after the other, in
the order they were pressed. When the recording ends the
draft names the files; the person tells the agent how the two go
together, and the agent aligns them by their waveforms and composes the
video. Chrome on a desktop only. A laptop without a microphone, or
without a camera, is asked for the one it has and records without the
other: the panel says so in the device's place, the source cannot be
ticked, and without a microphone the draft says the files have no
sound; with neither, the screen alone records.


The webcam's picture can go through effects on the laptop
(`rragent.client.effects`, remembered in the browser): *Background*
leaves it as it is, blurs what is behind the person, or takes it away
for a plain green (for the agent to key out with ffmpeg's `chromakey`
when it lays the webcam over the screen) or black; *Light and colour
automatic* asks the camera for continuous exposure and white balance
and corrects the brightness, contrast and saturation of the person as
the picture goes; *Touch-up* smooths the skin of the face, subtly or
smoothly, as Google Meet's portrait touch-up does, so that nobody
needs to make up for a recording. A worker (`public/effects/worker.js`)
does the work: MediaPipe's selfie segmenter (the landscape model,
256 × 144, Apache 2.0, in `public/effects/`; its bundle and wasm come
from `@mediapipe/tasks-vision` into `dist/public/effects/` at build,
served at `/effects/*`) finds the person in every frame, its BlazeFace
(`blaze_face_short_range.tflite`, Apache 2.0, beside it; on the GPU,
else the CPU, loaded when the touch-up is first turned on) six points
of the face, from which the skin is ellipses by the face's usual proportions, a
generous oval without the eyes, the eyebrows and the mouth
(`public/effects/skin.js`), a WebGL shader averages each
skin pixel with the neighbours near it in place and in colour (a
bilateral filter: pores and shadows fade, eyelashes and the edge of
the face stay sharp), a canvas composes the frame, and the frames flow
through streams
(`MediaStreamTrackProcessor`, `MediaStreamTrackGenerator`), so the
webcam keeps being processed while the tab is in the background, where
a page's animation frames stop. The preview, the test and the
recording show the processed picture; `recording.json` says which
background the webcam was recorded on (`effects`, with the green to
key out, and the `touch-up`), and so does the draft. Until a model is
loaded, and when it could not be, the webcam passes through as it is
and the panel says so. Chrome has the streams; another browser sees no
effects. `localStorage.setItem("rragent.effects.log", "1")` in the
browser's console puts the frames a second and the time of each step on
the console, every second.


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

The microphone alone is a source like the others, ticked in the
panel and remembered: Start records its stream to `audio.webm` in the
recording's folder, the pieces going up as they are recorded, and the
end has ffmpeg make `audio.mp3` of it (`rragent.recordings`; without
ffmpeg the WebM stays), which the draft names: a voice memo for the
agent to cut, clean and convert with ffmpeg in bash and write down
with the `transcribe` tool. The test plays it back as audio. The
words are not written into the draft, the way the dictation does it:
the recording itself is what the person sends.

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
goes over USB itself when no bridge answers. When the adb server knows
more than one phone (one the webcam, another whose screen is to be
recorded, say), the panel lists them, those plugged in by USB first and
those over the network marked so, and connects the one clicked; no
other phone is touched, not even woken. Chrome asks once whether
the page may reach apps on this device. Over USB on Linux, Chrome needs
a udev rule for the phone.

## Development

Node 26 (`mise install`), Java 17 or later for the compiler.

    npm ci
    npm run dev      # the dev server serves the page at :8080, hot reloads it, nREPL in .nrepl-port
    npm test         # the ClojureScript tests under Node
    npm run build    # dist/rragent.js and dist/public, the templates beside them, run with npm start

The dev server runs the app while the compiler has warnings (clojurific
0.12.12 and later); its hot reload pauses until they are fixed, and the
page's overlay shows them, so a warning is worth fixing right away.

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
- Composio, the agent's third-party services (Google Drive, Gmail,
  Notion, Slack and the like through Composio's managed OAuth): a
  `custom_api` connection whose base is `backend.composio.dev`, with
  the key of the agent's own Composio project. The agent reaches the
  services through the four meta tools of a tool-router session
  (`rragent.composio`), each one `POST
  /api/v3/tool_router/session/<id>/execute_meta` through that
  connection. The session is the agent's own, made when it starts
  for its user id, the machine's `agent:` label
  (`rragent.composio-setup`; `COMPOSIO_SESSION_ID` names one made
  elsewhere instead), and so are its connected accounts and its
  triggers, managed from the REPL. The session allows Composio's
  Instant tools (services Composio runs on its own accounts, like
  Firecrawl, without an account of the person's; each answer names
  what it charged), which charge the project's Composio balance and
  Storrito bills to the organization with the agent's usage; a session
  an earlier run made without them is switched once (`PATCH`, noted in
  the session document), one of the environment is left as it was
  made. The setup also subscribes the
  project's webhook to the page, `POST /composio/event`, outside the
  token: every event of the agent's triggers comes straight to the
  machine, which wakes it, verified by its Standard Webhooks
  signature, told again by its id (Composio delivers each several
  times), and submitted to the bot that added the trigger
  (`rragent.composio-events`).
- Storrito, the organization's own API: a `custom_api` connection whose
  base is the organization's host (`https://acme.storrito.com`, or
  `/api/v1` of it), found among the custom API connections by the
  catalog at its base, `/documentation/api/v1/catalog.json`, public, or
  by a note of the connection that carries the catalog's link. Every
  procedure of the catalog becomes a function of the REPL, with a
  docstring from the catalog (`rragent.storrito`).

The `machine` tool runs a program as a machine (`rragent.state-machine`,
`rragent.tools.machine`): Clojure evaluated by SCI in a worker thread
of the agent's process (`rragent.codemode-worker`), each program one of
its own, for its value, a map, the machine's state. Nothing runs inside
the program: the functions of the connectors answer a call as data,
`(storrito/list-social-accounts {})` is `[:storrito/list-social-accounts
{}]`, and the program puts one under `:next` with the key its answer
goes under, `{:next {:call (storrito/list-social-accounts {}) :as
:accounts}}`. The tool, the imperative shell of the functional core,
runs the call as a nested tool call of its own under the step's key
(the tools of `rragent.tools.host`, which only tools call, and the
bot's `remember`), puts the answer under `:as`, a failed call as
`{:error ...}`, and, when the program defined `(defn step [state]
...)`, has the worker evaluate it on the state for the next one, until
a state without `:next` or with `:done`; an initial state without
`:next` is stepped first. The state after every answer is a memo of
the call, so the tool is replay-safe: a call the machine's restart
interrupted loads the program again (pure), takes each state from its
memo and each call from its key, and goes on where it was; nothing is
made twice. The model reads what the program printed, how many calls
were made, and the final state. Every program starts from a fresh
fork of the context: a `def` of one is not there for the next, since
what the model cannot see in its conversation, once it is summarized,
it cannot use; data for a later program goes into a file, `{:next
{:call (spit "data/x.edn" v) :as :path}}`, read back in a later
machine with `{:next {:call (slurp "data/x.edn") :as :text}}` and
`(edn/read-string (:text state))` in its step. A program that writes
a call as a statement, `(spit "data/x.edn" v)` on its own, is refused
at load, since the vector would do nothing. A big or binary HTTP body goes to a file
with `:to-file` in the call's opts; an answer over a megabyte fails
the call and says so. `(help)` and `(doc f)` are pure and answer
`{:help ...}` and `{:doc ...}`. See [docs/machines.md](docs/machines.md).

The calls:

- `(connectors)`: the gateway's list, read again, as data;
  `(connector "github")`: one connection, by provider name or by id, as
  the machine started, pure.
- `(call "github" "/user/repos" {:method "GET" :query {:per_page 5}})`:
  an HTTP request through the gateway, by provider name or connection id
  (several `custom_api` connections: the id), `:body` as JSON when it is
  data, `:to-file` for the body as a file; answers `{:status :headers
  :body}`, the body as data when it is JSON. A GET is a read the host
  makes again when a restart caught it in flight; anything else is
  abandoned and answers an error then.
- `(composio/search "send an email" ["gmail"])`, `(composio/schemas
  ["GMAIL_SEND_EMAIL"])`, `(composio/execute "GMAIL_SEND_EMAIL" {:to
  "a@example.com"})`, `(composio/connections ["googlesheets"])`: the four
  meta tools, a completed run's data, a failed one `{:error ...}` with
  Composio's error; `connections` starts a connection the organization
  lacks and answers a sign-in link for the person, good for ten
  minutes, which the agent offers as soon as the person names a
  service of theirs. The search session a search answers is the
  shell's to carry into the calls after it.
- `(composio/accounts)`, `(composio/trigger-types "googledrive")`,
  `(composio/add-trigger "GOOGLEDRIVE_FILE_CREATED_TRIGGER" "ca_1" {})`,
  `(composio/triggers)`, `(composio/delete-trigger "ti_1")`: the agent's
  connected accounts and triggers at Composio, its own; a trigger added
  is noted as a fact (`composio-trigger`) by the host, and its events
  arrive in the conversation of the bot that added it.
- `(storrito/procedures)`: the catalog's procedures, pure, each a
  function `storrito/<name>` that takes a map and answers its call; a
  failed one `{:error ...}` with what the API said; a 429 or a 5xx is
  tried again.
- `(mcp.storrito/list_stories {:limit 5})`, `(mcp/servers)`,
  `(mcp/tools "storrito")`, `(mcp/call "files" "read.file" {...})`: the
  tools of the MCP servers named in `mcp.json` of the machine's folder
  (the `mcpServers` file of pi and pchat, a URL with headers or a
  program), a namespace per server, connected by the main thread at the
  first program (`rragent.mcp`, over `@earendil-works/pi-mcp`); the
  structured content or the text (JSON as data), a failure
  `{:error ...}`, pictures shown after the answer. `(mcp/add! "github"
  {:url … :token "GITHUB_TOKEN"})`, `mcp/test`, `mcp/enable!`,
  `mcp/remove!` are calls that change the servers; a token is typed by
  the person into a password field the bot asks for (`[:secret …]`),
  saved in `secrets.json`, never seen by the model, and replaced by its
  name in any tool output. A server that signs in with OAuth (`:oauth
  true`) gets a Sign in link in the settings, or `[:sign-in …]` in a
  bot's answer; its tokens are kept and refreshed by the machine.
  Settings → MCP servers lists them and opens a bot that connects a
  service. See [docs/mcp.md](docs/mcp.md).
- Why hooks and schedules start runs instead of chat messages, and why
  the person's know-how becomes a skill: [docs/runs-and-skills.md](docs/runs-and-skills.md).
- `(remember "review-posted" {:id 4711})` and `(recall {:event
  "review-posted"})`: the bot's events (`rragent.events`, one EDN map
  per line in `bots/<id>/events.edn`), the same file the `remember` and
  `recall` tools write and read, shared with the bot's runs.
- `(spit "data/accounts.edn" accounts)` and `(slurp "data/accounts.edn")`:
  a file of the bot's work folder written (a string as it is, data as
  EDN that reads back, anything else refused) and read, inside the work
  folder only; the state between machines, named by the model.

The worker holds nothing but the SCI context: no network, no shared
memory, no environment. The gateway's list at start and the OpenRouter
probe are the main thread's one blocking request each
(`rragent.sync-fetch`).

Three variables: `RRAGENT_GATEWAY`, the gateway's base
(`http://sprite.internal`, which answers only inside a sprite; elsewhere
the list is empty and says why), `COMPOSIO_SESSION_ID` and
`COMPOSIO_USER_ID`, the session and the user id of the Composio
functions (the setup at start fills them in, an environment that
names a session made elsewhere keeps it), and
`RRAGENT_PROGRAM_TIMEOUT_MIN`, how long a program may run.

## Releases

A pushed tag is the release: `git tag v0.1.3 <commit> && git push origin
v0.1.3`, nothing else. The workflow takes the version from the tag and
sets it into `package.json` for that build alone (`npm version
--no-git-tag-version`, nothing committed: `main` keeps `0.0.0`), so that
the bundle says the tag's version (`scripts/version.mjs` writes
`src/rragent/version.cljs` from `package.json` before a build); it
builds the bundle and publishes a GitHub release with `rragent.tar.gz`
(the `dist/` folder: `rragent.js`, `public/` and `templates/`) and its
checksum. A machine downloads `releases/latest/download/rragent.tar.gz`
at its first start instead of building the app (Deploy, below), and a
running agent takes the release by itself (Updates, below). The Sprite
image is not the release's: it has a version of its own, bumped with
its Dockerfile (Deploy, below); until Storrito provisions by that, the
release tags the image with the release's version too.

A branch gets to a sprite without a release through the Trial workflow
(`.github/workflows/trial.yml`): "Run workflow" on the branch builds
its bundle (which says `0.0.0-trial.<sha>`) and publishes a GitHub
pre-release `trial-<branch>-<sha>` with `rragent.tar.gz` and its
checksum, and the branch's image as `<version>-trial.<sha>` unless it is there
(Deploy, below). A pre-release is invisible to `releases/latest`,
so no machine takes it by itself; a sprite takes it with
`RRAGENT_UPDATE=trial-<branch>-<sha>` in its `.env` (Updates, below), a
forked sprite with a restart, a new one at its first start. The trial
tags accrue in the repository and are deleted by hand.

## Updates

A running rragent looks the newest release up at start, every hour and
when the person clicks Check for updates in the settings, so that
nobody waits for the hourly lookup (`rragent.update`: `releases/latest`
of GitHub's API, no token; a lookup that fails is one line on stderr).
A newer one shows as a banner above the chat, and in the settings: the
version, the first line of its notes and an Update button; the settings
also say that there is nothing newer, or that the lookup failed. The click
downloads `rragent.tar.gz` and its `.sha256` from the release into a
temp folder next to `dist/`, verifies the checksum, unpacks into
`dist.new`, swaps it for `dist/` and ends the process with exit code
75; `deploy/image/start.sh` starts it again on that code, from the new
`dist/`, and the page reconnects as it does after any restart; every
frame names the server's version beside the bot's path, and a tab whose
bundle is older reloads itself once (`rragent.client/url-alias`), so
that the open tabs do not go on running the old client against the new
server. A download or a checksum that fails leaves `dist/` as it was
and the banner says why. Nothing is applied while a run goes. With
`RRAGENT_UPDATE=auto`, which `start.sh` sets, a known update goes in by
itself once the agent is idle the way the sleep watch sees it (no run,
no render, no page connected), so an agent nobody opens profits too;
the banner says so. `RRAGENT_UPDATE=off` switches the lookup off.
`RRAGENT_UPDATE=<tag>`, a release's tag (`v0.1.55`, or
`trial-<branch>-<sha>`, Releases above), pins the machine to that
release: it is looked up instead of the newest (`releases/tags/<tag>`)
and taken once the agent is idle, whatever its version, a rollback or a
branch's trial; then nothing else is taken until the line in `.env`
changes, and the settings say so. What is in place is known by the
marker file `release` beside `dist/`, the tag of the release that made
it, written after a swap and by `start.sh` after its download.

The `dist/` replaced is the one the running bundle lies in, the parent
of its own folder (`/home/sprite/rragent/dist`), never the working
directory. This is for machines. A checkout on a laptop sees the banner
too (its build says `0.0.0`), and `npm start` replaces its own `dist/`
on a click and ends with 75, to be started again by hand; `git pull`
and a build is the way there, and under the dev server nothing is
applied.

## Deploy

An agent runs on a [Sprite](https://sprites.dev) made from an image of
rragent, `ghcr.io/storrito/rragent-sprite:<version>`, a public
package, where `<version>` is the image's own version, the
Dockerfile's label `org.opencontainers.image.version`: the image is
what the machine needs around the bundle, and it changes only when
that version is bumped, which every change in `deploy/image/` does.
The Image workflow (`.github/workflows/image.yml`) builds it on a push
to `main` that touches `deploy/image/` and refuses a version that
exists already (`.github/actions/sprite-image`), so a change without a
bump fails loudly. A provisioner lists the package's tags
(`ghcr.io/v2/storrito/rragent-sprite/tags/list`, with the anonymous
pull token of a public package) and makes the machine from the highest
`x.y.z`; Storrito's does (`app.agent.rragent-release`). The releases'
own tags (`rragent-sprite:0.1.x`, `latest`), which the release
workflow still pushes until nothing provisions by them, sort below
the image's `1.x` versions, and a trial's `<version>-trial.<sha>` is
no `x.y.z`: both are passed over.

The image is the Sprites base image (`s2-default`, which brings Node
through mise) with what the templates need, ffmpeg with the fonts,
Google Chrome, the HyperFrames CLI and its skills (`hyperframes skills`
installs the published set under `/home/sprite/.agents/skills`, which
the template's notes have the agent read first), nbb and `start.sh`;
no bundle, no skills, no version. At the first start of a
machine, when `/home/sprite/rragent/dist/rragent.js` is missing,
`start.sh` downloads the newest release's `rragent.tar.gz` and its
checksum (`releases/latest/download/…`, through GitHub's redirect, no
API), or the release that `RRAGENT_UPDATE` in `.env` names (`v<x.y.z>`,
or `trial-<name>` for a pre-release of a branch), verifies and unpacks
it into `dist/`, and rragent swaps in newer releases from there
(Updates, above). A download that fails ends the start with nothing in
place; the service's restart tries again. The shared prompt
(`system-prompt.md`, `deploy/agent/SYSTEM-PROMPT.md`), a bot's text
(`bot.md`) and a task's (`task.md`) come with the bundle; `agent/` is
empty, the first start makes the first bot in it: every new
machine starts as a new bot that offers the skills of
`/home/sprite/skills` and takes the one the person picks, by naming it
in its `AGENT.md`, or works from the job the person describes when no
skill fits; every further bot starts the same way. The skills folder is a
clone of a GitHub repository in the Agent Skills format, one folder per
skill with a `SKILL.md`, made by `start.sh` at the first start of a
machine, one commit deep, from `SKILLS_REPO` at `SKILLS_BRANCH` in
`.env` (`storrito/agentitos` at `main` without them); a clone that
fails is said on stderr and the machine starts without skills until
its next start. Every later start pulls the clone before rragent (20
seconds at most, a failure said on stderr and the clone that stands
stays), so a machine that wakes offers the current skills, and starts
rragent again when it ended with 75 after taking a release (Updates,
above), without the pull. The person's own
skills live beside ours, in `agent/skills/<name>/` of the machine: a bot
writes one with its `skill` tool when the person wants to keep what it
does as a how-to for their other bots (`rragent.skills`, see
[docs/runs-and-skills.md](docs/runs-and-skills.md)), the assistant offers
both folders, and a skill of ours a person wants changed is copied under
a name of their own, so the clone stays as it is. The image carries
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
- `COMPOSIO_SESSION_ID`, only for a Composio session made elsewhere;
  the agent makes its own at start (`rragent.composio-setup`).
- `OPENROUTER_API_KEY`, only when no OpenRouter connector of the
  organization covers the sprite: the models, dictation and the media
  tools all go through the connector, and all fall back to the key
  (`rragent.openrouter`).
- `RRAGENT_YOUTUBE_PROXY`, `http://user:password@host:port` of the
  mobile proxy the `youtube` tool's requests leave through
  (`rragent.youtube`); without it yt-dlp goes out directly, which
  YouTube refuses from a datacenter's address.
- `SKILLS_REPO` and `SKILLS_BRANCH`, optional: the GitHub repository
  (`owner/name`) and branch of the skills `start.sh` clones at the
  first start, `storrito/agentitos` at `main` without them.
- `RRAGENT_UPDATE`, optional: `auto` (what `start.sh` sets without
  it), `off`, or a release's tag to pin the machine to (Updates,
  above).

The sprite itself: `sprite create <name> ghcr.io/storrito/rragent-sprite:<version>
--service http:8080
--service-command http=/home/sprite/rragent/start.sh --service-restart
http=always` with the label `token:<hex>` among its labels, the `.env`
written by an exec, then `sprite update <name> --public`; the page's
link is `<url>?token=<hex>`.

The package is public: a sprite pulls it without credentials, and a
provisioner lists its tags with the registry's anonymous pull token.

The workflows push with the job's `GITHUB_TOKEN`, which reaches a
package only when the package's settings grant the repository's actions
write access (Manage Actions access, once; a package first pushed by a
workflow has it from the start).

Sprites imports an image tag once per organization (two minutes, which
the first sprite of it waits for) and never pulls it again: a sprite
made later from the same tag gets the bytes of that first import,
whatever the registry holds by then. So a tag is never pushed over: a
version is bumped, never rebuilt, and the workflows refuse or skip a
tag that exists. A branch that changes `deploy/image/` gets its image
through the Image workflow, "Run workflow" on the branch, tagged
`<version>-trial.<sha>`, which no provisioner takes; a branch that does
not needs none, its bundle reaches a sprite as a release does, or
pinned by `RRAGENT_UPDATE`. Locally, `docker build -f
deploy/image/Dockerfile -t rragent-sprite:dev deploy/image` builds the
image.

## License

MIT, see [LICENSE](LICENSE).
