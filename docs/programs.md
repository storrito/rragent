# How a REPL program runs

The `eval` tool runs Clojure programs the model writes. This is how a
program gets its calls made, why nothing is made twice when the
machine restarts in the middle, and what that asks of the program. It
is written for someone who wants to reason about it, so it goes by
small examples and shows what the host sees at each step.

## The shape

Two parts. The *core* is the program, evaluated by SCI in a worker
thread that reaches nothing outside itself: no network, no files, no
clock worth the name. The *shell* is the `eval` tool in the main
thread, which owns every effect. Functional core, imperative shell,
with one twist: the core does not get its inputs up front, it asks for
them as it goes.

A call in the program, `(call "echo" "/x")` or `(storrito/list-social-accounts {})`,
is a *need*. The binding looks the call up in a table of answers the
shell gave for this form. When the answer is there, the call returns
it. When it is not, the binding notes the need and throws. The form
stops, the shell makes the call as a nested tool call of its own, puts
the answer into the table, and runs the form again from its start.

That is the whole mechanism. Everything else follows from it.

## One call

```clojure
(:status (call "echo" "/x"))
```

Attempt 1. The table is empty. `call` notes the need
`{:tool "gateway-read" :args {:which "conn_echo" :path "/x" :opts {}}}`
and throws. The shell makes the request and records the answer:

| call | request | answer |
|---|---|---|
| 0 | gateway-read conn_echo /x | `{:status 200 :headers {…} :body {…}}` |

Attempt 2. `call` is the first call of the form, position 0, the
table has an answer at 0, `call` returns it. `:status` gives 200. The
form is done.

Two attempts for one call. The second attempt made no request.

## Two calls in one form

```clojure
(let [a (call "echo" "/a")
      b (call "echo" "/b")]
  [(:status a) (:status b)])
```

Attempt 1: `/a` is unanswered, need, throw. The shell answers call 0.
Attempt 2: `/a` is answered at position 0; `/b` is unanswered at
position 1, need, throw. The shell answers call 1.
Attempt 3: both answered, the form is done.

A form with N calls runs N + 1 times. Each rerun replays the earlier
calls from the table in memory, so what costs is the pure work the
form does again before each call, not the calls.

## The table is by position, so the order matters

The shell answers call k by its position in the form, and it checks
on the rerun that the program asks the same thing at k. This program
breaks:

```clojure
(let [k (rand-int 1000)]
  (call "echo" (str "/" k)))
```

Attempt 1 asks `/417`, the shell answers it. Attempt 2 draws a new
number and asks `/92` at position 0, where `/417` is recorded. The
form ends with an error that says so: "The program asked something
else than before at call 1 of this form".

The rule: given the same answers, a form must make the same calls in
the same order. A program that computes from its answers does this by
itself. What breaks it is randomness and the clock before a call. Do
not draw a random id in the program; take ids from the data, or ask a
service for one (`(storrito/generate-uuid)` is a call, so its answer
is recorded like any other).

## A `try` around a call

The need is a throw, and the model likes to write this:

```clojure
(try
  (call "echo" "/x")
  (catch :default e
    (println "failed:" (ex-message e))
    nil))
```

In attempt 1 the `catch` catches the need. The program prints
"failed: Waiting for the host …" and goes on with `nil`. That attempt
is garbage. It does not matter: every attempt runs in a *fork* of the
context, and an attempt that noted a need is thrown away whole, the
fork with it, whatever it printed or defined after the throw. The
shell sees only the noted need, answers it, and attempt 2 runs the
`try` again with the answer in place, which returns the status and
never reaches the `catch`.

What the `catch` is for still works. A call that failed (a 400, a
service that is down) is recorded as a failure, and on the rerun the
binding throws it at the call as an ordinary error. The `catch` then
catches the real thing.

One hole: an attempt that is thrown away cannot undo a `swap!` on an
atom made in an earlier form. Keep what a form works out in `def`s,
not in atoms.

## Forms are the checkpoints

A program is a sequence of top-level forms, run one at a time. When a
form is done the shell keeps it as a memo of the call, `f0`, `f1`, …:
what it printed, its value, and the vars it defined, as EDN.

```clojure
(def accounts (:socialAccounts (storrito/list-social-accounts {})))   ; f0
(defn insta? [a] (= "Instagram" (:platformName a)))                     ; f1
(def chosen (filterv insta? accounts))                                   ; f2
(count chosen)                                                           ; f3
```

| memo | kept |
|---|---|
| f0 | printed "", value `#'user/accounts`, vars `{accounts "[{:platformName \"Instagram\" …}]"}` |
| f1 | printed "", value `#'user/insta?`, `:redo true` |
| f2 | vars `{chosen "[…]"}` |
| f3 | value `"2"` |

`f1` says `redo` because a function does not read back as EDN. The
test is simple: `(= v (edn/read-string (pr-str v)))`. Data passes,
functions, regexes and objects do not.

## A restart in the middle

The machine dies while `f2` runs. On the next start pi-durable sees an
`eval` call that is replay-safe and not settled, and runs its
`execute` again. The shell then:

1. Finds `f0` in the memos: no run, it hands the vars to the fresh
   worker, which interns `accounts`.
2. Finds `f1` marked `redo`: runs the form again. It has no calls, so
   it just defines `insta?` again.
3. Finds no memo for `f2`: runs it. Any call it makes is a nested
   call under the key `c2-<position>`, and here is the part pi-durable
   does for us: a nested call under a key that was already made comes
   back as it was, finished with its result, or still running. So a
   form that was in the middle of its calls walks the ones it had made
   from their keys and continues with the next.
4. Runs `f3`.

Nothing was made twice. The only thing that ran twice is pure code.

Two cases inside step 3. A call finished before the crash comes back
with its result. A call that was in flight when the process died
follows the policy of its tool: `gateway-read` is `replay: "safe"`, a
GET or a Storrito query is made again; `gateway` is not, a POST is
abandoned and the program gets an error at that call saying it was
interrupted, which it may catch or let end the form. A command whose
fate is unknown is reported, never repeated.

## Reads and writes

The binding picks the tool from the request: a GET, or a Storrito
procedure that only reads (`list-`, `status-`, `get-`, `search-`,
`find-`, `generate-`), goes to `gateway-read`; everything else to
`gateway`. The program does not see the difference, the replay policy
does. The verb decides until the catalog carries a kind of its own.

## A loop over rows

```clojure
(def rows (:rows (composio/execute "GOOGLESHEETS_GET_ROWS" {...})))
(doseq [row rows]
  (storrito/schedule-social-post (post-of row)))
```

The first form is one call. The second form makes one call per row:
200 rows, 201 attempts, each replaying the posts made so far from the
table and making the next. The 201 attempts run `post-of` again for
every row before the current one, which is cheap pure work. A restart
at row 120 resumes the second form, walks 120 keyed calls, posts the
121st. Nothing is posted twice.

When the pure work per row is heavy, split the form: compute the
posts into a `def` in one form, loop over them in the next. The memo
of the first form keeps the computed posts, the second form's reruns
only walk calls.

## A program is one of its own

Every program starts from a fresh fork of the base context. A `def`
from an earlier program is not there:

```clojure
;; program 1
(def accounts (:socialAccounts (storrito/list-social-accounts {})))

;; program 2, later
(count accounts)   ; Unable to resolve symbol: accounts
```

This is deliberate. The conversation gets summarized, and after that
the model cannot know what an earlier program defined. A `def` it
cannot see is a `def` it will use wrongly. A file it named is in the
transcript, in the summary, in `ls`, and in the pane:

```clojure
;; program 1
(spit "data/accounts.edn" (:socialAccounts (storrito/list-social-accounts {})))

;; program 2
(def accounts (edn/read-string (slurp "data/accounts.edn")))
(count accounts)
```

`spit` writes a string as it is and anything else as EDN, and refuses
what would not read back, with the same test the memos use. `slurp`
answers the text. Both are needs, inside the bot's work folder only,
the same files `read`, `bash` and the pane see.

## Events

`(recall …)` reads the bot's events, `(remember …)` adds one. The
shell reads the events once when a form starts and gives the same
snapshot to every attempt of that form. Otherwise this form would
diverge, since attempt 2 would count the event attempt 1 remembered:

```clojure
(remember "seen" {:n (count (recall))})
```

Within the form, what it remembers is added to its own view at once,
so a `recall` after a `remember` sees it.

## What to tell the model, and what to watch

- A call is made once, by the host, also across a restart. A form
  runs again from its start until its calls are answered.
- Same calls in the same order: no randomness, no clock, no `swap!`
  before a call.
- `try` catches failures of calls. A need is not catchable in any way
  that matters.
- Keep a form's results in `def`s. Keep data for a later program in a
  file with `spit`.
- A long loop is fine; heavy per-row work goes into its own form.

Where it is: `rragent.need` (the table, the throw, the position
check), `rragent.codemode/evaluate-form` (one form in a fork, the
memo's contents), `rragent.tools.eval` (the loop over forms and
attempts, memos, keys), `rragent.tools.host` (the tools that answer
needs). The tests show every case above with the fake gateway:
`codemode_test`, `codemode_worker_test`, `tools_test`.
