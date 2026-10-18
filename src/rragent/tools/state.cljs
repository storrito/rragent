(ns rragent.tools.state
  "The agent's memory beyond the transcript: one document, a plain JSON
   object stored next to the transcript and changed in the same commit
   as the tool's result, so that \"I handled review 4711\" never falls
   off the end when the transcript is compacted. The `state` tool reads
   it, merges a patch into it, or replaces it."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineDoc defineTool]]))

(def State
  (defineDoc #js {:kind "rragent.state"
                  :version 1
                  :scope "conversation"
                  :history "latest"
                  :fork "current"
                  :initial (fn [] #js {})}))

(defn- text
  [value]
  #js {:output #js [#js {:type "text" :text (js/JSON.stringify value nil 2)}]})

(defn- clear!
  "Every own key of the draft removed, in place."
  [draft]
  (doseq [k (js/Object.keys draft)]
    (js-delete draft k))
  draft)

(defn ^:async apply!
  "The state after `patch`: its keys set on the state, a null one
   removed from it, or the patch in place of the state when `replace?`;
   nothing changed without a patch. Committed with the tool's result."
  [api patch replace? call-context]
  (let [after (atom nil)]
    (await (.commit api
                    (^:async fn [tx]
                      (let [draft (await (.doc tx State (.-conversationId api)))]
                        (when replace?
                          (clear! draft))
                        (doseq [k (js/Object.keys (or patch #js {}))]
                          (if (nil? (aget patch k))
                            (js-delete draft k)
                            (aset draft k (aget patch k))))
                        (reset! after (js/JSON.parse (js/JSON.stringify draft)))))
                    call-context))
    @after))

(def tool
  (defineTool
   #js {:name "state"
        :description (str "Your own state, a JSON object kept with the conversation and never compacted away: "
                          "what you have handled, what you are waiting for, settings you decided. "
                          "Without arguments it answers the current state. With `patch` the keys of the patch are "
                          "set on it (a key with null is removed); with `replace` true the patch becomes the whole state.")
        :parameters (.Object Type #js {:patch (.Optional Type (.Any Type #js {:description "Keys to set on the state, a JSON object"}))
                                       :replace (.Optional Type (.Boolean Type #js {:description "True: the patch replaces the whole state"}))})
        :execute (^:async fn [args api call-context]
                   (let [patch (.-patch args)
                         replace? (true? (.-replace args))]
                     (when (and patch (not (object? patch)))
                       (throw (js/Error. "patch must be a JSON object")))
                     (text (await (apply! api (when patch patch) replace? call-context)))))}))
