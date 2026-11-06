# Machines

The `machine` tool runs a Clojure program the model writes, and this
is how it works, by small examples: what a program is, how a call is
made, how a loop goes, what a restart does, and what the model is told.

## A program is a map

The program's value is a map, the machine's state. Nothing runs inside
the program. The functions of the connectors answer a call as data:

```clojure
(storrito/list-social-accounts {})   ; => [:storrito/list-social-accounts {}]
(call "github" "/user/repos")        ; => [:call "github" "/user/repos" {}]
(remember "posted" {:id 4711})       ; => [:remember "posted" {:id 4711}]
```

A program puts one under `:next`, with the key its answer goes under:

```clojure
{:next {:call (storrito/list-social-accounts {}) :as :accounts}}
```

The tool runs the call and puts the answer under `:accounts`. With no
`step` defined, the machine is done, and the model reads the state:

```
After 1 call.
State: {:accounts {:socialAccounts [{:platformName "Instagram" ...} ...]}}
```

That is the one-shot read, most of what a bot does with the tool.

## A step function

With `step` defined, the tool calls it with the state after every
answer, and runs the `:next` of the state it returns, until a state
without `:next`, or with `:done`:

```clojure
(defn step [s]
  (cond
    (nil? (:accounts s)) (assoc s :next {:call (storrito/list-social-accounts {}) :as :accounts})
    (nil? (:post s))     (let [insta (first (filter #(= "Instagram" (:platformName %)) (:socialAccounts (:accounts s))))]
                           (assoc s :next {:call (storrito/schedule-social-post {:socialAccountUuid (:uuid insta) :text "Hello"}) :as :post}))
    :else                (assoc s :done (:socialPostUuid (:post s)))))
{}
```

An initial state without `:next` is stepped first. One call per step;
`step` is a function of the state and nothing else.

## A loop

```clojure
(defn step [s]
  (let [s (if (contains? s :last) (-> s (update :posted conj (:id (:last s))) (dissoc :last)) s)]
    (if-let [row (first (:todo s))]
      (assoc s :todo (rest (:todo s)) :next {:call (storrito/schedule-social-post (post-of row)) :as :last})
      (assoc s :done (count (:posted s))))))
{:todo rows :posted []}
```

Two hundred rows, two hundred steps, two hundred calls, each a nested
tool call of the machine tool under its key, `c0`, `c1`, …, which
pi-durable records before it runs and after it settles. The state
after each answer is a memo of the call, `s0`, `s1`, …

## A failed call

A failed call answers `{:error "…"}` under `:as`, with `:status` and
`:body` when a request answered, and `step` runs with it. The step
decides: retry, skip, or `:done` with what it has.

```clojure
(defn step [s]
  (if (:error (:post s))
    (assoc s :done {:failed (:error (:post s))})
    ...))
```

A step that neither handles the error nor asks for anything new ends
the machine with an error result that says so, the state included.

## A restart in the middle

The machine died after call 120 of 200. On the next start pi-durable
runs the `machine` call again, since the tool is replay-safe. It loads
the program again, which is pure, steps the initial state, and then,
for each step, asks the memo for the state after that step and the
nested call under that step's key. Memo `s119` is there, call `c119`
is there, finished with its result, and so on up to the one that was
interrupted. A call that was in flight when the process died follows
its tool's rule: a GET or a read at Composio is made again, a POST is
abandoned and answers `{:error "interrupted"}`, which the step sees.
Nothing is made twice, and the pure code that runs again costs
nothing.

There is no replay of the program's logic, no determinism it has to
keep, no journal of its own: the state is data, pi-durable keeps the
data and the calls, and a step is a function of the state.

## Files, events, big answers

A program's defs are gone with it; what the model cannot see in its
conversation after a summary it must not depend on. Data for a later
machine goes into a file, named by the model and visible in the pane:

```clojure
{:next {:call (spit "data/accounts.edn" accounts) :as :path}}      ; one machine
{:next {:call (slurp "data/accounts.edn") :as :text}}              ; a later one, then (edn/read-string (:text s))
```

`(remember "posted" {...})` and `(recall {:event "posted"})` are calls
too, the bot's events as data. A big or binary HTTP body goes to a file
with `:to-file` in the call's opts, and the state gets `{:file :size
:type :status}` instead of the body; an answer over a megabyte fails
the call and says so.

## What the model is told

- A program's value is its state, a map. A call is data under `:next`.
- `step`, when defined, is called after every answer; one call per
  step; `:done` or no `:next` ends it.
- Keep what a step works out in the state, not in defs or atoms.
- A failed call is `{:error ...}` under its key; the step decides.
- Big data in files; `:to-file` for bodies.
- `(help)` and `(doc f)` are pure and answer maps.

Where it is: `rragent.codemode` (the closed context, the functions
that answer calls, `load` and `step`), `rragent.codemode-worker` (the
worker, `load!` and `step!`), `rragent.state-machine` (what a call
names, the answer into the state, done, the text),
`rragent.tools.machine` (the loop, the memos, the keys),
`rragent.tools.host` (the tools that run the calls).
