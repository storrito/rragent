# The manager: one conversation for the person, workers underneath

## The problem

A job with many parts, six themed video templates for one customer,
does not fit one bot. Each template needs its reference image, the
HyperFrames skill, lint and render output and the check frames in the
context: a hundred thousand tokens or more per template, six of them
in one conversation never. And a person who is not technical drowns
in the bots that result: a list of eight bots whose topics they no
longer remember, an artifacts tree to click through, and the urge to
write to a human instead of saying what they want in the chat.

## The shape

The manager is a bot with a role: the conversation the person lands
on, the one that talks and routes, and keeps little in its own
context. It makes a worker bot per unit of work, a durable bot of its
own with its own transcript and folder, hidden under the project in
the sidebar, and briefs it with `bot message`. It never does the work
that produces big outputs itself. What the person must decide reaches
them one thing at a time: one card, their answer, the next card.

Structure lives in pi-durable documents, committed with the action
that changes it: the session document `rragent.bots` names the
manager, each worker's parent and project, the archived and the
deleted bots. Status lives in a document per bot, `rragent.status`,
written by the manager's own reading of a worker's transcript, not by
a projection in code: the bet is that the model gets better at
understanding a chaotic project while a projection stays as naive as
the day it was written. Bot memory stays what it is, the bot's
`events.edn` with `remember` and `recall`.

The manager's behavior is text that ships with every release,
`deploy/agent/MANAGER.md`, assembled anew at every start of the
machine, with what the person asked of their manager in
`PREFERENCES.md` of its folder, which the manager edits when they
tell it. The hard rules the harness enforces win over both.

## What the person sees

The sidebar: the manager first, as the landing chat; "Needs you", a
count of what waits for them; the projects, each with its workers
folded under it; "Other", the bots without a parent, the ones of
before; the settings. Making a bot by hand and the raw list stay for
the advanced, a switch in the settings. Deleting a bot is the
manager's: the person asks it, and it looks at what depends on the
bot first, a template a posting job uses, a schedule, and proposes
what to do with each before anything goes.

## The decisions (Max, 2026-11-04)

1. The manager is a harness role, a record the harness makes on first
   start, not an environment variable. `/` lands on it, its tools
   differ. Its text is `MANAGER.md` from the bundle plus
   `PREFERENCES.md` of its folder, assembled at load; hard rules of
   the harness win on conflict.
2. Structure in pi-durable documents, not in an event table. The
   session document `rragent.bots` holds `hidden`, `manager`,
   `parents`, `projects` and `archived`; a worker's parent is written
   in the commit that makes its conversation. No reconciliation, no
   projection library. (A table `rragent_events` with your own schema
   was designed first and dropped: the exact parts are few and the
   documents already hold them atomically.)
3. The conversation id stays the bot id; projects, templates and
   approvals get ids inside the manager's data.
4. Status by the model: `rragent.status` per bot, `{summary needs
   version as-of-entry at}`, refreshed by the manager for the bots
   whose newest entry is past `as-of-entry`. Cards, "Needs you" and
   the sidebar render from it. A worker may `report` a milestone to
   its parent; that is a shortcut, not the bookkeeping. Delete and
   archive only through the manager, which checks dependents.
5. The runs gate stays (one scheduled or hook run at a time). Worker
   turns run in parallel. Renders take a machine-wide lock, one at a
   time, queued.
6. The manager runs on the strongest model with thinking high, workers
   on the default, the status reading on the cheapest adequate model.
   `bot create` takes `model` and `thinking`.
7. Cards in the manager's answers: one template per card, preview,
   summary "as of", Approve / Change / Open, each button sending a
   text that names the template, so the person never addresses one by
   name. One at a time: the next card after the answer to this one,
   never a wall of them. A board page in the pane may follow.
8. Sidebar: Manager / Needs you / Projects (workers folded) / Other /
   Settings; "New bot" and the raw list behind the advanced switch.
   (Activity across bots: later.)
9. `MANAGER.md` and the generic template-builder ship in the image;
   the guest-review pipeline lives in agentitos.
10. The template contract, approvals and versions, and the rollout to
    the first customer: decided after the overhaul.

## How it is built

- `manager`: the documents (`rragent.bots` with its new keys), the
  manager made at the open when the document names none, its folder
  `bots/<id>/` with `AGENT.md` written from the bundle's `MANAGER.md`
  at every open (its `model` and `thinking` kept from the file as it
  was) and `PREFERENCES.md` appended at read time (`rragent.reload`);
  `/` lands on it; the manager's extension, selected for its
  conversation alone: `bot create` with `parent` (the manager),
  `project`, `model`, `thinking`; `bot archive`, `bot delete` with the
  dependents check; `bot transcript` to read a worker's entries since
  an id.
- `status`: `rragent.status`, the document; the `bot status` action
  that reads the stale workers' chats since the entry their status
  covers, asks the status model for the new one in a conversation of
  the call's own and writes the document; the status in the bot's map
  for the page.
- `sidebar`: the sections, the advanced switch in the settings, the
  delete of a row behind it.
- `render-lock`: the gate in `rragent.render`.
