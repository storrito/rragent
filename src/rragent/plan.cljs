(ns rragent.plan
  "A plan: the actions a program answers, run by the host after it.
   After Nexus (github.com/cjohansen/nexus): an action is a vector led
   by a keyword, `[:storrito/schedule-social-post {...}]`, an
   instruction as data, and a plan is a vector of them, the value of a
   program of the REPL (`rragent.codemode`). The host turns each
   action into an effect, a nested tool call of the eval tool under a
   key of its own (`rragent.tools.eval`), so that a machine that
   restarts in the middle of a plan picks up where it was: an effect
   that was done is found by its key and not done again. The program
   itself only reads; what changes something is an action, and so a
   program cannot change anything twice. The actions run one after
   the other, each seeing the results before it: a placeholder
   `[:plan/result 0 :uuid]` in the arguments of an action stands for
   that part of the result of the action at that index, replaced when
   the action runs. The first failure ends the plan, the actions after
   it are not run; what each did is the answer, as data for the page
   and as lines for the model. Queries stay functions of the program,
   commands are actions; expansions, pure functions that turn one
   action into others, come later."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def max-action-chars
  "How much of an action a line shows."
  160)

(def max-value-chars
  "How much of a result a line shows."
  400)

(defn action?
  "A vector led by a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn kind
  [action]
  (first action))

(defn arguments
  "The arguments of an action: the map after its keyword, nil without."
  [action]
  (second action))

(defn known?
  "Whether the action's keyword is one the host runs: in one of the
   `namespaces` (strings, `storrito`) or one of the `names`
   (`:remember`)."
  [{:keys [namespaces names]} action]
  (let [k (kind action)]
    (boolean (or (contains? (set names) k)
                 (and (namespace k) (contains? (set namespaces) (namespace k)))))))

(defn- cut
  [s max]
  (if (< max (count s))
    (str (subs s 0 max) " …")
    s))

(defn describe
  "The action as a line shows it: `storrito/x {...}`, cut."
  [action]
  (let [k (kind action)]
    (cut (str (if (namespace k) (str (namespace k) "/" (name k)) (name k))
              (when (seq (rest action)) (str " " (str/join " " (map pr-str (rest action))))))
         max-action-chars)))

(defn of
  "The plan in `value`, the value of a program: a vector (or seq) of
   actions the host knows (`known`), or one such action, as a vector
   of actions; nil for any other value, which is data, a sequence of
   vectors led by keywords none of which the host knows included. A
   sequence of actions among which one is not known is a mistake,
   and a throw says which."
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
          (throw (js/Error. (str "A plan of " (count actions) " actions, but action " (inc index) ", " (describe action)
                                 ", is none the host runs: the actions are storrito/<procedure> for a command of the catalog and remember."))))
        :else nil))

    :else nil))

(defn placeholder?
  "`[:plan/result index & path]`."
  [x]
  (and (vector? x) (= :plan/result (first x))))

(defn placeholders
  "The placeholders anywhere in the action's arguments."
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
  "The problems of the placeholders of the action at `index`: an index
   that is no integer, or not before this action."
  [index action]
  (keep (fn [[_ i :as placeholder]]
          (cond
            (not (integer? i)) (str "the placeholder " (pr-str placeholder) " names no index")
            (or (neg? i) (<= index i)) (str "the placeholder " (pr-str placeholder) " refers to action " (inc i)
                                            ", which is not before this one (the first action is 0)")))
        (placeholders action)))

(defn- check-action
  "The problems of one action: its kind unknown, a query as an action,
   its arguments no map, its placeholders wrong. `procedures` is
   `{name {:kind :query|:command}}` of the Storrito catalog."
  [{:keys [procedures]} index action]
  (let [k (kind action)
        args (arguments action)]
    (concat
     (case (namespace k)
       "storrito" (let [procedure (get procedures (name k))]
                    (cond
                      (nil? procedure) [(str "storrito/" (name k) " is no procedure of the catalog; (storrito/procedures) lists them")]
                      (= :query (:kind procedure)) [(str "storrito/" (name k) " is a query: call it in the program, (storrito/" (name k) " {...}) answers at once")]
                      (not (data? args)) ["its arguments are no map"]
                      (< 2 (count action)) ["a storrito/ action is the keyword and one map"]
                      :else nil))
       nil (case k
             :remember (cond
                         (str/blank? (str (second action))) ["remember takes an event name"]
                         (not (data? (nth action 2 nil))) ["remember takes a map of data"]
                         :else nil)
             [(str "no action the host runs")])
       [(str "no action the host runs")])
     (check-placeholders index action))))

(defn check
  "The problems of `plan`, one line each, none when it may run: a
   problem names its action by number, from 1."
  [known plan]
  (vec (mapcat (fn [index action]
                 (map (fn [problem] (str "action " (inc index) ", " (describe action) ": " problem))
                      (check-action known index action)))
               (range)
               plan)))

(defn effect
  "The nested tool call of `action`: `{:tool :args}`."
  [action]
  (let [k (kind action)]
    (case (namespace k)
      "storrito" {:tool "storrito" :args {:procedure (name k) :args (or (arguments action) {})}}
      {:tool "remember" :args {:event (second action) :data (or (nth action 2 nil) {})}})))

(defn interpolate
  "`action` with every placeholder replaced by the part of the result
   of the earlier action it names, `outcomes` being theirs so far."
  [action outcomes]
  (walk/postwalk (fn [x]
                   (if (placeholder? x)
                     (let [[_ i & path] x]
                       (get-in (:value (nth outcomes i)) (vec path)))
                     x))
                 action))

(defn key-of
  "The key of the nested call of the action at `index`: `e0`, `e1`,
   ... so that a rerun finds it."
  [index]
  (str "e" index))

(defn ^:async perform!
  "`plan` run, one action after the other: `execute!` takes the
   action's key, the tool and the arguments of its effect and answers
   a promise of `{:ok true :value}` or `{:ok false :error}`;
   `aborted?` says whether to go on. The outcomes, one per action, in
   order: `{:action :status :value :error}`, the status `:done`,
   `:failed`, `:aborted`, or `:skipped` for the actions after the
   first failure."
  [plan {:keys [execute! aborted?]}]
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
          (let [{:keys [tool args]} (effect (interpolate action outcomes))
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
  "One line: how many actions, how many done, the first failure."
  [outcomes]
  (let [done (count (filter #(= :done (:status %)) outcomes))
        failed (first (filter #(contains? #{:failed :aborted} (:status %)) outcomes))]
    (str "Plan of " (count outcomes) " action" (when (not= 1 (count outcomes)) "s") ": " done " done"
         (when failed
           (str ", " (if (= :aborted (:status failed)) "stopped" "failed") " at action "
                (inc (.indexOf outcomes failed))
                (when (< (inc (.indexOf outcomes failed)) (count outcomes)) ", the rest not run")))
         ".")))

(defn report
  "The outcomes as lines for the model: the summary, then one line per
   action."
  [outcomes]
  (str/join "\n" (cons (summary outcomes) (map-indexed outcome-line outcomes))))

(defn failed?
  [outcomes]
  (boolean (some #(contains? #{:failed :aborted} (:status %)) outcomes)))
