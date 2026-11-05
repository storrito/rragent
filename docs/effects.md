# Pure programs, effects as data

The `eval` tool runs Clojure programs the model writes. A program is
pure, or at least idempotent: it reads, it computes, and what would
change something it answers as data. The host runs those effects,
each once, also when the machine restarts in the middle. This is how
it works, by small examples.

## The rule

Inside the program: queries. `(storrito/list-social-accounts {})`,
`(call "github" "/repos")`, `(composio/execute! "GOOGLESHEETS_GET_ROWS" {...})`,
`(mcp.github/search_issues {...})`, `(recall ...)`. They answer at
once, and they may run again.

As the value of the last form: effects. A vector of vectors led by a
keyword:

```clojure
[[:storrito/schedule-social-post {:socialAccountUuid "…" :text "…"}]
 [:composio/execute "GMAIL_SEND_EMAIL" {:to "a@example.com" :subject "Posted"}]
 [:remember "posted" {:id [:plan/result 0 :socialPostUuid]}]]
```

Nothing of that happens in the program. The host runs it afterwards.

## One effect

```clojure
(def accounts (:socialAccounts (storrito/list-social-accounts {})))
(def insta (first (filter #(= "Instagram" (:platformName %)) accounts)))
[(storrito/schedule-social-post {:socialAccountUuid (:uuid insta) :text "Hello"})]
```

Two reads, one effect. `storrito/schedule-social-post` is a command of
the catalog, so calling it does not post: it answers
`[:storrito/schedule-social-post {...}]`, and the program makes that
its last form. The answer the model reads:

```
=> a plan of 1 effect
Plan of 1 effect: 1 done.
1. storrito/schedule-social-post {:socialAccountUuid "…", :text "Hello"}: done {:socialPostUuid "…"}
```

## A loop

```clojure
(def rows (:rows (composio/execute! "GOOGLESHEETS_GET_ROWS" {:spreadsheet_id "…"})))
(def new (remove posted? rows))
(vec (for [row new]
       (storrito/schedule-social-post (post-of row))))
```

Two hundred rows, two hundred effects, one program run. The host runs
the effects in order under keys `e0`, `e1`, …, each a nested tool
call pi-durable records before and after.

## A result feeding a later effect

The effects run one after the other, each seeing the results before
it. A placeholder names a part of an earlier result:

```clojure
[[:storrito/create-draft {:text "…"}]
 [:remember "drafted" {:uuid [:plan/result 0 :draftUuid]}]]
```

`[:plan/result 0 :draftUuid]` is replaced by the `:draftUuid` of the
result of effect 0 when effect 1 runs. A placeholder that points at
itself or forward is refused before anything runs.

## A failure

The first failure ends the plan. The effects after it are not run,
and the answer says so, as an error result the model reads:

```
Plan of 3 effects: 1 done, failed at effect 2, the rest not run.
1. storrito/create-draft {...}: done {:draftUuid "…"}
2. composio/execute "GMAIL_SEND_EMAIL" {...}: failed: COMPOSIO_MULTI_EXECUTE_TOOL failed: gmail is not connected
3. remember "drafted" {...}: not run
```

The model then decides: connect Gmail and run a program for the rest,
or drop the mail.

## A restart in the middle

The program ran, the plan started, the machine died after effect 1.
On the next start pi-durable runs the `eval` call again, since the
tool is replay-safe. The tool does not run the program: it kept the
program's answer, text and plan, as the call's memo the moment it had
it. It runs the plan again from effect 0. Effect 0 was made under key
`e0`, and a nested call under a key that was already made comes back
as it was, finished with its result. Effect 1 the same. Effect 2 was
never started, so it runs now. Nothing ran twice.

The one case the host cannot settle is an effect that was in flight
when the process died: a POST that may or may not have happened. It
is abandoned and reported as failed, never repeated. An idempotency
key on the command would close that gap; until then the model reads
the line and looks.

## Why the program must not change anything

The memo is written after the program ran. A machine that dies while
the program runs has no memo, and the next start runs the program
again. That is harmless for a program that reads and computes, and it
is the whole point of the rule: a write inside the program is the one
thing this design cannot protect. Which Storrito procedures are
commands, the verb says, and they answer their effect instead of
calling. Which Composio and MCP tools change something, the model
judges from their names and descriptions, the same way it decided to
use them: a tool that sends, creates, posts or deletes is an effect,
`[:composio/execute …]` or `[:mcp/call …]`; a tool that lists,
searches or fetches is a call.

## What is not here

- No reading of a write's result inside the program. A draft made and
  then listed is two programs, or a placeholder.
- No retry inside the program for an effect. The host reports, the
  model decides.
- No preview before the plan runs. The lines are the record, after.

Where it is: `rragent.plan` (what a plan is, its check, the
placeholders, the serial run, the report), `rragent.tools.eval` (the
memo, the keyed nested calls), `rragent.tools.effects` (the tools that
run the effects, which only tools call), `rragent.codemode` (the
functions that answer effects instead of calling).
