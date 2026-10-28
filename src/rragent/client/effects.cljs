(ns rragent.client.effects
  "The webcam's effects of the screencast panel (`rragent.client.recorder`):
   the background behind the person blurred or taken away (a flat green
   for the agent to key out, or black), the light and the colour of
   the picture corrected as it goes, and the skin of the face smoothed
   (the touch-up, as Google Meet's). The work is done in a worker
   (`public/effects/worker.js`): MediaPipe's selfie segmenter finds the
   person in every frame, its face landmarker the face, a canvas
   composes the frame, a shader smooths the skin, and the frames
   flow through streams (MediaStreamTrackProcessor from the camera's
   track, MediaStreamTrackGenerator into the track that is shown and
   recorded), so the webcam keeps being processed while the tab is in
   the background, where a page's animation frames stop. Chrome has
   these; without them the panel shows no effects.

   The settings, `{:background :off|:blur|:green|:black :auto-light?
   bool :touch-up :off|:subtle|:smooth}`, are remembered in the
   browser. Nothing of the raw camera
   leaves the laptop: the file recorded is the processed picture, and
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

(defn settings-js
  "The settings as the worker reads them."
  [{:keys [background auto-light? touch-up]}]
  #js {:background (name (or background :off))
       :autoLight (boolean auto-light?)
       :touchUp (name (or touch-up :off))})

(defn facts
  "What `recording.json` says of the webcam's file: the background it
   was recorded with and the green to key out, whether the light was
   corrected and how much the skin was smoothed; nil when no effect
   was on."
  [settings]
  (when (active? settings)
    (cond-> {:background (name (:background settings :off))
             :auto-light (boolean (:auto-light? settings))
             :touch-up (name (:touch-up settings :off))}
      (= :green (:background settings)) (assoc :key green))))

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
   one could not be loaded; the frames pass through meanwhile."
  [^js raw settings {:keys [on-ready on-loading on-failed]}]
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

(defn tune-camera!
  "The camera asked to keep its exposure, white balance and focus
   automatic, where it lets a page say so; the light is corrected on
   top of that. Nothing happens on a camera that does not."
  [^js raw]
  (when-let [^js track (some-> raw .getVideoTracks array-seq first)]
    (let [capabilities (try
                         (js->clj (.getCapabilities track))
                         (catch :default _
                           {}))
          continuous? (fn [mode] (some #{"continuous"} (get capabilities mode)))
          wanted (cond-> {}
                   (continuous? "exposureMode") (assoc :exposureMode "continuous")
                   (continuous? "whiteBalanceMode") (assoc :whiteBalanceMode "continuous")
                   (continuous? "focusMode") (assoc :focusMode "continuous"))]
      (when (seq wanted)
        (-> (.applyConstraints track (clj->js {:advanced [wanted]}))
            (.catch (fn [error]
                      (js/console.warn "[effects] the camera kept its settings:" (str (some-> error .-message))))))))))
