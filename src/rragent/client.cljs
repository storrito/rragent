(ns rragent.client
  "The browser bundle of the page: the rframes runtime, which opens the
   frame stream of the page it is on and renders every frame with
   Replicant, with one alias of our own, the composer. The aliases of
   the artifacts pane come here later."
  (:require [clojure.string :as str]
            [rframes.client :as rframes]
            [rframes.client.state :as state]))

(defn composer
  "The textarea of the page. Its text lives in the store under
   `:ui/store-key`, so that a frame from the server does not reset what
   the person types; Enter runs `:ui/send`, the actions of the server,
   and Shift+Enter breaks the line. The other attributes pass through."
  [{:keys [ui/store-key ui/send ui/state] :as attrs} _children]
  (let [draft (str (get state store-key ""))]
    [:textarea
     (-> (dissoc attrs :ui/store-key :ui/send :ui/state)
         (assoc :value draft
                :on {:input [[:store/assoc store-key :event/target.value]]
                     :keydown (fn [^js event]
                                (when (and (= "Enter" (.-key event))
                                           (not (.-shiftKey event))
                                           (not (.-isComposing event)))
                                  (.preventDefault event)
                                  (when-not (str/blank? draft)
                                    (state/dispatch! send))))}))]))

(def register
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}])

(defn init!
  []
  (rframes/init! {:register register}))

(init!)
