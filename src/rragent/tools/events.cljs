(ns rragent.tools.events
  "The `remember` and `recall` tools: an event appended to the bot's
   events file, the events projected (`rragent.events`). The file is
   the bot's of the call (`rragent.tools.work/bot-dir`), so a call of
   a run of the bot writes where the bot and its other runs read."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            [rragent.events :as events]
            [rragent.home :as home]
            [rragent.tools.work :as work]))

(defn ^:async file
  "The events file of the bot of the call."
  [api call-context]
  (home/events-file (await (work/bot-dir api call-context))))

(def remember
  (defineTool
   #js {:name "remember"
        :description (str "Note an event for later: what happened, with a name for the kind of event and the details, for example "
                          "event \"review-posted\" with {id: 4711, stars: 5}. Events are never changed or lost, the conversation's "
                          "summaries leave them alone, and your runs see them as you do; `recall` reads them back. "
                          "Use it for everything you must know in a later run: what was posted, what was seen, what was decided.")
        :parameters (.Object Type #js {:event (.String Type #js {:description "The kind of event, a short name such as review-posted"})
                                       :data (.Optional Type (.Any Type #js {:description "The details, a JSON object"}))})
        :execute (^:async fn [args api call-context]
                   (let [data (.-data args)]
                     (when (and data (not (object? data)))
                       (throw (js/Error. "data must be a JSON object")))
                     (when (empty? (str (.-event args)))
                       (throw (js/Error. "remember needs the event's name")))
                     (events/append! (await (file api call-context)) (.-event args) (js->clj (or data #js {}) :keywordize-keys true) (js/Date.now))
                     (work/text (str "Noted " (.-event args)))))}))

(def recall
  (defineTool
   #js {:name "recall"
        :description (str "Your events, oldest first, as `events`: maps with :event, :at (milliseconds) and your own keys. "
                          "With `code`, Clojure evaluated with `events` bound, the value is the answer: a projection you write, "
                          "for example (->> events (filter #(= \"review-posted\" (:event %))) (map :id) set). "
                          "Without code the events themselves. `event` and `since` narrow the events first.")
        :parameters (.Object Type #js {:code (.Optional Type (.String Type #js {:description "Clojure over `events`"}))
                                       :event (.Optional Type (.String Type #js {:description "Only events of this kind"}))
                                       :since (.Optional Type (.String Type #js {:description "Only events from this time on, ISO 8601"}))})
        :execute (^:async fn [args api call-context]
                   (let [all (events/read (await (file api call-context)))
                         chosen (events/select all {:event (.-event args) :since (.-since args)})]
                     (work/text (events/project chosen (.-code args)))))}))
