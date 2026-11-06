# Machines

How a bot runs a Clojure program with the `machine` tool. Read this
before your first machine; every example is complete and runs as it
stands.

## The contract, in five lines

1. A program's value is a map, the machine's state.
2. Nothing runs inside the program. A function such as
   `(storrito/list-social-accounts {})` answers a *call*, a vector
   `[:storrito/list-social-accounts {}]`.
3. You put one call under `:next`, with the key its answer goes under:
   `{:next {:call (storrito/list-social-accounts {}) :as :accounts}}`.
   The host runs the call and puts the answer under that key.
4. With `(defn step [state] ...)` defined, the host calls `step` with
   the state after every answer and runs the `:next` it returns, until
   a state without `:next`, or with `:done`.
5. You read what the program printed, how many calls were made, and
   the final state.

## A read

```clojure
{:next {:call (storrito/list-social-accounts {}) :as :accounts}}
```

The answer:

```
After 1 call.
State: {:accounts {:socialAccounts [{:platformName "Instagram" :name "carfunnews" ...} ...]}}
```

Most machines are this: one call, the answer, done. The same with any
call: `(call "github" "/user/repos" {:query {:per_page 5}})`,
`(composio/search "send an email" ["gmail"])`, `(mcp.github/search_issues {:query "bug"})`,
`(recall {:event "posted"})`.

## A step machine: read, then act

```clojure
(defn step [s]
  (cond
    (nil? (:accounts s))
    (assoc s :next {:call (storrito/list-social-accounts {}) :as :accounts})

    (nil? (:post s))
    (let [insta (first (filter #(= "Instagram" (:platformName %)) (:socialAccounts (:accounts s))))]
      (assoc s :next {:call (storrito/schedule-social-post {:socialAccountUuid (:uuid insta) :text "Hello"}) :as :post}))

    :else
    (assoc s :done (:socialPostUuid (:post s)))))

{}
```

The initial state `{}` has no `:next`, so the host steps it first.
Step 1 asks for the accounts; the host answers; step 2 picks one and
asks for the post; step 3 says done. Every value a later step needs is
in the state, under a key you chose. `step` is a function of the state
and nothing else: no def, no atom, no clock.

## A loop

```clojure
(defn step [s]
  (let [s (if (contains? s :last)
            (-> s (update :posted conj (:id (:last s))) (dissoc :last))
            s)]
    (if-let [row (first (:todo s))]
      (assoc s :todo (rest (:todo s))
               :next {:call (storrito/create-note {:text (:text row)}) :as :last})
      (assoc s :done (count (:posted s))))))

{:todo [{:text "a"} {:text "b"} {:text "c"}] :posted []}
```

One call per step. Three rows, three calls, then `:done 3`. Two
hundred rows work the same way, and a machine that the host restarts
in the middle goes on at the row it was at: the state after every
answer is kept, and a call that was made is not made again.

## A failed call

A failed call answers `{:error "..."}` under its key, with `:status`
and `:body` when a request answered. The step decides what to do:

```clojure
(defn step [s]
  (cond
    (:error (:status-answer s)) (assoc s :done {:failed (:error (:status-answer s))})
    (:status-answer s)          (assoc s :done (:status (:status-answer s)))
    :else                       (assoc s :next {:call (storrito/status-instagram-story {:storyPostUuid "…"}) :as :status-answer})))

{}
```

A machine that ends right after a failed call it neither handled nor
followed up ends as an error, with the state shown, so that you see
what failed.

## Files: data for a later machine

A program's defs are gone with it. What a later machine needs goes
into a file of your work folder, which you see in the pane:

```clojure
{:next {:call (spit "data/accounts.edn" accounts) :as :path}}
```

and in a later machine:

```clojure
(defn step [s]
  (if (:text s)
    (assoc s :done (count (edn/read-string (:text s))))
    (assoc s :next {:call (slurp "data/accounts.edn") :as :text})))

{}
```

`spit` writes a string as it is and anything else as EDN that reads
back; a function or an object in the data is refused. A big or binary
HTTP body goes straight to a file with `:to-file` in the call's opts,
`(call "drive" "/file" {:to-file "data/photo.jpg"})`, and the state
gets `{:file "data/photo.jpg" :size … :type …}`; an answer over a
megabyte that is not sent to a file fails the call and says so.

## Mistakes the host catches

- `(spit "data/x.edn" v)` or `(remember "seen" {...})` written on its
  own line, as a statement: refused at load, since a call is data that
  does nothing by itself. Put it under `:next`.
- `(:socialAccounts (storrito/list-social-accounts {}))` inside the
  program: gives `nil`, since the function answers a call, not the
  accounts. The accounts are under `:as` in the state of the next step.
- A value that is no map, `(+ 1 2)` or `nil`: refused. `(help)` and
  `(doc f)` answer maps, `{:help "…"}` and `{:doc "…"}`, so a program
  that is just `(help)` is a machine that runs nothing and shows them.
- A step that keeps something in a def or an atom: it is gone or stale
  after a restart. Keep it in the state.
- `(call "github" …)` for a connection that is not there: refused at
  once, with the connections that are.

## Where things live

`rragent.codemode` (the closed context, the functions that answer
calls, `load` and `step`), `rragent.codemode-worker` (the worker),
`rragent.state-machine` (what a call names, the answer into the
state, done, the text), `rragent.tools.machine` (the loop, the memos,
the keys), `rragent.tools.host` (the tools that run the calls).
