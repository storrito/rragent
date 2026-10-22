(ns rragent.client.recorder
  "The screencast button of the composer (`rragent.client`): the page
   records the screen and the webcam as two files, both with the
   microphone, the way rawcast does it (github.com/maxweber/rawcast):
   no layout up front, the picture-in-picture and the cuts come later,
   from the agent, which aligns the two by their waveforms. The files
   go to the server as they are recorded (`rragent.recordings`, a piece
   every few seconds, so a long recording never sits in the browser's
   memory), into `artifacts/recordings/<stamp>/`, and when the
   recording ends the draft names them, for the person to say how the
   two go together.

   The panel above the text (`panel`) picks the microphone and the
   camera (remembered in the browser), shows the camera live with the
   level of the microphone, makes a test recording of five seconds to
   play back, and starts: Chrome asks which screen. While it records,
   the panel is a bar with the time and Stop; Chrome's own Stop sharing
   ends it too.

   The state is in the store under `:ui/recorder`: the `:status`
   (`:opening`, `:ready`, `:testing`, `:recording`, `:finishing`,
   `:failed`), the `:devices`, the `:mic` and `:camera` chosen, the
   `:seconds` recorded, the `:test` (an object URL) and the `:error`.
   What is no data (the streams, the recorders, the uploaders, the
   meter and the preview node, the URL and the labels the panel was
   opened with) is in `session*`."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]))

(defonce ^:private session*
  (atom nil))

(def piece-ms
  "How often a recorder hands over a piece, which goes up at once."
  3000)

(def test-ms
  "How long the test recording is."
  5000)

(def retry-delays-ms
  "The pauses before a piece the server did not take is sent again."
  [1000 2000 4000 8000 15000])

(def screen-bits
  "The bitrate of the screen, enough for text at full resolution."
  8000000)

(def webcam-bits
  4000000)

(def audio-bits
  128000)

(defn supported?
  "Whether this browser records a screencast: Chrome on a desktop."
  []
  (boolean (and (exists? js/MediaRecorder)
                (some-> js/navigator .-mediaDevices .-getDisplayMedia))))

(defn- update!
  [store f & args]
  (apply swap! store update :ui/recorder f args))

(defn- mime-type
  []
  (some #(when (js/MediaRecorder.isTypeSupported %) %)
        ["video/webm;codecs=vp9,opus" "video/webm;codecs=vp8,opus" "video/webm"]))

(defn- remembered
  [kind]
  (try
    (js/localStorage.getItem (str "rragent.recorder." (name kind)))
    (catch :default _
      nil)))

(defn- remember!
  [kind id]
  (try
    (js/localStorage.setItem (str "rragent.recorder." (name kind)) (str id))
    (catch :default _
      nil)))

(defn- stop-tracks!
  [^js stream]
  (when stream
    (doseq [^js track (array-seq (.getTracks stream))]
      (.stop track))))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- clock
  "Seconds as m:ss."
  [seconds]
  (str (quot (or seconds 0) 60) ":" (.padStart (str (rem (or seconds 0) 60)) 2 "0")))

(defn- ^:async devices
  "The microphones and the cameras with their labels: the browser gives
   the labels once it was allowed the devices, so it is asked once."
  []
  (stop-tracks! (await (js/navigator.mediaDevices.getUserMedia #js {:audio true :video true})))
  (let [all (array-seq (await (js/navigator.mediaDevices.enumerateDevices)))
        of (fn [kind word]
             (->> all
                  (filter #(= kind (.-kind ^js %)))
                  (map-indexed (fn [i ^js device]
                                 {:id (.-deviceId device)
                                  :label (let [label (str (.-label device))]
                                           (if (str/blank? label) (str word " " (inc i)) label))}))
                  (vec)))]
    {:mics (of "audioinput" "Microphone")
     :cameras (of "videoinput" "Camera")}))

(defn- level
  "The loudness of the microphone now, the root mean square of a frame."
  [^js analyser ^js samples]
  (.getFloatTimeDomainData analyser samples)
  (let [n (.-length samples)]
    (loop [i 0
           sum 0]
      (if (< i n)
        (let [x (aget samples i)]
          (recur (inc i) (+ sum (* x x))))
        (js/Math.sqrt (/ sum (max n 1)))))))

(defn- tick!
  "The level on the meter, twenty times a second, straight to the node;
   the seconds in the store once they change."
  [store]
  (let [{:keys [analyser samples meter since]} @session*
        status (:status (:ui/recorder @store))]
    (when (and analyser meter)
      (.setProperty (.-style ^js meter) "--level" (str (min 1 (* 8 (level analyser samples))))))
    (when (and since (#{:recording :testing} status))
      (let [seconds (js/Math.floor (/ (- (js/performance.now) since) 1000))]
        (when (not= seconds (:seconds (:ui/recorder @store)))
          (update! store assoc :seconds seconds))))))

(defn- ^:async preview!
  "The camera and the microphone chosen, live in the panel: the stream
   the webcam file records, with the meter on its microphone."
  [store]
  (let [{:keys [mic camera]} (:ui/recorder @store)]
    (stop-tracks! (:preview @session*))
    (let [stream (await (js/navigator.mediaDevices.getUserMedia
                         (clj->js {:audio (if mic {:deviceId {:exact mic}} true)
                                   :video (cond-> {:width {:ideal 1920}
                                                   :height {:ideal 1080}
                                                   :frameRate {:ideal 30}}
                                            camera (assoc :deviceId {:exact camera}))})))
          audio-context (or (:audio-context @session*) (js/AudioContext.))
          analyser (.createAnalyser audio-context)]
      (set! (.-fftSize analyser) 2048)
      (.connect (.createMediaStreamSource audio-context stream) analyser)
      (swap! session* assoc
             :preview stream
             :audio-context audio-context
             :analyser analyser
             :samples (js/Float32Array. (.-fftSize analyser)))
      (when-let [^js node (:preview-node @session*)]
        (set! (.-srcObject node) stream))
      stream)))

(defn- failed!
  [store ^js error]
  (js/console.error "[recorder]" error)
  (update! store assoc :status :failed :error (str (or (some-> error .-message) error))))

(defn open!
  "Opens the panel: the devices, the ones used last time or the first,
   the camera live. `ctx` is where the pieces go (`:url`), the draft
   the recording ends in (`:store-key`) and the panel's words
   (`:labels`)."
  [store ctx]
  (reset! session* {:ctx ctx})
  (update! store assoc :status :opening :error nil)
  (-> (devices)
      (.then (fn [{:keys [mics cameras] :as found}]
               (let [pick (fn [kind xs]
                            (let [wanted (remembered kind)]
                              (or (some #(when (= wanted (:id %)) (:id %)) xs)
                                  (:id (first xs)))))]
                 (update! store assoc :devices found :mic (pick :mic mics) :camera (pick :camera cameras))
                 (preview! store))))
      (.then (fn [_]
               (swap! session* assoc :timer (js/setInterval #(tick! store) 50))
               (update! store assoc :status :ready)))
      (.catch (fn [error]
                (failed! store error)))))

(defn- release!
  "Lets the devices go and forgets the session."
  []
  (let [{:keys [timer preview display clone audio-context]} @session*]
    (some-> timer js/clearInterval)
    (stop-tracks! preview)
    (stop-tracks! display)
    (some-> ^js clone .stop)
    (some-> ^js audio-context .close)
    (reset! session* nil)))

(defn close!
  "Closes the panel, unless it records."
  [store]
  (when-not (#{:recording :finishing} (:status (:ui/recorder @store)))
    (some-> (:test (:ui/recorder @store)) js/URL.revokeObjectURL)
    (release!)
    (swap! store dissoc :ui/recorder)))

(defn pick!
  "Another microphone or camera, remembered for next time."
  [store kind id]
  (remember! kind id)
  (update! store assoc kind id :error nil)
  (-> (preview! store)
      (.catch (fn [error]
                (failed! store error)))))

(defn test!
  "Five seconds of the camera and the microphone, played back in the
   panel; nothing goes up."
  [store]
  (let [recorder (js/MediaRecorder. (:preview @session*)
                                    #js {:mimeType (mime-type)
                                         :videoBitsPerSecond webcam-bits
                                         :audioBitsPerSecond audio-bits})
        chunks #js []]
    (some-> (:test (:ui/recorder @store)) js/URL.revokeObjectURL)
    (update! store assoc :status :testing :test nil :seconds 0)
    (swap! session* assoc :since (js/performance.now))
    (set! (.-ondataavailable recorder)
          (fn [^js event]
            (when (pos? (.. event -data -size))
              (.push chunks (.-data event)))))
    (set! (.-onstop recorder)
          (fn [_]
            (update! store assoc
                     :status :ready
                     :test (js/URL.createObjectURL (js/Blob. chunks #js {:type (.-mimeType recorder)})))))
    (.start recorder)
    (js/setTimeout (fn []
                     (when (= "recording" (.-state recorder))
                       (.stop recorder)))
                   test-ms)))

(defn- ^:async post-piece!
  "One piece to the server, at `offset` of its file, until it is taken:
   a pause of the network means another try after a while; when the
   piece does not start where the file ends the server says how much
   it holds, and the rest of the piece goes, or nothing when all of it
   is there already (the answer was lost). Answers the bytes the server
   holds then."
  [url ^js blob offset]
  (loop [^js blob blob
         offset offset
         tries 0]
    (let [result (try
                   (let [response (await (js/fetch (str url "?offset=" offset)
                                                   #js {:method "POST"
                                                        :headers #js {"Content-Type" "video/webm"}
                                                        :body blob}))
                         status (.-status response)
                         value (try
                                 (t/read (t/reader :json) (await (.text response)))
                                 (catch :default _
                                   nil))]
                     (cond
                       (= 200 status) {:taken (:received value)}
                       (= 409 status) {:received (:received value)}
                       (= 503 status) {:retry? true}
                       :else {:error (str "HTTP " status)}))
                   (catch :default error
                     {:retry? true :error (str (.-message error))}))
          end (+ offset (.-size blob))]
      (cond
        (:taken result)
        (:taken result)

        (some? (:received result))
        (let [received (:received result)]
          (cond
            (= received end) received
            (< offset received end) (recur (.slice blob (- received offset)) received 0)
            :else (throw (js/Error. (str "The server holds " received " bytes of the file, the page sent " offset ".")))))

        (and (:retry? result) (< tries (count retry-delays-ms)))
        (do (await (sleep (nth retry-delays-ms tries)))
            (recur blob offset (inc tries)))

        :else
        (throw (js/Error. (or (:error result) "The piece was not taken.")))))))

(defn- uploader
  "The queue of a file's pieces: each goes after the one before, from
   where the server says the file ends."
  [url]
  (atom {:url url
         :chain (js/Promise.resolve 0)}))

(defn- push!
  [up ^js blob]
  (swap! up update :chain
         (fn [chain]
           (.then chain (fn [offset]
                          (post-piece! (:url @up) blob offset))))))

(defn stop!
  "Ends the recording: the recorders stop, the last pieces go up, then
   the recording is finished on the server (`finish!`)."
  [store]
  (when (= :recording (:status (:ui/recorder @store)))
    (update! store assoc :status :finishing)
    (doseq [{:keys [^js recorder]} [(:screen @session*) (:webcam @session*)]]
      (when (= "recording" (.-state recorder))
        (.stop recorder)))))

(defn- labels-of
  [store]
  (let [{:keys [devices mic camera]} (:ui/recorder @store)]
    {:mic (some #(when (= mic (:id %)) (:label %)) (:mics devices))
     :camera (some #(when (= camera (:id %)) (:label %)) (:cameras devices))}))

(defn- ^:async finish!
  "Both recorders stopped: once every piece is up, the server makes the
   files, and the draft names them."
  [store]
  (let [{:keys [screen webcam base started mime ctx]} @session*
        {:keys [store-key labels]} ctx]
    (try
      (await (:chain @(:uploader screen)))
      (await (:chain @(:uploader webcam)))
      (let [response (await (js/fetch (str base "/finish")
                                      #js {:method "POST"
                                           :headers #js {"Content-Type" "application/transit+json"}
                                           :body (t/write (t/writer :json) {:started started
                                                                            :devices (labels-of store)
                                                                            :mime mime})}))
            value (t/read (t/reader :json) (await (.text response)))]
        (when-not (= 200 (.-status response))
          (throw (js/Error. (str "The recording was not saved (" (.-status response) ")."))))
        (let [folder (str "artifacts/" (:folder value))
              line (str/replace (or (:draft labels)
                                    "I recorded a screencast: {folder}/screen.webm is the screen, {folder}/webcam.webm the webcam, both with the microphone. ")
                                "{folder}" folder)]
          (swap! store update store-key (fn [draft]
                                          (str (when (seq (str draft)) (str draft "\n\n")) line)))
          (update! store assoc :status :ready)
          (close! store)
          (some-> (js/document.querySelector ".composer textarea") .focus)))
      (catch :default error
        (release!)
        (failed! store error)))))

(defn- maybe-finish!
  [store name]
  (swap! session* assoc-in [:stopped name] true)
  (when (every? #(get-in @session* [:stopped %]) ["screen" "webcam"])
    (finish! store)))

(defn- stamp
  "The folder's name: the time here, like rawcast names its folders."
  []
  (let [d (js/Date.)
        p #(.padStart (str %) 2 "0")]
    (str (.getFullYear d) "-" (p (inc (.getMonth d))) "-" (p (.getDate d))
         "T" (p (.getHours d)) "-" (p (.getMinutes d)) "-" (p (.getSeconds d)))))

(defn- recorder-of
  "A recorder of `stream` into the file `name`, its pieces up as they
   come."
  [store base mime ^js stream name bits]
  (let [recorder (js/MediaRecorder. stream #js {:mimeType mime
                                                :videoBitsPerSecond bits
                                                :audioBitsPerSecond audio-bits})
        up (uploader (str base "/" name))]
    (set! (.-ondataavailable recorder)
          (fn [^js event]
            (when (pos? (.. event -data -size))
              (push! up (.-data event)))))
    (set! (.-onstart recorder)
          (fn [_]
            (swap! session* assoc-in [:started name] (js/Date.now))))
    (set! (.-onstop recorder)
          (fn [_]
            (maybe-finish! store name)))
    {:recorder recorder
     :uploader up}))

(defn start!
  "Starts the recording from the click (Chrome asks which screen only
   on a gesture): the screen with a copy of the microphone, the camera
   with the microphone, each to its file."
  [store]
  (-> (js/navigator.mediaDevices.getDisplayMedia
       #js {:video #js {:frameRate #js {:ideal 30}
                        :displaySurface "monitor"}
            :audio false
            :selfBrowserSurface "exclude"
            :surfaceSwitching "exclude"
            :systemAudio "exclude"})
      (.then (fn [^js display]
               (let [^js preview (:preview @session*)
                     ^js screen-track (first (array-seq (.getVideoTracks display)))
                     ^js clone (.clone ^js (first (array-seq (.getAudioTracks preview))))
                     screen-stream (js/MediaStream. #js [screen-track clone])
                     the-stamp (stamp)
                     base (str (get-in @session* [:ctx :url]) "/" the-stamp)
                     mime (mime-type)
                     screen (recorder-of store base mime screen-stream "screen" screen-bits)
                     webcam (recorder-of store base mime preview "webcam" webcam-bits)]
                 (swap! session* assoc
                        :display display
                        :clone clone
                        :screen screen
                        :webcam webcam
                        :stamp the-stamp
                        :base base
                        :mime mime
                        :started {}
                        :stopped {}
                        :since (js/performance.now))
                 ;; Chrome's own Stop sharing ends the recording too
                 (set! (.-onended screen-track) (fn [_] (stop! store)))
                 (.start ^js (:recorder screen) piece-ms)
                 (.start ^js (:recorder webcam) piece-ms)
                 (update! store assoc :status :recording :seconds 0 :error nil))))
      (.catch (fn [^js error]
                ;; the person closed Chrome's picker: nothing happened
                (when-not (= "NotAllowedError" (.-name error))
                  (failed! store error))))))

(defn- preview-mount
  [{:keys [replicant/node]}]
  (set! (.-muted ^js node) true)
  (swap! session* #(some-> % (assoc :preview-node node)))
  (when-let [stream (:preview @session*)]
    (set! (.-srcObject ^js node) stream)))

(defn- device-select
  [store kind chosen options]
  [:select {:on {:change (fn [^js event]
                           (pick! store kind (.. event -target -value)))}}
   (for [{:keys [id label]} options]
     [:option {:value id
               :selected (= id chosen)}
      label])])

(defn panel
  "The panel above the text while it is open: the devices, the camera
   live with the meter, the test, Start; a bar with the time and Stop
   while it records. `labels` are the words, the server's, translated."
  [store state labels]
  (let [{:keys [status devices mic camera seconds test error]} (:ui/recorder state)]
    (when status
      [:div.recorder {:replicant/key "recorder"}
       (if (#{:recording :finishing} status)
         [:div.live
          [:span.dot]
          [:span.time (clock seconds)]
          [:span.hint (if (= :finishing status) (:saving labels) (:recording labels))]
          (when (= :recording status)
            [:button.primary {:type "button"
                              :on {:click (fn [_] (stop! store))}}
             (:stop labels)])]
         (list
          [:div.head
           [:strong (:title labels)]
           [:button.link.close {:type "button"
                                :on {:click (fn [_] (close! store))}}
            (:close labels)]]
          (when (= :opening status)
            [:p.hint (:opening labels)])
          (when (#{:ready :testing :failed} status)
            (list
             [:div.devices
              [:label (:mic labels)
               (device-select store :mic mic (:mics devices))]
              [:label (:camera labels)
               (device-select store :camera camera (:cameras devices))]]
             [:div.preview
              [:video.camera {:autoplay true
                              :playsinline true
                              :replicant/on-mount preview-mount}]
              [:span.meter {:replicant/on-mount (fn [{:keys [replicant/node]}]
                                                  (swap! session* #(some-> % (assoc :meter node))))
                            :replicant/on-unmount (fn [_]
                                                    (swap! session* #(some-> % (dissoc :meter))))}
               [:span] [:span] [:span] [:span] [:span]]]
             (when test
               [:video.test {:replicant/key test
                             :src test
                             :controls true}])
             [:div.actions
              [:button {:type "button"
                        :disabled (not= :ready status)
                        :on {:click (fn [_] (test! store))}}
               (if (= :testing status)
                 (str (:testing labels) " " (clock seconds))
                 (:test labels))]
              [:button.primary {:type "button"
                                :disabled (not= :ready status)
                                :on {:click (fn [_] (start! store))}}
               (:start labels)]]
             [:p.hint (:screen-hint labels)]))
          (when error
            [:p.error (str/replace (or (:failed labels) "The recording did not work: {error}") "{error}" (str error))])))])))

(defn button
  "The screencast button of the composer's row: opens the panel, closes
   it; red while it records. Only in a browser that records the screen."
  [store state ctx]
  (when (supported?)
    (let [status (:status (:ui/recorder state))]
      [:button.attach.record {:type "button"
                              :class (when (#{:recording :finishing} status) "recording")
                              :title (:button (:labels ctx))
                              :on {:click (fn [_]
                                            (cond
                                              (nil? status) (open! store ctx)
                                              (#{:recording :finishing} status) nil
                                              :else (close! store)))}}
       "🎥"])))
