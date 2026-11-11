# The bots: one conversation per job

Decided 2026-11-10, a day after [one-loop.md](one-loop.md) went out:
the bots are back. A machine has several bots, each a conversation of
its own that the person talks to on its own page; there is no manager
over them ([manager.md](manager.md)) and no one conversation in their
place. What the one conversation brought and proved is kept: tasks,
the wiki written before a compaction, the sidebar's marks, the feed's
window.

Why back (Max, 2026-11-10): a context window per matter again, in
place of one window for everything. What a bot learns that another
should find goes into the wiki, and a bot looks there when it needs
it, instead of every turn carrying every matter.

In pi-durable's terms: a conversation is the unit that has a context
window, a compaction, an inbox (one turn at a time) and a provider
cache of its own. A bot is one conversation. A topic of the one
conversation was a tag the page read off the turns; pi-durable knew
nothing of it, so every topic shared the one window, the one
compaction and the one turn at a time.

## 1. A bot

A bot is an ownerless conversation of the machine's one storage
(`rragent.machine`): the root conversation is the first, every
further one is made by the person ("New bot" in the sidebar) or by a
bot (`bot create`). Its folder is `bots/<id>/`: `AGENT.md`, its file,
`events.edn`, what it noted, and `work/` with `artifacts/`,
`uploads/`, `programs/`, `imports/`. The machine makes no bot of its
own and lands on none by rank: `/` goes to the bot used last. Every
bot can be renamed and deleted from its row, but the last one.

## 2. A bot's instructions

Assembled at every read (`rragent.reload`), nothing of it copied
into the bot's file:

1. `deploy/agent/SYSTEM-PROMPT.md`, what every conversation of the
   machine works by, a bot's and a task's: skills, files, the
   `machine` tool, programs, artifacts, the player.
2. `deploy/agent/BOT.md`, what a bot is: its file, a new bot and the
   skills it takes, tasks, runs, the other bots, the wiki, the notice
   before a compaction, cards.
3. The body of its file, `## Your job`.
4. The body of the skill its file names.
5. `preferences.md` of the wiki, what the person asked of their bots,
   under `## Your preferences`.

`<agent>` in the texts is the bot's folder, `<skill>` its skill's,
`<wiki>` the machine's wiki (`rragent.definition`).

## 3. Tasks, and who sees which tool

A task is a fresh-context helper a bot starts with its `task` tool
(`rragent.tasks`, `rragent.tools.task`): a conversation of its own,
owned by a durable task of the bot's, configured with the shared
prompt and `TASK.md`, the bot's `work/` as its folder, the bot's own
model unless the call names the other one the page offers. It knows
nothing of the bot's chat; its last message is its result. Without
`wait` the call answers the task's id at once and the result arrives
in the bot's chat as a `task-done` entry, which wakes the bot for one
turn; with `wait: true` the call waits up to a minute and takes the
result itself, and the two never both happen (the `Taken` document).
A task is continued by its id and stopped with `task stop`. Several
run at once; one render at a time all the same.

What a conversation's model sees is its role's (`rragent.roles`,
pi-durable's `modelTools`):

- a bot: every tool but `subagent`. It hands work to tasks, which go
  on beside it.
- a run of a bot's (below): every tool but `task`. Nobody reads its
  chat, so a result delivered there would wake the bot for a turn
  nobody asked for; it does the job itself and hands a long piece to
  a `subagent`, whose answer it waits for.
- a task: the tools of work alone, files, `machine`, `project`,
  `render`, the media tools, `youtube`, `transcribe`, `fetch`; no
  task of its own, no schedule, no hook, no `remember`, no `bot`.

## 4. The wiki

One wiki per machine, `shared/wiki/` of the home (`rragent.wiki`): a
git repository of Markdown pages after Karpathy's LLM wiki, which
every bot reads, searches and writes, and the person sees in every
bot's pane under `wiki/`. `raw/` holds what came as it came; the
pages are `topics/<name>.md` (one per matter, a bot's own the one of
its job), `sources/`, `notes/`, `decisions.md`, `brand.md`,
`people.md`, `preferences.md`; `index.md` is the catalog, `log.md`
the timeline. A bot writes as it goes, reads the index and the page
before it answers from memory, and learns from the pages what
another bot found out.

Several bots share the repository, and there is no lock but git's
own: BOT.md tells a bot to commit its own files and no others (`git
add <files>`, never `-A`), to read a page before it writes it, and
to try again when git says the index is locked. Tasks never write
the wiki: they answer, the bot files.

A bot's events (`remember`, `recall`, `events.edn`) stay what they
were, the bot's own record of what happened, for its runs and its
programs. The wiki is what is known and decided, for everyone.

## 5. Compaction

Before a bot's window shrinks, the bot writes
(`rragent.compaction`). At 70 percent of the model's window (or
`RRAGENT_CONTEXT_TOKENS`), when the person has been away long enough
(`rragent.away`), or when they click the context ring, a durable
task of the bot's conversation submits one notice: "Compaction
coming. Write into the wiki everything that must survive and is not
there yet … Then answer ready." Then it asks pi-durable for the
compaction, with a focus that tells the summary to point at the wiki
pages instead of repeating them and to name what still waits for the
person. The notice shows in the chat as one folded row.

Only a bot gets the notice, a conversation nobody owns
(`rragent.compaction/bot?`). A task's, a run's and a subagent's are
compacted at once: nobody talks to them, and what they learned goes
back in their answer.

The bot's answer to the notice read the whole context once more.
The away watch does not count it (`rragent.away/forget!`): counted,
it had the conversation compacted a second time ten minutes later,
notice and turn included.

## 6. Runs, and the other bots

A schedule's fire and a hook's call start a run (`rragent.runs`): a
conversation of its own with the bot's definition, which does the job
and ends with one line in the bot's activity. A schedule or a hook
made with `mode: message` arrives in the bot's chat instead. A
Composio event is a run of the bot whose trigger it is.

The `bot` tool (`rragent.tools.bot`): `create` makes a bot with a
name, a job, a skill, a project, and a model the page offers (an id
the maker knows from its training is refused, as for a task); `list`
names the bots; `message` sends one a message and brings its answer
back as a message from that bot; `transcript` reads a bot's chat,
folded, when the wiki does not answer. No bot deletes or archives
another: that is the person's, on the page.

## 7. Models

A new bot's file names `anthropic/claude-opus-5.5`
(`rragent.definition/default-model`), thinking high; the page's
switch under the composer offers it and `openai/gpt-6.1-sol`
(`rragent.models/choices`). A bot made before keeps the model its
file names. A task runs on its bot's model, or on the other of the
two when the call says so.

## 8. What the one conversation adopted goes back

The releases 0.1.56 to 0.1.58 made one conversation of a machine:
every bot but that one became a topic and was archived, its artifacts
moved under the one conversation's `artifacts/<topic>/`, its
schedules and hooks were set again there as messages. At the first
open of this version (`rragent.migration/restore!`, once, marked
`restored` in the structure), after the database, every bot's
definition and the wiki were copied to `<home>.bak-<version>` next
to the home (`backup!`, for a home the adoption marked):

- every adopted bot that is still there is taken off the archived
  list and is in the sidebar again;
- a schedule or a hook the adoption moved is the bot's again, as a
  run: the mode of before is kept nowhere, and a run is what a bot's
  job was written for. The adoption wrote `message` and the topic
  into every one it moved, and so did the conversation into the ones
  it set itself since, a reminder in a bot's topic for one; those
  stay with the conversation, where their words reach the person.
  The two are told apart by their age (`moved?`): a moved schedule's
  task is older than the task of the adoption's handover, a moved
  hook was made before the adoption;
- the artifacts of a topic one bot had come back into the bot's
  `artifacts/`, with what was made there since; a name taken in the
  bot's folder stays where it is;
- a note is appended to each restored chat, read by the model at its
  next turn, which says what happened, where the wiki page of the
  matter is, and what came back. No turn is started.

Two bots of one project were adopted as one topic, their artifacts in
one folder. Of such a topic only the schedules and hooks that carry a
bot's id in their name go back (the adoption's way out of a name
taken); the folder stays with the conversation.

The one conversation stays, with every topic's turns, as a bot named
Assistant (`rragent.bots/first-chat-text`): its file is written once,
its model and thinking kept, its other schedules and hooks marked
`message`, which is how every one of them reached it whatever its
mode said. A topic made since the adoption gets no bot: its turns
are read there, each of the person's messages with its topic behind
the name. The wiki it kept in its folder becomes the machine's, one
rename, with a link where it lay. A machine that still has a manager
of 0.1.55, never adopted, gets the file and the wiki's move alone.

An address of before still leads to its file
(`rragent.page/moved-artifact`): a bot's artifact that stayed under
the conversation's topic folder, and a path into a topic folder that
went back to its bot.

## 9. The page

The sidebar is "New bot", the bots, the settings. A bot's row says
what the person should know at a glance: "Needs you" while a card of
the bot waits for their answer (a ```hiccup block with a `:send`
button that nobody answered since; `rragent.view/needs`), with the
count from the second on; and a pulsing dot while the bot works, a
run of it going or a task (Tobias, 2026-11-10: nothing showed that
the model works on a matter). The feed of a bot ends in the
"Working" row while a run goes and in a row per task that goes, "A
task works on it · …": the bot is ready while its tasks work.

The header's context ring (how full the window is, the cost,
"Summarize older messages now") and the model and effort switch
under the composer are every bot's. There is no advanced switch.
The pane lists the artifacts, the programs, the wiki and the
person's skills, and the activity: the runs and the tasks, each with
its transcript.

A feed shows the newest 200 of its items (`rragent.view/feed-window`)
and a row "Show earlier messages" above them, which brings 500 more
per click while the reader keeps their place. Every cost of a frame
grows with the items shown. The composer's draft stays out of the
browser's store (`rragent.client.draft`): a typed letter renders
nothing. A frame equal to the one a tab last got is not sent
(`rragent.rframes.sse/stream-handler`). A change of what one tab
shows goes to that tab alone (`rragent.page/affected`).
