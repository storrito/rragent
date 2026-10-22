(ns rragent.client.memo
  "The voice memo button of the composer (`rragent.client`): a click
   records the microphone, a second click stops, and the recording goes
   with the message as a file, the way an audio file picked would: the
   stop hands it to `upload` (the composer's clip upload, which sends
   it in pieces with `?to=mp3`, so the server has ffmpeg make an MP3 of
   it, `rragent.uploads`), its chip shows the progress, and the message
   names it, `audio: uploads/<id>.mp3`, for the agent to cut, clean and
   transcribe (`rragent.tools.transcribe`). Unlike the dictation beside
   it (`rragent.client.dictation`), which writes the words into the
   draft, the recording itself is what the person sends.

   While it records, a strip above the text shows a red dot and the
   time, the dictation's strip without the meter. The state is in the
   store under `:ui/memo`: the `:status` (`:starting`, `:recording`,
   `:failed`) and the `:seconds`; the stream, the recorder, its chunks
   and the clock are in `session*`."
  (:require [clojure.string :as str]))

(defonce ^:private session*
  (atom nil))

(def chunk-ms
  "How often the recorder hands over a chunk, so that a long memo is
   many small blobs, not one."
  1000)

(def mime-types
  "The containers tried in order: Opus in WebM, what Chrome and Firefox
   record, then AAC in MP4, what Safari records; the recorder's own
   default otherwise."
  ["audio/webm;codecs=opus"
   "audio/webm"
   "audio/mp4"])

(defn mime-type
  "The first of `mime-types` the recorder supports, by `supported?`, or
   nil for its default."
  [supported?]
  (some #(when (supported? %) %) mime-types))

(defn extension-of
  "The extension of a file of `type`."
  [type]
  (cond
    (str/includes? (str type) "mp4") "m4a"
    (str/includes? (str type) "ogg") "ogg"
    :else "webm"))

(defn file-name
  "The name of the memo recorded at `date`, memo-20261022-1133.webm."
  [^js date type]
  (let [two #(.padStart (str %) 2 "0")]
    (str "memo-" (.getFullYear date) (two (inc (.getMonth date))) (two (.getDate date))
         "-" (two (.getHours date)) (two (.getMinutes date))
         "." (extension-of type))))

(defn- update!
  [store f & args]
  (swap! store update :ui/memo #(apply f % args)))

(defn- release!
  "The microphone let go, the clock stopped."
  []
  (when-let [{:keys [stream timer]} @session*]
    (js/clearInterval timer)
    (doseq [^js track (.getTracks ^js stream)]
      (.stop track))
    (reset! session* nil)))

(defn start!
  "Starts a recording; the browser asks for the microphone the first
   time. The stop hands the file to `upload`."
  [store upload]
  (swap! store assoc :ui/memo {:status :starting :seconds 0})
  (-> (js/navigator.mediaDevices.getUserMedia #js {:audio true})
      (.then (fn [^js stream]
               (let [type (mime-type #(js/MediaRecorder.isTypeSupported %))
                     recorder (if type
                                (js/MediaRecorder. stream #js {:mimeType type})
                                (js/MediaRecorder. stream))
                     chunks #js []
                     started (js/Date.now)]
                 (set! (.-ondataavailable recorder)
                       (fn [^js event]
                         (when (pos? (.. event -data -size))
                           (.push chunks (.-data event)))))
                 (set! (.-onstop recorder)
                       (fn [_]
                         (let [type (or (not-empty (.-mimeType recorder)) type "audio/webm")
                               ;; the container's type alone: the server knows audio/webm, not its codecs
                               plain (first (str/split type #";"))
                               file (js/File. chunks (file-name (js/Date. started) plain) #js {:type plain})]
                           (release!)
                           (swap! store dissoc :ui/memo)
                           (if (pos? (.-size file))
                             (upload file)
                             (js/console.warn "[memo] nothing was recorded")))))
                 (set! (.-onerror recorder)
                       (fn [^js event]
                         (js/console.error "[memo]" (.-error event))
                         (release!)
                         (swap! store assoc :ui/memo {:status :failed})))
                 (.start recorder chunk-ms)
                 (reset! session* {:stream stream
                                   :recorder recorder
                                   :timer (js/setInterval
                                           (fn []
                                             (update! store assoc :seconds (js/Math.floor (/ (- (js/Date.now) started) 1000))))
                                           500)})
                 (update! store assoc :status :recording)
                 ;; stopped while the browser was still asking for the microphone
                 (when (get-in @store [:ui/memo :stop-when-ready?])
                   (.stop recorder)))))
      (.catch (fn [error]
                (js/console.error "[memo]" error)
                (swap! store assoc :ui/memo {:status :failed})))))

(defn stop!
  "Ends the recording: the file goes up."
  [store]
  (if-let [^js recorder (:recorder @session*)]
    (when (not= "inactive" (.-state recorder))
      (.stop recorder))
    (when (= :starting (get-in @store [:ui/memo :status]))
      (update! store assoc :stop-when-ready? true))))

(defn recording?
  [state]
  (= :recording (get-in state [:ui/memo :status])))

(defn- clock
  "Seconds as m:ss."
  [seconds]
  (str (quot seconds 60) ":" (.padStart (str (rem seconds 60)) 2 "0")))

(defn indicator
  "The strip above the text while a memo records: a red dot and the
   time."
  [state]
  (when (recording? state)
    [:div.dictation.memo {:replicant/key "memo"}
     [:span.dot]
     [:span.time (clock (get-in state [:ui/memo :seconds] 0))]]))

(defn button
  "The record button: a click starts a memo, a click while it records
   (red, see app.css) stops it. Only in a browser that records."
  [store state upload {:keys [title stop-title failed-title]}]
  (when (and (exists? js/MediaRecorder)
             (some-> js/navigator .-mediaDevices .-getUserMedia))
    (let [status (get-in state [:ui/memo :status])]
      [:button.attach.memo {:type "button"
                            :class (case status
                                     :recording "recording"
                                     :failed "failed"
                                     nil)
                            :disabled (= :starting status)
                            :title (case status
                                     :recording (or stop-title "End the recording, it goes with the message as an MP3")
                                     :failed (or failed-title "The recording did not work; click to try again")
                                     (or title "Record a voice memo"))
                            :on {:click (fn [_event]
                                          (if (= :recording status)
                                            (stop! store)
                                            (start! store upload)))}}
       "⏺"])))
