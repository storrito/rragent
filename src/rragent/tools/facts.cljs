(ns rragent.tools.facts
  "The `remember` and `recall` tools: a fact appended, the facts
   projected (`rragent.facts`)."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.facts :as facts]))

(defn- text
  [s]
  #js {:output #js [#js {:type "text" :text s}]})

(def remember
  (defineTool
   #js {:name "remember"
        :description (str "Note a fact for later: what happened, with a name for the kind of event and the details, for example "
                          "event \"review-posted\" with {id: 4711, stars: 5}. Facts are never changed or lost, the conversation's "
                          "summaries leave them alone; `recall` reads them back. Use it for everything you must know in a later run.")
        :parameters (.Object Type #js {:event (.String Type #js {:description "The kind of event, a short name such as review-posted"})
                                       :data (.Optional Type (.Any Type #js {:description "The details, a JSON object"}))})
        :execute (^:async fn [args api call-context]
                   (let [data (.-data args)]
                     (when (and data (not (object? data)))
                       (throw (js/Error. "data must be a JSON object")))
                     (let [id (await (facts/remember! api (.-event args) (js->clj (or data #js {}) :keywordize-keys true) (js/Date.now) call-context))]
                       (text (str "Noted " (.-event args) " (fact " id ")")))))}))

(def recall
  (defineTool
   #js {:name "recall"
        :description (str "Your facts, oldest first, as `events`: maps with :event, :at (milliseconds) and your own keys. "
                          "With `code`, Clojure evaluated with `events` bound, the value is the answer: a projection you write, "
                          "for example (->> events (filter #(= \"review-posted\" (:event %))) (map :id) set). "
                          "Without code the facts themselves. `event` and `since` narrow the facts first.")
        :parameters (.Object Type #js {:code (.Optional Type (.String Type #js {:description "Clojure over `events`"}))
                                       :event (.Optional Type (.String Type #js {:description "Only facts of this event"}))
                                       :since (.Optional Type (.String Type #js {:description "Only facts from this time on, ISO 8601"}))})
        :execute (^:async fn [args api call-context]
                   (let [all (await (facts/facts (facts/from-tool api call-context)))
                         chosen (facts/select all {:event (.-event args) :since (.-since args)})]
                     (text (facts/project chosen (.-code args)))))}))
