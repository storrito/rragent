(ns rragent.client.effects
  "The webcam's effects of the screencast panel (`rragent.client.recorder`):
   the background behind the person blurred or taken away (a flat green
   for the agent to key out, or black), the light and the colour of
   the picture corrected as it goes, and the skin of the face smoothed
   (the touch-up, as Google Meet's). The work is done in a worker
   (`public/effects/worker.js`): the picture is made on the GPU
   (`public/effects/gpu.js`: a dual Kawase blur of the background, the
   skin smoothed and the person laid over it in one shader, whose
   context MediaPipe's selfie segmenter shares to find the person),
   while MediaPipe's BlazeFace finds the face in a worker of
   its own (`public/effects/models.js`), the picture never waiting for
   it; and the frames
   flow through streams (MediaStreamTrackProcessor from the camera's
   track, MediaStreamTrackGenerator into the track that is shown and
   recorded), so the webcam keeps being processed while the tab is in
   the background, where a page's animation frames stop. Chrome has
   these; without them the panel shows no effects. While an effect is
   on the camera is asked for 1280 x 720 (`picture`).

   The webcam is recorded as the preview shows it, the effects made
   live (`timing` \"live\", the default). A frame made live has a
   thirtieth of a second, and every frame that took longer is lost for
   good: on a busy computer the webcam stutters, and the panel warns
   under `smooth-fps`. The settings can have the effects put on after
   the recording instead (`timing` \"after\"): the webcam is recorded
   as it is, kept in the browser, and every frame takes the time it
   needs (`apply-to-file!`, in a worker of its own; the file read,
   encoded anew and written by Mediabunny, WebCodecs, in
   `public/effects/apply.js`). `recording.json` holds the frames a
   second of the webcam's file.

   The settings, `{:background :off|:blur|:green|:black :auto-light?
   bool :touch-up :off|:subtle|:smooth}`, are remembered in the
   browser. Nothing of the raw camera
   leaves the laptop: the file that goes up is the processed picture, and
   `facts` says so in `recording.json`, so that the agent knows the
   green is to key out."
  (:require [clojure.string :as str]))

(def backgrounds
  "What can be behind the person, in the order the panel offers them."
  [:off :blur :green :black])

(def touch-ups
  "How much the skin is smoothed, in the order the panel offers them."
  [:off :subtle :smooth])

(def default-settings
  {:background :off
   :auto-light? false
   :touch-up :off})

(def green
  "The green behind the person when the background is taken away, for
   ffmpeg's chromakey; the agent reads it from `recording.json`."
  "#00ff00")

(defn supported?
  "Whether this browser processes a camera's frames in a worker: Chrome."
  []
  (boolean (and (exists? js/MediaStreamTrackProcessor)
                (exists? js/MediaStreamTrackGenerator)
                (exists? js/Worker)
                (exists? js/OffscreenCanvas))))

(defn active?
  "Whether any effect is on: the frames go through the worker then."
  [settings]
  (boolean (or (not= :off (:background settings :off))
               (:auto-light? settings)
               (not= :off (:touch-up settings :off)))))

(def smooth-fps
  "The frames a second under which the webcam's picture stutters."
  20)

(defn picture
  "What the camera is asked for: 1280 x 720 while an effect is on, which
   the effects make in time on a laptop and is more than the webcam's
   circle in a video needs; 1920 x 1080 without."
  [settings]
  (if (active? settings)
    {:width {:ideal 1280} :height {:ideal 720} :frameRate {:ideal 30}}
    {:width {:ideal 1920} :height {:ideal 1080} :frameRate {:ideal 30}}))

(defn fps
  "Frames a second of `frames` in `ms`, to a tenth; nil without time."
  [frames ms]
  (when (and (number? ms) (pos? ms))
    (/ (js/Math.round (/ (* frames 10000) ms)) 10)))

(defn stutters?
  "Whether a rate the worker told (frames a second) makes the webcam stutter."
  [rate]
  (and (number? rate) (< rate smooth-fps)))

(defn- one-of
  [choices word]
  (some #(when (= word (name %)) %) choices))

(defn parse
  "The settings from what the browser remembered: the background's name,
   whether the light is automatic and the touch-up's name; the defaults
   for anything else."
  ([background auto-light]
   (parse background auto-light nil))
  ([background auto-light touch-up]
   {:background (or (one-of backgrounds background) :off)
    :auto-light? (= "1" auto-light)
    :touch-up (or (one-of touch-ups touch-up) :off)}))

(defn remembered
  []
  (try
    (parse (js/localStorage.getItem "rragent.recorder.background")
           (js/localStorage.getItem "rragent.recorder.auto-light")
           (js/localStorage.getItem "rragent.recorder.touch-up"))
    (catch :default _
      default-settings)))

(defn remember!
  [{:keys [background auto-light? touch-up]}]
  (try
    (js/localStorage.setItem "rragent.recorder.background" (name (or background :off)))
    (js/localStorage.setItem "rragent.recorder.auto-light" (if auto-light? "1" "0"))
    (js/localStorage.setItem "rragent.recorder.touch-up" (name (or touch-up :off)))
    (catch :default _
      nil)))

(defn- log?
  "Whether the effects tell the time of each step on the console every
   second, set by hand in the browser's console:
   `localStorage.setItem(\"rragent.effects.log\", \"1\")`."
  []
  (try
    (boolean (some-> js/globalThis .-localStorage (.getItem "rragent.effects.log") seq))
    (catch :default _ false)))

(defn settings-js
  "The settings as the worker reads them."
  [{:keys [background auto-light? touch-up]}]
  #js {:background (name (or background :off))
       :autoLight (boolean auto-light?)
       :touchUp (name (or touch-up :off))
       :log (log?)})

(defn facts
  "What `recording.json` says of the webcam's file: the background it
   was recorded with and the green to key out, whether the light was
   corrected, how much the skin was smoothed and, when the worker told
   it, the frames a second it made (`rate`); nil when no effect was
   on."
  ([settings]
   (facts settings nil))
  ([settings rate]
   (when (active? settings)
     (cond-> {:background (name (:background settings :off))
              :auto-light (boolean (:auto-light? settings))
              :touch-up (name (:touch-up settings :off))}
       (= :green (:background settings)) (assoc :key green)
       (number? rate) (assoc :fps rate)))))

(defn file-label
  "The word of the panel for the webcam's file, by the background it was
   recorded with: `labels` has `:file-webcam`, `:file-webcam-green` and
   `:file-webcam-black`."
  [labels settings]
  (or (case (:background settings)
        :green (:file-webcam-green labels)
        :black (:file-webcam-black labels)
        nil)
      (:file-webcam labels)
      "the webcam"))

(defn start!
  "The effects on the camera's stream `raw`: the worker is started with
   the camera's frames and the settings, and answers the stream to show
   and record, the processed picture with the microphone of `raw`.
   `on-ready` is called once the models run, `on-loading` when new
   settings need one not loaded yet, `on-failed` with a message when
   one could not be loaded; the frames pass through meanwhile.
   `on-rate` is called every second with the frames that went out and
   the milliseconds they took."
  [^js raw settings {:keys [on-ready on-loading on-failed on-rate]}]
  (let [^js video (first (array-seq (.getVideoTracks raw)))
        processor (js/MediaStreamTrackProcessor. #js {:track video})
        generator (js/MediaStreamTrackGenerator. #js {:kind "video"})
        worker (js/Worker. "/effects/worker.js" #js {:type "module"})
        readable (.-readable processor)
        writable (.-writable generator)]
    (set! (.-onmessage worker)
          (fn [^js event]
            (let [data (.-data event)]
              (case (some-> data .-type)
                "ready" (when on-ready (on-ready))
                "loading" (when on-loading (on-loading))
                "failed" (when on-failed (on-failed (str (.-message data))))
                "rate" (when on-rate (on-rate (.-frames data) (.-ms data)))
                nil))))
    (set! (.-onerror worker)
          (fn [^js event]
            (js/console.error "[effects] worker" event)
            (when on-failed
              (on-failed (str (or (some-> event .-message) "the worker did not start"))))))
    (.postMessage worker
                  #js {:type "start" :readable readable :writable writable :settings (settings-js settings)}
                  #js [readable writable])
    {:worker worker
     :generator generator
     :stream (js/MediaStream. (into-array (cons generator (array-seq (.getAudioTracks raw)))))}))

(defn preview-every!
  "The preview of the worker that runs made of every `n`th frame only,
   the others dropped: 3 while the webcam records as it is, 1 again
   after."
  [{:keys [^js worker]} n]
  (when worker
    (.postMessage worker #js {:type "preview" :every n})))

(def timing-key
  "Where the browser keeps when the webcam's effects are made
   (`timing`)."
  "rragent.recorder.effects-timing")

(defn timing
  "When the webcam's effects are made, as the person chose in the
   settings: \"live\", the default, recorded as the preview shows
   them; or \"after\", put on the recorded file in this browser once
   the recording ends, which never stutters and keeps the page busy a
   while."
  []
  (or (try
        (#{"after"} (.getItem js/localStorage timing-key))
        (catch :default _ nil))
      "live"))

(defn choose-timing!
  "`k` (\"live\" or \"after\") kept for the next recordings."
  [k]
  (try
    (.setItem js/localStorage timing-key k)
    (catch :default _ nil)))

(defn ^:async apply-to-file!
  "The effects of `settings` put on the recorded webcam `file` (a Blob)
   by `public/effects/apply.js`: encoded anew as the recorders' `mime` at
   `bits`, `write` called with each piece (a Uint8Array) in order and
   waited for, `on-progress` with 0..1. Answers `{:frames :seconds}`."
  [^js file settings {:keys [mime bits write on-progress]}]
  ;; from the page's own origin, which serves /effects (in development the
  ;; scripts come from Vite's, where a worker would be another origin's)
  (let [^js apply (await (js* "import(/* @vite-ignore */ ~{})" (str js/location.origin "/effects/apply.js")))
        result (await (.applyToFile apply file (settings-js settings)
                                    #js {:codec (.codecOf apply mime)
                                         :bits bits
                                         :write write
                                         :onProgress on-progress}))]
    {:frames (.-frames result)
     :seconds (.-seconds result)}))

(defn update!
  "New settings to the worker that runs."
  [{:keys [^js worker]} settings]
  (when worker
    (.postMessage worker #js {:type "settings" :settings (settings-js settings)})))

(defn stop!
  "The worker ended, its track too; the camera's own track is the
   panel's to stop."
  [{:keys [^js worker ^js generator]}]
  (some-> worker .terminate)
  (some-> generator .stop))

(defn fit-camera!
  "The camera asked for the size of `picture` by the settings, and,
   while the light is corrected, to keep its exposure, white balance
   and focus automatic, where it lets a page say so; the light is
   corrected on top of that. A camera that takes none of it stays as
   it is."
  [^js raw settings]
  (when-let [^js track (some-> raw .getVideoTracks array-seq first)]
    (let [capabilities (try
                         (js->clj (.getCapabilities track))
                         (catch :default _
                           {}))
          continuous? (fn [mode] (some #{"continuous"} (get capabilities mode)))
          wanted (if (:auto-light? settings)
                   (cond-> {}
                     (continuous? "exposureMode") (assoc :exposureMode "continuous")
                     (continuous? "whiteBalanceMode") (assoc :whiteBalanceMode "continuous")
                     (continuous? "focusMode") (assoc :focusMode "continuous"))
                   {})]
      (-> (.applyConstraints track (clj->js (cond-> (picture settings)
                                              (seq wanted) (assoc :advanced [wanted]))))
          (.catch (fn [error]
                    (js/console.warn "[effects] the camera kept its settings:" (str (some-> error .-message)))))))))
