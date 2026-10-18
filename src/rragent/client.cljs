(ns rragent.client
  "The browser bundle of the page: the rframes runtime, which opens the
   frame stream of the page it is on and renders every frame with
   Replicant, with two aliases of our own, the composer and the canvas
   of the artifacts pane, the iframe that shows a page or a project and
   reloads it in place; the canvas is rchat's."
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

(defonce ^:private canvas-node*
  (atom nil))

(defonce ^:private canvas-src*
  (atom nil))

(defonce ^:private canvas-scroll*
  (atom nil))

(defn- canvas-scroll
  "The scroll position of the page in the iframe, same origin."
  []
  (try
    (when-let [^js node @canvas-node*]
      (let [^js win (.-contentWindow node)]
        [(.-scrollX win) (.-scrollY win)]))
    (catch :default _
      nil)))

(defn- restore-canvas-scroll!
  [_event]
  (when-let [[x y] @canvas-scroll*]
    (reset! canvas-scroll* nil)
    (try
      (when-let [^js node @canvas-node*]
        (.scrollTo (.-contentWindow node) x y))
      (catch :default _
        nil))))

(defn canvas
  "The creation of the agent: an iframe over the page the server names.
   A new `:ui/rev` changes the src and reloads the page at the position
   the person was looking at; everything else Replicant leaves alone.
   `:ui/title` is the title of the iframe."
  [{:keys [ui/src ui/rev ui/title]} _children]
  (let [src (str src "?rev=" rev)]
    (when (and @canvas-src* (not= src @canvas-src*))
      (reset! canvas-scroll* (canvas-scroll)))
    (reset! canvas-src* src)
    [:iframe.canvas {:replicant/key "canvas"
                     :src src
                     :title (or title "Your creation")
                     :replicant/on-mount (fn [{:keys [replicant/node]}]
                                           (reset! canvas-node* node)
                                           (.addEventListener ^js node "load" restore-canvas-scroll!))
                     :replicant/on-unmount (fn [_]
                                             (reset! canvas-node* nil)
                                             (reset! canvas-src* nil))}]))

(def register
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}
   {:ui.alias/kind :ui/canvas
    :ui.alias/fn canvas}])

(defn init!
  []
  (rframes/init! {:register register}))

(init!)
