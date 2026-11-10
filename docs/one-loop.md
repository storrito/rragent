# One loop: the sprite's one conversation

Decided 2026-11-09, twelve decisions one by one. This replaces
[manager.md](manager.md): the manager that briefed hidden worker bots
and relayed their reports is gone, and with it the relay that lost
detail on every hop ("Lydia's short Stories", a thing nobody had
said). The person talks to one conversation per sprite, *the loop*,
which knows everything that happens on the machine; the page shows
that one conversation through views, so the person reads a slice and
the model keeps the whole. The shape is Claude Code's: one context
window, tool-style subagents with a fresh context that return a
compact result and go, background tasks that post their result into
the loop, compaction that writes to files first. The article behind it:
claude.com, "Building agents: when to fold subagents into your main
agent loop".

## 1. The loop

The loop is the former manager conversation, same id, same folder,
same wiki; the structure doc keeps its `manager` key, the code calls
it `loop-id`. `/` lands on it. Its text is `deploy/agent/LOOP.md`,
assembled at every read with the shared prompt (`SYSTEM-PROMPT.md`)
and `wiki/preferences.md`, as the manager's was. The loop runs on
the strongest model with thinking high (the frontmatter of LOOP.md,
changeable in the page). Its tools are talk and judgment: read,
write, edit, bash (the wiki, files), `machine` (a read or a quick
call through a connector), `fetch`, `schedule`, `hook`, `skill`,
`share`, `task` (below), `topic` (below), and `bot` with `list` and
`transcript` alone, to read the chats of before. Not its tools:
`remember`/`recall` (the wiki is its memory, §5), `render`,
`project` (an artifact project from a template), the media tools,
`youtube`, `transcribe`: that is work, and work is a task's.

No worker bots are made any more. `bot create`, `bot message`,
`report`, the status reader and `rragent.messages` go; the bots of
before stay as archived conversations, readable under Settings →
Advanced (§3).

## 2. Tasks

A task is a fresh-context subagent called as a tool: `task` with the
instructions, an optional `topic`, an optional `model` (the default
model unless the work needs more or less) and `wait`. It runs on a
conversation of its own, owned by a durable task `rragent.task` of
the loop's, configured with the shared prompt and `TASK.md`: you
answer a bot, not a person; facts, in the asked shape, no paths, no
proof; your last message is your result; when you need an answer
from the person, that question *is* your result. Its tool set is the
work set: files, `machine`, `render`, `project`, media, `youtube`,
`transcribe`, `fetch`; not `task` (no nesting), not `topic`, not the
loop's `schedule`/`hook`. Several tasks may run at once; the render
gate (one render at a time) stays. A task is continued by its id
(`task` with `id` and new instructions submits into the same
conversation, which keeps its context), and stopped with `task stop`.
Its transcript is a conversation the page can show, under Advanced
only, where the activity list of the pane names every task with its
result line.

`wait: true` blocks the loop's turn until the result, for a short
check; a wait that crosses 60 seconds returns "still running, id N"
and the task goes on in the background. `wait: false` (the default)
returns the id at once. A finished background task posts a
`task-done` entry into the loop (a follow-up input with the result as
content, kind `rragent.task-done`), which wakes the loop for one
turn; a result the loop already took inline is not posted twice (the
task's deliver step and the tool's take are one transaction each on
the same document, so exactly one wins). Stop on the loop ends its
turn only; tasks keep running and deliver later. A `task-done` entry
is model input: the person sees the loop's words and a folded row
"Ran a task" with the result line, the transcript behind Advanced.

## 3. Topics: tags and views

A topic is what the person would call a project: "Content week",
"Pinterest short". The word is topic because `project` is the
artifact-project tool of the templates. Topics are data in the
structure doc, `topics {id {name created archived}}`; the loop
manages them with the `topic` tool (`create`, `rename`, `archive`,
`tag`), the person with the sidebar's "New topic" row or by telling
the loop. The row opens an empty view (`{kind new}`) with the
composer; the first message sent from it gives the view a token,
which the message's entry carries (`rragent.person {name fresh}`),
and the model reads "Lydia (new topic): …" and makes the topic in
that turn. Once a `topic create` or `topic tag` call ran in a turn of
the token, the view is that topic's, and every turn of the token
belongs to the topic.

Every turn carries topics: the person's message carries the topic of
the view it was sent from (`rragent.person {name topic}`, and the
model reads "Lydia (Content week): …"), a trigger carries its job's
topic, a `task-done` its task's; the assistant's and tools' entries
of a turn inherit the turn's topic, and a `topic tag` or `topic
create` call inside the turn adds its topic to the turn. No tag is
stored on assistant entries: the page groups entries into turns and
reads the tags from the input entry and the topic calls. A turn may
carry several topics; it shows in each of their views.

The sidebar is New topic / Topics / Settings. There is no view of the
whole conversation (there was, "All": every topic intertwined, what
the model sees, which is the load on a person's mind the topics are
there to take away, and it was the slowest page to show). A tab that
picked no view shows the first topic of the sidebar's order, the
"New topic" view on a machine without a topic
(`rragent.view/default-view`); the page writes that pick down when
the tab opens, so the view does not change under the person when
another topic becomes the newest. A topic's row shows the turns
tagged with it, with a "Needs you" badge while a card of it is open
(its count from the second on); the topics that need the person come
first, then rows order by last activity, archived topics fold at the
end. There is no view of the open cards alone (there was, "Needs
you": the same card as in its topic with less around it, which
confused): a card is read in its topic. A turn of no topic, a
schedule's or a task's without one, the compaction's, is in no view;
the loop is told to tag what the person should read (LOOP.md). The
net under that: while an open card belongs to no topic, the sidebar
has a row "Without topic" with the badge, the view of the turns of no
topic (`{kind loose}`); the row goes with the card. A card is open
when the loop asked (a ```hiccup block with
a `:send` button) and no person answered since. When later turns pushed
an open card up the feed, the page pins it above the composer, in the
view that shows it: its topic's, never another topic's
or the "New topic" view.

A view shows the newest 200 of its items (`rragent.view/feed-window`)
and a row "Show earlier messages" above them, which brings 500 more
per click (`:view/earlier`, the tab's `:tab-limits`) while the
reader keeps their place (`:ui/keep-place`, the client holds the
first message shown where it is); the window
reaches back to a pinned card, and starts at the newest again with
the next view picked. Every cost of a frame, the server's render and
translation, the browser's decode, walk and diff, grows with the
items shown, and the loop's conversation only grows: a typed letter
re-renders the page in the browser, so the feed's size is the
typing's speed. Settings → Advanced keeps the raw list of the
conversations of before and the tasks' transcripts, and a "New bot"
for the people behind this machine; the header's context ring (how
full the window is, the cost, "Summarize older messages now") and the
composer's model and effort switch show with it only, technical
details a person need not meet. Only a machine with bots of before
the one loop has the Advanced section (the adoption of §9 made
topics of some, `rragent.machine/bots-before?`): a new machine's
settings show no such tab, and a switch left on there does not
count. The composer says which topic it
is in; a card's button sends from the tab's view too. A view is the
tab's, not the URL's.

## 4. Compaction and the wiki

Compaction is invisible to the person: every entry stays, every view
keeps its history, only the model's window shrinks. Before it shrinks,
the loop writes. At 70 percent of the model's window (or
`RRAGENT_CONTEXT_TOKENS`), when the person has been away long enough
for the away compaction, or when they click the context ring, a
durable task `rragent.compaction` runs two steps: one turn with the
instruction "Compaction coming. Write what must survive into the
wiki: every active topic's page with its state, decisions and open
questions; people.md; preferences.md; commit. Then say ready.",
then `compact` with a focus that tells the summary to point at wiki
pages instead of repeating them and to name what still waits for the
person. pi-durable's own compaction stays as the last resort. After a
compaction the loop reads `index.md` and the last log lines first,
as LOOP.md says; the index is not in the instructions, since it
changes at every ingest and would cost the cache each time.

## 5. Memory

The wiki is the loop's only memory: `projects/` becomes
`topics/<name>.md`, with `people.md`, `preferences.md`, `brand.md`,
`decisions.md`, `notes/`, `sources/`, `raw/`, `index.md`, `log.md` as
before, one git commit per change, the loop its single writer. The
chat tools `remember` and `recall` are gone from the loop and from
tasks. `events.edn` stays as the store of programs and machines (the
`[:remember]` and `[:recall]` effects of the `machine` tool), an
event log for code, not memory for the model. Tasks read wiki pages
the loop hands them; they never write the wiki. What a task learned
comes back in its result and the loop files it.

## 6. Triggers

A schedule's fire and a hook's call are inputs into the loop, with
the job's topic: the entries `rragent.schedule` and `rragent.hook`
as the message mode wrote them, now the only mode for the loop; a
Composio event the same way. The loop knows what happened since the
job was set and decides: do it now, start a task, or skip. The runs
gate is inherent, one conversation handles one turn at a time;
missed fires while the sprite slept arrive on wake. No run of the
loop's is ever made. A payload beyond the inline cap goes to a file
under `work/`, the entry names the path.

## 7. People

Unchanged: the first name per browser, "Lydia (Content week): …" in
front of a message, `people.md` and the preferences per person. One
shared window; a card may be addressed to a name. Not identity.

## 8. Models

The loop: `anthropic/claude-opus-5.5`, thinking high (LOOP.md, the
pill in the page). Tasks: the default model, or the `model` of the
call. Prompt caching holds for the loop: pi-ai sets Anthropic's
`cache_control` for `anthropic/*` models through OpenRouter, and
rragent speaks to them through the Anthropic-style base.

No cap on the month's spend: the cap of the first build (a session
doc, a ledger of the month, a notice at 80 percent, refusals at 100)
was removed on 2026-11-10 with the settings' Budget section. What
the answers cost is still shown per conversation (`rragent.costs`,
with Advanced).

## 9. Migration

At the first start of this version a machine with a manager is
adopted in place, once, inside one commit of the structure doc and
marked `migrated`: `agent.sqlite` and its sidecars, every
`AGENT.md` and the wiki are copied to `home.bak-<version>/` first.
Every bot but the manager becomes a topic (its project name, else its
name) and is archived; its artifacts move under the loop's
`artifacts/<topic-slug>/` (the move is recorded, a link of before,
`/bot/<id>/artifacts/<path>`, resolves to the new place); its
schedules are stopped and set again on the loop with the topic in
message mode, its hooks move to the loop's hook doc with their
secrets, so their addresses keep working; its `events.edn` stays in
its folder. Then one handover turn: the loop reads each archived
chat's transcript and writes the topics' wiki pages, and the
compaction task compacts right away, so the loop starts with a clean
window and nothing lost in the views.

## 10. Rollout

The branch goes to a forked sprite: the Trial workflow publishes
`trial-one-loop-<sha>`, the fork's `.env` gets
`RRAGENT_UPDATE=trial-one-loop-<sha>`, a restart takes it and the
migration runs there. Go/no-go: the content-week case re-run, the
migration run twice, one compaction, the cost per turn with the cache
read in the usage. Then a release tag, and every sprite migrates at
its next start.
