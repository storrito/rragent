(ns rragent.plan
  "A plan: the effects a program answers, run by the host after it.
   After Nexus (github.com/cjohansen/nexus): an effect is a vector led
   by a keyword, `[:storrito/schedule-social-post {...}]`, an
   instruction as data, and a plan is a vector of them, the value of
   the last form of a program of the REPL (`rragent.codemode`). The
   program itself is pure, or at least idempotent: it reads, it
   computes, and what would change something it answers as an effect
   instead of doing it. The host turns each effect into a nested tool
   call of the eval tool under a key of its own (`rragent.tools.eval`,
   the tools of `rragent.tools.effects`), so that a machine that
   restarts in the middle of a plan picks up where it was: an effect
   that was done is found by its key and not done again, and the
   program, which changes nothing, may run again. The effects run one
   after the other, each seeing the results before it: a placeholder
   `[:plan/result 0 :uuid]` in the arguments of an effect stands for
   that part of the result of the effect at that index, replaced when
   the effect runs. The first failure ends the plan, the effects after
   it are not run; what each did is the answer, as data for the page
   and as lines for the model.

   The effects: `[:storrito/<procedure> args]` a command of the
   Storrito catalog; `[:call which path opts]` a request through the
   gateway, as `(call ...)` makes it; `[:composio/execute slug args]`,
   `[:composio/connections toolkits]`, `[:composio/add-trigger slug
   account config]`, `[:composio/delete-trigger id]` as the composio/
   functions of the REPL; `[:mcp/call server tool args]` a tool of an
   MCP server; `[:remember event data]` an event of the bot."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def max-action-chars
  "How much of an effect a line shows."
  160)

(def max-value-chars
  "How much of a result a line shows."
  400)

(def kinds
  "The effects the host runs, by keyword: how many values follow the
   keyword at most, and what each is for the check."
  {:remember {:takes [:event :data] :least 1}
   :call {:takes [:which :path :opts] :least 2}
   :composio/execute {:takes [:slug :args] :least 1}
   :composio/connections {:takes [:toolkits] :least 1}
   :composio/add-trigger {:takes [:slug :account :config] :least 2}
   :composio/delete-trigger {:takes [:id] :least 1}
   :mcp/call {:takes [:server :tool :args] :least 2}})

(defn action?
  "A vector led by a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn kind
  [action]
  (first action))

(defn arguments
  "The map after the keyword of a storrito/ effect, nil without."
  [action]
  (second action))

(defn known?
  "Whether the effect's keyword is one the host runs: a storrito/
   procedure, or one of `kinds`."
  [_known action]
  (let [k (kind action)]
    (boolean (or (= "storrito" (namespace k))
                 (contains? kinds k)))))

(defn- cut
  [s max]
  (if (< max (count s))
    (str (subs s 0 max) " …")
    s))

(defn describe
  "The effect as a line shows it: `storrito/x {...}`, cut."
  [action]
  (let [k (kind action)]
    (cut (str (if (namespace k) (str (namespace k) "/" (name k)) (name k))
              (when (seq (rest action)) (str " " (str/join " " (map pr-str (rest action))))))
         max-action-chars)))

(defn of
  "The plan in `value`, the value of the last form of a program: a
   vector (or seq) of effects the host knows, or one such effect, as a
   vector of effects; nil for any other value, which is data, a
   sequence of vectors led by keywords none of which the host knows
   included. A sequence of effects among which one is not known is a
   mistake, and a throw says which."
  [known value]
  (cond
    (action? value)
    (when (known? known value) [value])

    (and (sequential? value) (seq value) (every? action? value))
    (let [actions (vec value)]
      (cond
        (every? (partial known? known) actions) actions
        (some (partial known? known) actions)
        (let [[index action] (first (keep-indexed (fn [i a] (when-not (known? known a) [i a])) actions))]
          (throw (js/Error. (str "A plan of " (count actions) " effects, but effect " (inc index) ", " (describe action)
                                 ", is none the host runs: the effects are storrito/<procedure>, call, composio/execute, "
                                 "composio/connections, composio/add-trigger, composio/delete-trigger, mcp/call and remember."))))
        :else nil))

    :else nil))

(defn placeholder?
  "`[:plan/result index & path]`."
  [x]
  (and (vector? x) (= :plan/result (first x))))

(defn placeholders
  "The placeholders anywhere in the effect's arguments."
  [action]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when (placeholder? x) (swap! found conj x))
                     x)
                   (vec (rest action)))
    @found))

(defn- data?
  "Arguments the host can send: a map or nothing."
  [x]
  (or (nil? x) (map? x)))

(defn- check-placeholders
  "The problems of the placeholders of the effect at `index`: an index
   that is no integer, or not before this effect."
  [index action]
  (keep (fn [[_ i :as placeholder]]
          (cond
            (not (integer? i)) (str "the placeholder " (pr-str placeholder) " names no index")
            (or (neg? i) (<= index i)) (str "the placeholder " (pr-str placeholder) " refers to effect " (inc i)
                                            ", which is not before this one (the first effect is 0)")))
        (placeholders action)))

(defn- check-storrito
  [{:keys [procedures]} action]
  (let [k (kind action)
        procedure (get procedures (name k))]
    (cond
      (nil? procedure) [(str "storrito/" (name k) " is no procedure of the catalog; (storrito/procedures) lists them")]
      (= :query (:kind procedure)) [(str "storrito/" (name k) " is a query: call it in the program, (storrito/" (name k) " {...}) answers at once")]
      (not (data? (arguments action))) ["its arguments are no map"]
      (< 2 (count action)) ["a storrito/ effect is the keyword and one map"]
      :else nil)))

(defn- check-kind
  "The problems of an effect of `kinds`: too few or too many values,
   a value of the wrong shape."
  [action]
  (let [k (kind action)
        {:keys [takes least]} (get kinds k)
        values (vec (rest action))]
    (cond
      (< (count values) least) [(str "it takes " (str/join ", " (map name (take least takes))) " at least")]
      (< (count takes) (count values)) [(str "it takes " (str/join ", " (map name takes)) " and no more")]
      :else
      (case k
        :remember (cond
                    (str/blank? (str (first values))) ["remember takes an event name"]
                    (not (data? (second values))) ["remember takes a map of data"]
                    :else nil)
        :call (cond
                (str/blank? (str (first values))) ["call takes a connection, by provider name or id"]
                (str/blank? (str (second values))) ["call takes a path"]
                (not (data? (nth values 2 nil))) ["the opts of call are a map"]
                :else nil)
        :composio/execute (cond
                            (str/blank? (str (first values))) ["composio/execute takes a tool slug"]
                            (not (data? (second values))) ["the arguments of composio/execute are a map"]
                            :else nil)
        :composio/connections (when-not (and (sequential? (first values)) (seq (first values)))
                                ["composio/connections takes a vector of toolkit slugs"])
        :composio/add-trigger (cond
                                (str/blank? (str (first values))) ["composio/add-trigger takes a trigger slug"]
                                (str/blank? (str (second values))) ["composio/add-trigger takes a connected account id"]
                                (not (data? (nth values 2 nil))) ["the config of composio/add-trigger is a map"]
                                :else nil)
        :composio/delete-trigger (when (str/blank? (str (first values))) ["composio/delete-trigger takes a trigger id"])
        :mcp/call (cond
                    (str/blank? (str (first values))) ["mcp/call takes a server name"]
                    (str/blank? (str (second values))) ["mcp/call takes a tool name"]
                    (not (data? (nth values 2 nil))) ["the arguments of mcp/call are a map"]
                    :else nil)
        nil))))

(defn- check-action
  "The problems of one effect: its kind unknown, a query as an effect,
   its values wrong, its placeholders wrong. `procedures` is `{name
   {:kind :query|:command}}` of the Storrito catalog."
  [known index action]
  (let [k (kind action)]
    (concat
     (cond
       (= "storrito" (namespace k)) (check-storrito known action)
       (contains? kinds k) (check-kind action)
       :else ["no effect the host runs"])
     (check-placeholders index action))))

(defn check
  "The problems of `plan`, one line each, none when it may run: a
   problem names its effect by number, from 1."
  [known plan]
  (vec (mapcat (fn [index action]
                 (map (fn [problem] (str "effect " (inc index) ", " (describe action) ": " problem))
                      (check-action known index action)))
               (range)
               plan)))

(defn effect
  "The nested tool call of `action`: `{:tool :args}`; `session` is
   the Composio search session the program made, for its effects."
  ([action] (effect action nil))
  ([action session]
   (let [k (kind action)
         [a b c] (rest action)]
     (cond
       (= "storrito" (namespace k)) {:tool "storrito" :args {:procedure (name k) :args (or (arguments action) {})}}
       :else
       (case k
         :remember {:tool "remember" :args {:event a :data (or b {})}}
         :call {:tool "gateway" :args {:which (str a) :path (str b) :opts (or c {})}}
         :composio/execute {:tool "composio-execute" :args (cond-> {:slug (str a) :args (or b {})} session (assoc :session session))}
         :composio/connections {:tool "composio-connections" :args (cond-> {:toolkits (vec a)} session (assoc :session session))}
         :composio/add-trigger {:tool "composio-add-trigger" :args {:slug (str a) :account (str b) :config (or c {})}}
         :composio/delete-trigger {:tool "composio-delete-trigger" :args {:id (str a)}}
         :mcp/call {:tool "mcp" :args {:server (str a) :tool (str b) :args (or c {})}})))))

(defn interpolate
  "`action` with every placeholder replaced by the part of the result
   of the earlier effect it names, `outcomes` being theirs so far."
  [action outcomes]
  (walk/postwalk (fn [x]
                   (if (placeholder? x)
                     (let [[_ i & path] x]
                       (get-in (:value (nth outcomes i)) (vec path)))
                     x))
                 action))

(defn key-of
  "The key of the nested call of the effect at `index`: `e0`, `e1`,
   ... so that a rerun finds it."
  [index]
  (str "e" index))

(defn ^:async perform!
  "`plan` run, one effect after the other: `execute!` takes the
   effect's key, the tool and the arguments of its call and answers a
   promise of `{:ok true :value}` or `{:ok false :error}`; `aborted?`
   says whether to go on; `session` is the Composio search session of
   the program. The outcomes, one per effect, in order: `{:action
   :status :value :error}`, the status `:done`, `:failed`, `:aborted`,
   or `:skipped` for the effects after the first failure."
  [plan {:keys [execute! aborted? session]}]
  (loop [index 0
         outcomes []]
    (if (= index (count plan))
      outcomes
      (let [action (nth plan index)]
        (cond
          (some #(contains? #{:failed :aborted} (:status %)) outcomes)
          (recur (inc index) (conj outcomes {:action action :status :skipped}))

          (and aborted? (aborted?))
          (recur (inc index) (conj outcomes {:action action :status :aborted :error "The program was stopped."}))

          :else
          (let [{:keys [tool args]} (effect (interpolate action outcomes) session)
                {:keys [ok value error]} (await (execute! (key-of index) tool args))]
            (recur (inc index)
                   (conj outcomes (if ok
                                    {:action action :status :done :value value}
                                    {:action action :status :failed :error (str error)})))))))))

(defn- outcome-line
  [index {:keys [action status value error]}]
  (str (inc index) ". " (describe action) ": "
       (case status
         :done (str "done" (when (some? value) (str " " (cut (pr-str value) max-value-chars))))
         :failed (str "failed: " error)
         :aborted "stopped"
         :skipped "not run")))

(defn summary
  "One line: how many effects, how many done, the first failure."
  [outcomes]
  (let [done (count (filter #(= :done (:status %)) outcomes))
        failed (first (filter #(contains? #{:failed :aborted} (:status %)) outcomes))]
    (str "Plan of " (count outcomes) " effect" (when (not= 1 (count outcomes)) "s") ": " done " done"
         (when failed
           (str ", " (if (= :aborted (:status failed)) "stopped" "failed") " at effect "
                (inc (.indexOf outcomes failed))
                (when (< (inc (.indexOf outcomes failed)) (count outcomes)) ", the rest not run")))
         ".")))

(defn report
  "The outcomes as lines for the model: the summary, then one line per
   effect."
  [outcomes]
  (str/join "\n" (cons (summary outcomes) (map-indexed outcome-line outcomes))))

(defn failed?
  [outcomes]
  (boolean (some #(contains? #{:failed :aborted} (:status %)) outcomes)))
