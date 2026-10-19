(ns rragent.facts
  "The agent's memory beyond the transcript, as facts: immutable
   entries of the kind `rragent.fact` in the conversation, each a map
   with an `event` name, a time and whatever the agent noted, appended
   with `remember!` and never changed. There is no state to keep in
   step: a read model is a projection the agent writes as Clojure code
   over the facts, oldest first, evaluated here by SCI (`project`).
   Compaction summarizes what the model sees and leaves the entries,
   so a fact outlives every summary."
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(def kind
  "rragent.fact")

(def max-chars
  10000)

(defn ^:async remember!
  "A fact appended to the tool's conversation: `event`, the time, and
   `data`, a map of the agent's own keys. Answers the entry's id."
  [api event data now call-context]
  (let [entry (await (.commit api
                              (fn [tx]
                                (.appendEntry tx (.-conversationId api)
                                              #js {:kind kind
                                                   :data (clj->js (assoc data :event event :at now))}))
                              call-context))]
    (.-id entry)))

(defn- fact
  "The entry's data with the entry's id under `:fact/id`; the agent's
   own keys, `:id` among them, stay what they are."
  [entry]
  (when (= kind (.-kind entry))
    (assoc (js->clj (.-data entry) :keywordize-keys true) :fact/id (.-id entry))))

(defn ^:async facts
  "Every fact of the conversation, oldest first; `page` answers one
   page of the conversation's entries for a cursor (`from-root`,
   `from-tool`)."
  [page]
  (loop [cursor js/undefined
         found []]
    (let [result (await (page cursor))
          items (array-seq (.-items result))
          found (into found (keep fact) items)
          next (or (.-cursor result) (.-next result))]
      (if (and next (seq items))
        (recur next found)
        (vec (reverse found))))))

(defn from-root
  "The pages of the root conversation's entries, newest first."
  [root context]
  (fn [cursor] (.entries root #js {} 200 cursor context)))

(defn from-tool
  "The pages of the tool's conversation's entries, through a commit
   that changes nothing: a tool's api reads through its transaction."
  [api call-context]
  (fn [cursor]
    (.commit api
             (fn [tx] (.scanEntries tx #js {:conversationId (.-conversationId api)} 200 cursor))
             call-context)))

(defn- since-ms
  [since]
  (when (seq (str since))
    (let [ms (.getTime (js/Date. since))]
      (when (js/isNaN ms)
        (throw (js/Error. (str "since is not a date: " since))))
      ms)))

(defn select
  "The facts of `event` (any without) from `since` (a date or
   milliseconds; all without), oldest first."
  [facts {:keys [event since]}]
  (let [from (since-ms since)]
    (cond->> facts
      (seq (str event)) (filter #(= event (:event %)))
      from (filter #(<= from (:at %)))
      true vec)))

(defn- cut
  [printed]
  (if (< max-chars (count printed))
    (str (subs printed 0 max-chars) " … (cut at " max-chars " characters)")
    printed))

(defn project
  "`code`, Clojure with `events` bound to the facts, evaluated in a
   fresh context; the value printed. Without code the facts themselves."
  [facts code]
  (cut (if (str/blank? (str code))
         (pr-str facts)
         (pr-str (sci/eval-string code {:classes {'js js/globalThis :allow :all}
                                        :features #{:cljs}
                                        :namespaces {'user {'events facts}}})))))
