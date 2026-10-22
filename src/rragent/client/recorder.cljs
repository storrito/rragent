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

   What is recorded is a set of sources (`:sources`): the screen, the
   webcam, a phone, any of them ticked. A phone paired by a QR code
   (`rragent.phone`, its page `rragent.client.phone`) is a camera the
   laptop tells the machine to start and stop with the recording; it
   records its own file at full quality and sends it up itself, and
   the end waits for that file. The phone alone is a vertical video
   for the agent to cut.

   The state is in the store under `:ui/recorder`: the `:status`
   (`:opening`, `:ready`, `:testing`, `:recording`, `:finishing`,
   `:failed`), the `:devices`, the `:mic` and `:camera` chosen, the
   `:sources` ticked, the `:pair` (the link and its QR while shown),
   the `:seconds` recorded, the `:test` (an object URL) and the
   `:error`.
   What is no data (the streams, the recorders, the uploaders, the
   meter and the preview node, the URL and the labels the panel was
   opened with) is in `session*`."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            ["qrcode" :as qrcode]))

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

(def finish-retry-ms
  "How often the end is asked for again while the phone's file is
   still going up."
  2000)

(def finish-tries
  "How long the end waits for the phone: three minutes."
  90)

(defn supported?
  "Whether this browser records a screencast: Chrome on a desktop."
  []
  (boolean (and (exists? js/MediaRecorder)
                (some-> js/navigator .-mediaDevices .-getDisplayMedia))))

(defn- update!
  [store f & args]
  (apply swap! store update :ui/recorder f args))

(defn mime-type
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

(defn- device-gone?
  "Whether the browser could not give the devices asked for: none, or
   not the ones remembered (their ids change when the person revokes
   and allows the site again)."
  [^js error]
  (contains? #{"NotFoundError" "OverconstrainedError" "NotReadableError"} (some-> error .-name)))

(defn- ^:async user-media
  "The camera and the microphone, the ones chosen when the browser has
   them, any otherwise: a remembered id that is gone does not stop the
   panel."
  [mic camera]
  (let [chosen (clj->js {:audio (if mic {:deviceId {:ideal mic}} true)
                         :video (cond-> {:width {:ideal 1920}
                                         :height {:ideal 1080}
                                         :frameRate {:ideal 30}}
                                  camera (assoc :deviceId {:ideal camera}))})]
    (try
      (await (js/navigator.mediaDevices.getUserMedia chosen))
      (catch :default error
        (if (device-gone? error)
          (await (js/navigator.mediaDevices.getUserMedia #js {:audio true :video true}))
          (throw error))))))

(defn- ^:async preview!
  "The camera and the microphone chosen, live in the panel: the stream
   the webcam file records, with the meter on its microphone."
  [store]
  (let [{:keys [mic camera]} (:ui/recorder @store)]
    (stop-tracks! (:preview @session*))
    (let [stream (await (user-media mic camera))
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
  "The panel says what went wrong: a camera or a microphone the
   browser does not give (blocked for the site, none there) in the
   panel's own words, anything else as the browser put it."
  [store ^js error]
  (js/console.error "[recorder]" error)
  (let [name (some-> error .-name)
        words (get-in @session* [:ctx :labels])]
    (update! store assoc
             :status :failed
             :error (cond
                      (= "NotAllowedError" name) (or (:blocked words) "The camera or the microphone is blocked for this site: allow them in the browser, then open the panel again.")
                      (device-gone? error) (or (:no-devices words) "No camera or microphone the browser can use: check they are plugged in and allowed for this site, then open the panel again.")
                      :else (str (or (some-> error .-message) error))))))

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
                 (update! store assoc
                          :devices found
                          :mic (pick :mic mics)
                          :camera (pick :camera cameras)
                          :sources (remembered-sources))
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
                   (let [response (await (js/fetch (str url (if (str/includes? url "?") "&" "?") "offset=" offset)
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

(defn uploader
  "The queue of a file's pieces: each goes after the one before, from
   where the server says the file ends. The phone's page uses it too."
  [url]
  (atom {:url url
         :chain (js/Promise.resolve 0)}))

(defn push!
  [up ^js blob]
  (swap! up update :chain
         (fn [chain]
           (.then chain (fn [offset]
                          (post-piece! (:url @up) blob offset))))))

(def default-sources
  #{:screen :webcam})

(defn- remembered-sources
  []
  (if-let [kept (remembered :sources)]
    (set (keep #(#{:screen :webcam :phone} (keyword %)) (str/split kept #",")))
    default-sources))

(defn- sources
  [store]
  (or (:sources (:ui/recorder @store)) default-sources))

(defn- phone-url
  []
  (get-in @session* [:ctx :phone-url]))

(defn- ^:async post-phone!
  "A word to the phones through the machine; what it answers, or nil
   when it could not be told."
  [action data]
  (try
    (let [response (await (js/fetch (str (phone-url) "/" action)
                                    #js {:method "POST"
                                         :headers #js {"Content-Type" "application/transit+json"}
                                         :body (t/write (t/writer :json) data)}))]
      (t/read (t/reader :json) (await (.text response))))
    (catch :default error
      (js/console.error "[recorder] phone" error)
      nil)))

(declare finish!)

(defn stop!
  "Ends the recording: the recorders stop, the phone is told, the last
   pieces go up, then the recording is finished on the server
   (`finish!`); with the phone alone there is no recorder here and
   the end is asked for at once."
  [store]
  (when (= :recording (:status (:ui/recorder @store)))
    (update! store assoc :status :finishing)
    (when (:phone? @session*)
      (post-phone! "stop" {:stamp (:stamp @session*)}))
    (if-let [recorders (seq (vals (:recorders @session*)))]
      (doseq [{:keys [^js recorder]} recorders]
        (when (= "recording" (.-state recorder))
          (.stop recorder)))
      (finish! store))))

(defn- labels-of
  [store]
  (let [{:keys [devices mic camera]} (:ui/recorder @store)]
    {:mic (some #(when (= mic (:id %)) (:label %)) (:mics devices))
     :camera (some #(when (= camera (:id %)) (:label %)) (:cameras devices))}))

(defn- draft-line
  "What the draft says of the recording: its folder and every file
   with what it is."
  [labels folder files]
  (let [what {"screen" (or (:file-screen labels) "the screen")
              "webcam" (or (:file-webcam labels) "the webcam")
              "phone" (or (:file-phone labels) "the phone's camera")}
        named (str/join ", " (map (fn [file]
                                    (str file " (" (get what (first (str/split file #"\.")) file) ")"))
                                  files))]
    (-> (or (:draft labels) "New recording in {folder}: {files}; every file carries a microphone. ")
        (str/replace "{folder}" folder)
        (str/replace "{files}" named))))

(defn- ^:async ask-finish!
  "The end, asked for until the server has every file: the phone's
   may still be going up, or not have begun to (409)."
  [base facts]
  (loop [tries 0]
    (let [response (await (js/fetch (str base "/finish")
                                    #js {:method "POST"
                                         :headers #js {"Content-Type" "application/transit+json"}
                                         :body (t/write (t/writer :json) facts)}))
          value (t/read (t/reader :json) (await (.text response)))]
      (cond
        (= 200 (.-status response))
        value

        (and (= 409 (.-status response))
             (or (= :phone-uploading (:error value))
                 (and (= :not-recorded (:error value)) (= ["phone"] (:missing value))))
             (< tries finish-tries))
        (do (await (sleep finish-retry-ms))
            (recur (inc tries)))

        :else
        (throw (js/Error. (str "The recording was not saved (" (.-status response) " " (pr-str value) ").")))))))

(defn- ^:async finish!
  "The recorders stopped: once every piece is up, the server makes the
   files (waiting for the phone's when there is one), and the draft
   names them."
  [store]
  (let [{:keys [recorders base started mime ctx phone?]} @session*
        {:keys [store-key labels]} ctx
        files (cond-> (into {} (map (fn [[name _]] [name "webm"]) recorders))
                phone? (assoc "phone" (:phone-ext @session* "webm")))]
    (try
      (doseq [{:keys [uploader]} (vals recorders)]
        (await (:chain @uploader)))
      (when phone?
        (update! store assoc :phone-waiting? true))
      (let [value (await (ask-finish! base {:started started
                                            :devices (labels-of store)
                                            :mime mime
                                            :files files}))
            line (draft-line labels (str "artifacts/" (:folder value)) (:files value))]
        (swap! store update store-key (fn [draft]
                                        (str (when (seq (str draft)) (str draft "\n\n")) line)))
        (update! store assoc :status :ready :phone-waiting? false)
        (close! store)
        (some-> (js/document.querySelector ".composer textarea") .focus))
      (catch :default error
        (release!)
        (failed! store error)))))

(defn- maybe-finish!
  [store name]
  (swap! session* assoc-in [:stopped name] true)
  (when (every? #(get-in @session* [:stopped %]) (keys (:recorders @session*)))
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

(defn- ^:async begin!
  "The recorders of the mode, started together once the phone, when it
   is a camera, was told the stamp; without a phone listening the
   recording does not start. `display` is the screen, nil with the
   phone alone."
  [store ^js display]
  (let [wanted (sources store)
        phone? (contains? wanted :phone)
        the-stamp (stamp)
        phones (when phone?
                 (:phones (await (post-phone! "start" {:stamp the-stamp}))))]
    (if (and phone? (not (pos? (or phones 0))))
      (do (stop-tracks! display)
          (update! store assoc :error (or (get-in @session* [:ctx :labels :phone-none]) "No phone is connected.")))
      (let [^js preview (:preview @session*)
            ^js screen-track (when display (first (array-seq (.getVideoTracks display))))
            ^js clone (when display (.clone ^js (first (array-seq (.getAudioTracks preview)))))
            base (str (get-in @session* [:ctx :url]) "/" the-stamp)
            mime (mime-type)
            recorders (cond-> {}
                        display (assoc "screen" (recorder-of store base mime (js/MediaStream. #js [screen-track clone]) "screen" screen-bits))
                        (contains? wanted :webcam) (assoc "webcam" (recorder-of store base mime preview "webcam" webcam-bits)))]
        (swap! session* assoc
               :display display
               :clone clone
               :recorders recorders
               :phone? phone?
               :stamp the-stamp
               :base base
               :mime mime
               :started {}
               :stopped {}
               :since (js/performance.now))
        ;; Chrome's own Stop sharing ends the recording too
        (when screen-track
          (set! (.-onended screen-track) (fn [_] (stop! store))))
        (doseq [{:keys [^js recorder]} (vals recorders)]
          (.start recorder piece-ms))
        (update! store assoc :status :recording :seconds 0 :error nil)))))

(defn start!
  "Starts the recording from the click (Chrome asks which screen only
   on a gesture): the screen with a copy of the microphone, the camera
   with the microphone, the phone's camera on the phone, each to its
   file. Without the screen nothing is asked: the cameras record."
  [store]
  (if-not (contains? (sources store) :screen)
    (-> (begin! store nil)
        (.catch (fn [^js error] (failed! store error))))
    (-> (js/navigator.mediaDevices.getDisplayMedia
         #js {:video #js {:frameRate #js {:ideal 30}
                          :displaySurface "monitor"}
              :audio false
              :selfBrowserSurface "exclude"
              :surfaceSwitching "exclude"
              :systemAudio "exclude"})
        (.then (fn [^js display]
                 (begin! store display)))
        (.catch (fn [^js error]
                  ;; the person closed Chrome's picker: nothing happened
                  (when-not (= "NotAllowedError" (.-name error))
                    (failed! store error)))))))

(declare pair!)

(defn toggle!
  "A source ticked or unticked, remembered. The phone ticked without a
   phone connected shows the pairing at once."
  [store source phone]
  (let [now (if (contains? (sources store) source)
              (disj (sources store) source)
              (conj (sources store) source))]
    (remember! :sources (str/join "," (map name now)))
    (update! store assoc :sources now :error nil)
    (when (and (= :phone source) (contains? now :phone) (nil? phone) (nil? (:pair (:ui/recorder @store))))
      (pair! store))))

(defn pair!
  "A pairing link from the machine, as a QR code in the panel."
  [store]
  (update! store assoc :pair {:busy? true})
  (-> (post-phone! "code" {})
      (.then (fn [{:keys [url]}]
               (if url
                 (-> (.toDataURL qrcode url #js {:width 220 :margin 1})
                     (.then (fn [data-url]
                              (update! store assoc :pair {:url url :qr data-url}))))
                 (throw (js/Error. "No pairing link.")))))
      (.catch (fn [error]
                (update! store assoc :pair nil :error (str (.-message error)))))))

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

(defn- phone-section
  "What records, the phone connected or the way to pair one, its QR
   while shown."
  [store labels phone {:keys [pair]}]
  (let [ticked (sources store)]
    [:div.phone-camera
     [:div.modes
      [:span.label (:camera-choice labels)]
      (for [[source word] [[:screen (:source-screen labels)]
                           [:webcam (:source-webcam labels)]
                           [:phone (:source-phone labels)]]]
        [:label {:replicant/key source}
         [:input {:type "checkbox"
                  :checked (contains? ticked source)
                  :on {:change (fn [_] (toggle! store source phone))}}]
         word])]
   (if phone
     [:p.connected (str/replace (or (:phone-connected labels) "Phone connected: {device}") "{device}" (str (:device phone)))]
     [:p.hint (:pair-hint labels)])
   (cond
     (:qr pair)
     [:div.pairing
      [:img {:src (:qr pair) :alt (:url pair) :width 220 :height 220}]
      [:code (:url pair)]]

     (:busy? pair)
     nil

     :else
     [:button {:type "button"
               :on {:click (fn [_] (pair! store))}}
      (:pair labels)])]))

(defn panel
  "The panel above the text while it is open: the devices, the camera
   live with the meter, the phone as a camera, the test, Start; a bar
   with the time and Stop while it records. `labels` are the words, the
   server's, translated; `phone` the phone connected, from the frame
   (`rragent.phone/status`)."
  [store state labels phone]
  (let [{:keys [status devices mic camera seconds test error phone-waiting?] :as recorder} (:ui/recorder state)
        ticked (or (:sources recorder) default-sources)]
    (when status
      [:div.recorder {:replicant/key "recorder"}
       (if (#{:recording :finishing} status)
         [:div.live
          [:span.dot]
          [:span.time (clock seconds)]
          [:span.hint (cond
                        phone-waiting? (:phone-waiting labels)
                        (= :finishing status) (:saving labels)
                        (contains? ticked :screen) (:recording labels)
                        :else (:recording-local labels))]
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
             (phone-section store labels phone recorder)
             [:div.actions
              [:button {:type "button"
                        :disabled (not= :ready status)
                        :on {:click (fn [_] (test! store))}}
               (if (= :testing status)
                 (str (:testing labels) " " (clock seconds))
                 (:test labels))]
              [:button.primary {:type "button"
                                :disabled (or (not= :ready status) (empty? ticked))
                                :on {:click (fn [_] (start! store))}}
               (:start labels)]]
             [:p.hint (cond
                        (empty? ticked) (:none labels)
                        (contains? ticked :screen) (:screen-hint labels)
                        :else (:no-screen-hint labels))]))
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
                                              (nil? status) (open! store (assoc ctx :phone-url (str/replace (:url ctx) #"/record$" "/phone")))
                                              (#{:recording :finishing} status) nil
                                              :else (close! store)))}}
       "🎥"])))
