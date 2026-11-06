(ns rragent.state-machine
  "A machine, pure: what the shell does with a state map between a
   step and the next, as data. The core (`rragent.codemode`) loads a
   program for its state and evaluates `step` on it; this namespace
   says which tool a call under `:next` names and with what arguments
   (`tool-call`), how an answer goes back into the state
   (`answered`), when the machine is done (`done?`), and what the
   model reads at the end (`text`). The shell that runs the tools is
   `rragent.tools.machine`.

   The state is a map. `:next` is `{:call [...] :as :key}`: the call
   a vector led by a keyword, as the functions of the core answer
   them, `:as` the key the answer goes under, `:result` without. A
   failed call answers `{:error ...}` under that key, with `:status`
   and `:body` when a request answered. `:done` ends the machine
   whatever else the state holds. The Composio search session is the
   shell's to carry: a search's answer names it, the calls after it
   take it, so the program never sees it."
  (:require [clojure.string :as str]))

(def max-chars
  "How much of the final state the model reads."
  10000)

(def calls
  "The calls a program may name, by keyword: the tool that runs each,
   the arguments the tool takes from the vector's values, and whether
   the tool may be run again after a restart caught it in flight
   (`:safe`, a read)."
  {:call {:tool "gateway" :takes [:which :path :opts] :least 2}
   :connectors {:tool "connectors" :takes [] :least 0 :safe true}
   :remember {:tool "remember" :takes [:event :data] :least 1}
   :recall {:tool "events" :takes [:opts] :least 0 :safe true}
   :spit {:tool "spit" :takes [:path :content] :least 2 :safe true}
   :slurp {:tool "slurp" :takes [:path] :least 1 :safe true}
   :composio/search {:tool "composio-read" :takes [:use-case :toolkits] :least 1 :safe true}
   :composio/schemas {:tool "composio-read" :takes [:slugs] :least 1 :safe true}
   :composio/accounts {:tool "composio-read" :takes [] :least 0 :safe true}
   :composio/trigger-types {:tool "composio-read" :takes [:toolkit] :least 1 :safe true}
   :composio/triggers {:tool "composio-read" :takes [] :least 0 :safe true}
   :composio/execute {:tool "composio" :takes [:slug :args] :least 1}
   :composio/connections {:tool "composio" :takes [:toolkits] :least 1}
   :composio/add-trigger {:tool "composio" :takes [:slug :account :config] :least 2}
   :composio/delete-trigger {:tool "composio" :takes [:id] :least 1}
   :mcp/call {:tool "mcp" :takes [:server :tool :args] :least 2}
   :mcp/add! {:tool "mcp-admin" :takes [:name :opts] :least 2}
   :mcp/test {:tool "mcp-admin" :takes [:name] :least 1}
   :mcp/enable! {:tool "mcp-admin" :takes [:name :on?] :least 2}
   :mcp/remove! {:tool "mcp-admin" :takes [:name] :least 1}})

(defn call?
  "A vector led by a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn- read-method?
  [opts]
  (let [method (:method opts)]
    (or (nil? method)
        (= "GET" (str/upper-case (name method))))))

(defn describe
  "The call as a line shows it: `storrito/x {...}`, cut."
  [call]
  (let [k (first call)
        s (str (if (namespace k) (str (namespace k) "/" (name k)) (name k))
               (when (seq (rest call)) (str " " (str/join " " (map pr-str (rest call))))))]
    (if (< 160 (count s)) (str (subs s 0 160) " …") s)))

(defn tool-call
  "The nested tool call of `call`, `{:tool :args :safe}`: a
   `[:storrito/<procedure> args]` goes to the `storrito` tool, the
   others as `calls` says; `session` is the Composio search session
   the shell carries. A throw that says what is wrong with a call the
   shell cannot run."
  [call session]
  (when-not (call? call)
    (throw (js/Error. (str ":next names no call: " (pr-str call) ". A call is what (storrito/…), (call …), (composio/…), (mcp.<server>/… ) and the others answer, a vector led by a keyword."))))
  (let [k (first call)
        values (vec (rest call))]
    (if (= "storrito" (namespace k))
      (do (when-not (or (empty? values) (and (= 1 (count values)) (map? (first values))))
            (throw (js/Error. (str (describe call) ": a storrito/ call is the keyword and one map of arguments"))))
          {:tool "storrito" :args {:procedure (name k) :args (or (first values) {})}})
      (let [{:keys [tool takes least safe]} (or (get calls k)
                                                (throw (js/Error. (str (describe call) ": no call the shell runs; (help) lists them"))))]
        (when (< (count values) least)
          (throw (js/Error. (str (describe call) ": it takes " (str/join ", " (map name (take least takes))) " at least"))))
        (when (< (count takes) (count values))
          (throw (js/Error. (str (describe call) ": it takes " (str/join ", " (map name takes)) " and no more"))))
        (let [named (zipmap takes values)
              [a b c] values]
          (case k
            :call {:tool (if (read-method? c) "gateway-read" "gateway") :args {:which (str a) :path (str b) :opts (or c {})}}
            :recall {:tool tool :args (or a {})}
            :remember {:tool tool :args {:event (str a) :data (or b {})}}
            :spit {:tool tool :args {:path (str a) :content (str b)}}
            :slurp {:tool tool :args {:path (str a)}}
            :connectors {:tool tool :args {} :safe true}
            (:composio/search :composio/schemas :composio/accounts :composio/trigger-types :composio/triggers
             :composio/execute :composio/connections :composio/add-trigger :composio/delete-trigger)
            {:tool tool :safe safe
             :args (cond-> (assoc named :action (name k))
                     session (assoc :session session))}
            :mcp/call {:tool tool :args {:server (str a) :tool (str b) :args (or c {})}}
            (:mcp/add! :mcp/test :mcp/enable! :mcp/remove!)
            {:tool tool :args (assoc named :admin (subs (name k) 0 (- (count (name k)) (if (str/ends-with? (name k) "!") 1 0))))}))))))

(defn next-of
  "The `:next` of `state`, checked: `{:call :as}`, `:as` a keyword,
   `:result` without one; nil when the state has none. A throw for a
   `:next` that is no such map."
  [state]
  (when-let [next (:next state)]
    (when-not (map? next)
      (throw (js/Error. (str ":next is a map {:call [...] :as :key}, not " (pr-str next)))))
    (when-not (contains? next :call)
      (throw (js/Error. (str ":next has no :call: " (pr-str next)))))
    (let [as (:as next :result)]
      (when-not (keyword? as)
        (throw (js/Error. (str ":as names the key the answer goes under, a keyword, not " (pr-str as)))))
      {:call (:call next) :as as})))

(defn answered
  "`state` with the answer of its `:next` in place: `:next` gone, the
   value, or `{:error ...}` for a failed call, under `:as`."
  [state {:keys [as]} {:keys [ok value error status body]}]
  (-> state
      (dissoc :next)
      (assoc as (if ok
                  value
                  (cond-> {:error (str error)}
                    status (assoc :status status)
                    body (assoc :body body))))))

(defn session-of
  "The Composio search session a search's answer names, for the calls
   after it; nil when `value` is no such answer."
  [call value]
  (when (= :composio/search (first call))
    (not-empty (str (get-in value [:session :id])))))

(defn done?
  "Whether the machine ends with `state`: it has `:done`, or no
   `:next`."
  [state]
  (or (contains? state :done)
      (nil? (:next state))))

(defn stuck?
  "Whether the machine ended without handling a failed call: the last
   answer was an error, and the state neither asks anything new nor
   says it is done."
  [state last-as]
  (and (some? last-as)
       (map? (get state last-as))
       (contains? (get state last-as) :error)
       (nil? (:next state))
       (not (contains? state :done))))

(defn- cut
  [s]
  (if (< max-chars (count s))
    (str (subs s 0 max-chars) " … (cut at " max-chars " characters)")
    s))

(defn text
  "What the model reads when the machine ended: what the program and
   its steps printed, how many calls were made, then the final state,
   cut; a state with `:done` shows `:done` first."
  [printed state steps]
  (let [printed (apply str printed)]
    (cut (str printed
              (when (and (seq printed) (not (str/ends-with? printed "\n"))) "\n")
              (if (zero? steps) "" (str "After " steps " call" (when (not= 1 steps) "s") ".\n"))
              (if (contains? state :done)
                (str "Done: " (pr-str (:done state)) "\nState: " (pr-str (dissoc state :done)))
                (str "State: " (pr-str state)))))))
