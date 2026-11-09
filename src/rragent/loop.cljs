(ns rragent.loop
  "The loop's id as the process knows it (docs/one-loop.md), for the
   namespaces that cannot ask the machine, since `rragent.machine`
   requires them: a schedule's fire, a hook's call and a Composio event
   go into the loop as inputs instead of runs (§6), and a compaction of
   the loop writes the wiki first (§4). `rragent.machine/open!` sets
   it when the loop is known."
  (:refer-clojure :exclude [loop?]))

(defonce id
  (atom nil))

(defn loop?
  "Whether `conversation-id` is the loop's."
  [conversation-id]
  (and (some? @id) (= (str conversation-id) (str @id))))
