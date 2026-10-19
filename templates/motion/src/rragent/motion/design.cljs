(ns rragent.motion.design
  "The design file of a motion project, `design.cljs`, evaluated by SCI
   in the project's process. The file is the namespace `design` and
   defines `size` (width and height of the video in pixels), `duration`
   (seconds), `design-view` (the frame at a time, a pure function of the
   design data and the time, hiccup), `controls` (what the person plays
   with, a function of the design data to hiccup, optional) and `styles`
   (CSS for the page and the video, optional). fnmotion is at hand,
   `fnmotion.core`, `fnmotion.timeline` and `fnmotion.captions`, and
   `rragent.motion.player/setter` for a control. The context of the
   last load stays with the design (`:ctx`), so that an eval
   (`eval-in`) reaches the namespace the file made. The port of the
   `src/artifact/main.clj` of pchat's template: the design is data and
   functions in one file, the app around it is the template's
   (`rragent.motion.app`)."
  (:require ["node:fs" :as fs]
            [fnmotion.captions :as captions]
            [fnmotion.core :as fm]
            [fnmotion.timeline :as tl]
            [rragent.motion.player :as player]
            [sci.core :as sci]))

(def namespaces
  "What a design file may require."
  {'fnmotion.core (sci/copy-ns fnmotion.core (sci/create-ns 'fnmotion.core))
   'fnmotion.timeline (sci/copy-ns fnmotion.timeline (sci/create-ns 'fnmotion.timeline))
   'fnmotion.captions (sci/copy-ns fnmotion.captions (sci/create-ns 'fnmotion.captions))
   'rragent.motion.player {'setter player/setter}})

(defn context
  "A fresh context: a reload starts over, nothing of the old file stays."
  []
  (sci/init {:namespaces namespaces
             :classes {'js js/globalThis :allow :all}
             :features #{:cljs}}))

(defn- var-of
  [ctx sym]
  (some-> (sci/eval-string* ctx (str "(resolve 'design/" sym ")")) deref))

(defn- read-vars
  "What the `design` namespace of the context defines, checked:
   `{:size :duration :design-view :controls :styles :ctx}`, or `{:error
   message}` when it lacks what the player needs."
  [ctx]
  (let [design {:size (var-of ctx "size")
                :duration (var-of ctx "duration")
                :design-view (var-of ctx "design-view")
                :controls (var-of ctx "controls")
                :styles (var-of ctx "styles")
                :ctx ctx}]
    (cond
      (not (fn? (:design-view design)))
      {:error "design.cljs defines no design-view, the function of the design and the time."}

      (not (and (vector? (:size design)) (= 2 (count (:size design))) (every? number? (:size design))))
      {:error "design.cljs defines no size, [width height] in pixels."}

      (not (number? (:duration design)))
      {:error "design.cljs defines no duration, seconds."}

      :else design)))

(defn- message
  [e]
  (or (.-message e) (str e)))

(defn evaluate
  "The text of a design file evaluated in a fresh context: what it
   defines (`read-vars`), or `{:error message}` when it does not
   evaluate."
  [text]
  (try
    (let [ctx (context)]
      (sci/eval-string* ctx text)
      (read-vars ctx))
    (catch :default e
      {:error (message e)})))

(defn load
  "The design file read and evaluated, see `evaluate`."
  [file]
  (if (fs/existsSync file)
    (evaluate (fs/readFileSync file "utf8"))
    {:error (str file " is missing.")}))

(defn eval-in
  "`code` evaluated in the `design` namespace of the loaded design's
   context: `{:value printed :loaded the design read again}`, so that
   a redefinition takes effect; `{:error message}` when the code fails
   or there is no context (the file did not load)."
  [{:keys [ctx]} code]
  (if-not ctx
    {:error "design.cljs is not loaded, so there is nothing to evaluate in."}
    (try
      (let [value (sci/eval-string* ctx (str "(in-ns 'design)\n" code))
            loaded (read-vars ctx)]
        {:value (pr-str value)
         :loaded (if (:error loaded) (assoc loaded :ctx ctx) loaded)})
      (catch :default e
        {:error (message e)}))))
