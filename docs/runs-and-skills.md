# Runs and skills: one concept for the person, four parts underneath

*Decided 2026-10-28 (Max), after a demo call with a beta customer.*

## The problem

The demo built a template for an Airbnb apartment: whenever a guest
leaves a 5-star review, the bot turns the guest's words into an
Instagram story. It showed where rragent as it was broke down: every
hook call and every schedule landed as a message in the bot's own
conversation (folded to one line since PR #61). At one event a minute
the context is full by the afternoon, the chat is a log nobody reads,
each event re-reads the whole transcript, and one bad turn shapes the
next.

The goal that must not give way: a non-technical person learns no new
concept. Claude.ai's scheduled tasks are a second thing to understand
(a job, its file, its runs, its trigger, a settings page for each). The
person here talks to a bot and nothing else.

## The shape

The bot stays the only thing the person talks to. Behind it four parts
stay separate and combine à la carte, in the sense of Rich Hickey's
decomplecting: each varies on its own, none knows how the others are
made.

| Part | What the person says | What it is |
|---|---|---|
| **Bot** | "my bot" | The standing mind with a page. Owns its definition, hooks, schedules, events, work folder, and its runs. |
| **Skill** | "how-to" | A `SKILL.md` plus files. Ours come from agentitos, read-only. The person's are written by the bot into the machine's own skills folder. |
| **Trigger** | "whenever…" | A hook the machine answers, fed by a Storrito integration trigger or by a schedule that polls. Carries the text a run gets as its task. |
| **Run** | "what happened" | One execution: a conversation started by a trigger, a copy of the bot at that moment. A row in a list, not an entry in the chat. Readable and askable afterwards. |

The person meets none of these words unless they ask.

## What the person sees

1. "Whenever one of my apartments gets a 5-star review, make a story
   from what the guest wrote, in the style we built, and post it to the
   apartment's account." The bot creates a hook, adds the Storrito
   integration trigger (or a schedule that polls when the source has no
   trigger), writes the job into its definition. Nothing new on the page.
2. A review arrives. The machine starts a run: a fresh conversation
   with the bot's definition, tools, events and work folder. It reads
   the review, fills the template, renders, posts, and ends with one
   line. The activity list on the bot's page gets a row,
   *14:02 · Anna, 5 stars · story posted to @apartment_mitte*. The chat
   shows nothing.
3. A review arrives without text. The run cannot write a story, says
   so, and the bot's chat gets one message about it. That is the only
   way a run reaches the chat: when it failed or asked for the person.
4. The person opens Tuesday's run from the list, reads it, types "why
   did you pick that photo?". The run answers; it is a conversation too.
5. Back in the bot's chat: "always take the apartment's cover photo".
   The bot edits its definition; every later run starts from the
   definition as it is then.
6. "Keep this as a how-to, I want the same for my other three
   apartments." The bot writes `skills/airbnb-review-stories/SKILL.md`
   on the machine, the template beside it, the apartment-specific bits
   as the questions the skill asks. The new skill appears in the
   assistant's offer next to ours.

## Where an event runs

Five ways to handle the review that arrives at 14:02. 🟢 good, 🟡
neutral, 🔴 bad.

| | A · Events in the bot's chat (before) | B · A Jobs page of its own (Claude.ai style) | C · The bot delegates each event itself (subagent tool) | **D · Runs under the bot (chosen)** | E · A sub-bot per job |
|---|---|---|---|---|---|
| Concepts the person learns | 🟢 none new | 🔴 job, run, trigger, schedule; a form for each | 🟢 none new | 🟡 sees an activity list; sets nothing up | 🟡 which bot does what, more entries in the sidebar |
| Bot's context stays small | 🔴 full after ~250 events, half a day at one a minute | 🟢 events never touch a chat | 🟡 event plus delegation still land per event | 🟢 runs are rows, not transcript | 🔴 the sub-bot fills instead of the bot |
| Chat stays readable | 🔴 a folded line per event, 1440 a day | 🟢 clean | 🟡 a line per event | 🟢 chat holds conversation only | 🟡 manager clean, sub-bot a log |
| Debug a failed run | 🟡 buried in the chat; compaction eats the details | 🟢 runs page | 🟢 the child's transcript | 🟢 open the run, read it, ask it | 🟡 scroll the sub-bot's chat |
| Cost per event | 🔴 re-reads the whole transcript every time | 🟢 fresh context | 🟡 a manager turn plus the run | 🟢 fresh short context, cached prefix | 🔴 as A, one level down |
| Events per minute | 🔴 serial, one conversation | 🟢 parallel | 🔴 the tool waits for the child, so still serial | 🟢 parallel possible (one at a time chosen for now) | 🔴 serial per sub-bot |
| Failure isolation | 🔴 an error stays in context and shapes the next turn | 🟢 per run | 🟢 per child | 🟢 per run | 🟡 sub-bot poisoned, manager fine |
| Effort from the code as it was | 🟢 done | 🔴 new page, settings forms, new nouns in Storrito too | 🟢 prompt only | 🟡 about three PRs: run spawn, activity view, report-back | 🟢 bot tool exists |
| The person never leaves the chat | 🟢 yes | 🔴 a second place to look and maintain | 🟢 yes | 🟢 activity sits on the bot's page | 🟡 many pages to visit |

C looks tempting because it costs nothing to build, but the subagent
tool blocks the parent's turn until the child answers, so ten reviews
in a minute still queue, and each one still spends a manager turn on a
transcript that keeps growing. E is A with the noise pushed one level
down. B solves the mechanics and loses the premise.

## Where the know-how lives

Independent of the first table. The template, the posting rules and the
apartment-specific answers have to live somewhere other bots can reach.

| | i · Inside the bot (AGENT.md, events, work folder) | **ii · A skill the bot writes (chosen)** | iii · A skill in agentitos (we review and merge it) | iv · Clone the bot or fork the sprite |
|---|---|---|---|---|
| Concepts the person learns | 🟢 none | 🟡 "keep this as a how-to", one sentence | 🟢 none, we do it | 🟡 copies and forks |
| Reuse by another bot | 🔴 explain it again | 🟢 the assistant offers it from the list | 🟢 same list | 🟡 the clone drags the transcript along |
| Reuse on another machine | 🔴 no | 🟡 machine-local until Storrito syncs an org folder | 🟢 every machine pulls | 🟡 fork the sprite, which does not exist yet |
| The customer fixes an edge case | 🟡 tell the bot; this bot only | 🟢 copy our skill, edit the copy, point AGENT.md at it | 🔴 through us, a PR each time | 🟡 per clone |
| Our curation | 🟡 we never see it | 🟡 customer-owned; "send it to Storrito" can feed iii | 🟢 reviewed | 🔴 drift between copies |
| Effort | 🟢 as it was | 🟢 one PR: skill tool, second folder in the offer | 🟡 our time, every skill | 🟡 fork not built |

ii and iii are not rivals. A customer's skill that works for four
apartments is the best source for one of ours; the bot can offer to
send it, and we review it into agentitos. The customer who hates how
our skill handles an edge case copies it and edits the copy; our repo
stays read-only on the machine.

## Why runs end the once-a-minute problem

Rough numbers for a bot whose transcript has grown to 300k tokens. A
review event is about 2 KB; with the fetch, the render and the post,
one handling is a few thousand tokens of transcript.

| | Events in the bot's chat | Runs |
|---|---|---|
| Tokens read per event | the whole transcript, ~300k | definition, skill, event: ~10–20k, mostly a cached prefix |
| Events until the context is full | ~250 at a 1M window, ~4 hours at one a minute | no limit; each run starts empty |
| What the bot remembers of them | whatever compaction kept | the runs' rows and the events the runs wrote |

The last row names the real trade. "The transcript is the memory"
stops being true for events. The bot's memory of its runs is the
activity list plus the events file. That is better than compaction,
which forgets by design, and it is what makes "what happened this
week?" answerable.

## The decisions

Asked one by one, answered by Max on 2026-10-28.

1. **Runs by default.** Every hook call and every schedule starts a run.
   The bot's chat gets a message only when a run failed or reported
   something for the person. `mode: message` on a hook or a schedule
   keeps the old behaviour for the rare, low-volume event that belongs
   in the chat.
2. **Activity in the right pane**, beside the artifacts. A row opens
   the run's transcript in the chat view at `/bot/<id>/run/<rid>` with
   a composer, so the person can ask the run. The sidebar stays for
   bots.
3. **Events instead of facts, in a file the runs share.** What was
   "facts" as entries of the conversation is "events" now: one EDN map
   per line in `bots/<id>/events.edn` (the EDN counterpart of jsonl),
   appended by `remember`, never changed. `recall` reads them back with
   a projection the agent writes as Clojure (SCI) over `events`. The
   file is the bot's, so a run that posted a review notes it and the
   next run sees it. The entries of before are moved into the file
   once, when the bot opens.
4. **Runs act on their own.** No approval step by default: a row added
   to a Google spreadsheet should become a post without a human in the
   loop. A run that must tell the person something reports into the
   bot's chat; a failed run does so by itself.
5. **One run at a time per machine, kept forever.** Runs queue in
   order. Nothing is deleted; a run is a task of the bot's conversation
   in the machine's storage, so the list is paged from there.
6. **The person's skills live on the machine first**,
   `~/rragent/skills/<name>/SKILL.md`, written by a `skill` tool and
   offered beside ours. Sharing across a customer's machines comes
   later through Storrito; the format does not change for that.

## How it is built

- `rragent.events`: the events file, `remember` and `recall` over it,
  also from the REPL; the migration of the `rragent.fact` entries.
- `rragent.runs`: a run is a background task (`rragent.run`) of the
  bot's conversation. It creates the run's conversation owned by the
  task, a copy of the bot's agent with the run's note on top, submits
  the trigger's text, waits for the answer, and ends with the one line
  as its result. One at a time per machine through an in-memory gate
  that a restart re-enters. A failed run submits a report into the
  bot's chat, deduplicated by request id. The list of a bot's runs is
  the scan of its tasks of that kind, newest first.
- `rragent.hooks` and `rragent.schedule` start a run unless the hook or
  the schedule says `mode: message`.
- The page: an activity list in the pane, the run's transcript at
  `/bot/<id>/run/<rid>`.
- `rragent.tools.skill`: the skill tool and the machine's skills folder.
