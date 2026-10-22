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
   for the agent to cut. An Android phone's screen comes over ADB
   (`rragent.client.usb-screen`: the laptop's adb server through its
   bridge, else USB from Chrome itself): connected in the panel, shown
   live there, recorded here like the laptop's screen, with a copy of
   the microphone, into `phone-screen.webm`.

   The state is in the store under `:ui/recorder`: the `:status`
   (`:opening`, `:ready`, `:testing`, `:recording`, `:finishing`,
   `:failed`), the `:devices`, the `:mic` and `:camera` chosen, the
   `:sources` ticked, the `:pair` (the link and its QR while shown),
   the `:seconds` recorded, the `:tests` (the clips of the last test,
   one per source: object URLs of the laptop's, the phone's on the
   machine) and the `:error`.
   What is no data (the streams, the recorders, the uploaders, the
   meter and the preview node, the URL and the labels the panel was
   opened with) is in `session*`."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            ["qrcode" :as qrcode]
            [rragent.client.usb-screen :as usb]))

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

(def default-sources
  #{:screen :webcam})

(def ^:private exclusive
  "The phone's camera and the phone's screen exclude each other: while
   the phone is used, its camera page is in the background, and Android
   gives a page in the background the sound only, a picture a second at
   most. Each ticked unticks the other."
  {:phone :phone-screen
   :phone-screen :phone})

(defn- remembered-sources
  []
  (if-let [kept (remembered :sources)]
    (let [found (set (keep #(#{:screen :webcam :phone :phone-screen} (keyword %)) (str/split kept #",")))]
      (cond-> found
        (every? found [:phone :phone-screen]) (disj :phone-screen)))
    default-sources))

(defn- sources
  [store]
  (or (:sources (:ui/recorder @store)) default-sources))

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

(declare phone-url)

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
                          :sources (remembered-sources)
                          :minimized? (= "1" (remembered :minimized)))
                 (preview! store))))
      (.then (fn [_]
               (swap! session* assoc :timer (js/setInterval #(tick! store) 50))
               (update! store assoc :status :ready)))
      (.catch (fn [error]
                (failed! store error)))))

(defn- release!
  "Lets the devices go and forgets the session."
  []
  (let [{:keys [timer preview display clone usb-clone usb audio-context]} @session*]
    (some-> timer js/clearInterval)
    (stop-tracks! preview)
    (stop-tracks! display)
    (some-> ^js clone .stop)
    (some-> ^js usb-clone .stop)
    (some-> (:close usb) (apply []))
    (some-> ^js audio-context .close)
    (reset! session* nil)))

(defn- forget-tests!
  "The clips of the last test let go."
  [store]
  (doseq [[_ url] (:tests (:ui/recorder @store))]
    (when (str/starts-with? (str url) "blob:")
      (js/URL.revokeObjectURL url)))
  (update! store dissoc :tests))

(defn close!
  "Closes the panel, unless it records."
  [store]
  (when-not (#{:recording :finishing} (:status (:ui/recorder @store)))
    (forget-tests! store)
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

(def display-options
  "What Chrome's screen picker is asked for: the whole screen, without
   its own tab and without the sound of the system."
  #js {:video #js {:frameRate #js {:ideal 30} :displaySurface "monitor"}
       :audio false
       :selfBrowserSurface "exclude"
       :surfaceSwitching "exclude"
       :systemAudio "exclude"})

(declare stop!)

(defn- live?
  "Whether the screen chosen is still shared: Chrome's own Stop
   sharing ends its track."
  [^js display]
  (some-> display .getVideoTracks array-seq first .-readyState (= "live")))

(defn- drop-screen!
  "The screen let go: no longer shared, no longer shown."
  [store]
  (stop-tracks! (:display @session*))
  (swap! session* #(some-> % (dissoc :display)))
  (update! store dissoc :screen?))

(defn- ^:async screen!
  "The screen, live in the panel: the one chosen already, or Chrome's
   picker from the click. Test and Start record it, so the picker is
   asked once. Chrome's own Stop sharing ends a recording, and before
   one lets the screen go."
  [store]
  (let [kept (:display @session*)]
    (if (live? kept)
      kept
      (let [^js display (await (js/navigator.mediaDevices.getDisplayMedia display-options))
            ^js track (first (array-seq (.getVideoTracks display)))]
        (stop-tracks! kept)
        (swap! session* assoc :display display)
        (set! (.-onended track)
              (fn [_]
                (when (identical? display (:display @session*))
                  (if (= :recording (:status (:ui/recorder @store)))
                    (stop! store)
                    (drop-screen! store)))))
        (when-let [^js node (:screen-node @session*)]
          (set! (.-srcObject node) display))
        (update! store assoc :screen? true)
        display))))

(defn choose-screen!
  "Another screen from Chrome's picker, shown in the panel; closing the
   picker keeps the one there was."
  [store]
  (let [kept (:display @session*)]
    (swap! session* dissoc :display)
    (-> (screen! store)
        (.then (fn [_] (stop-tracks! kept)))
        (.catch (fn [^js error]
                  (swap! session* assoc :display kept)
                  (when-not (= "NotAllowedError" (.-name error))
                    (failed! store error)))))))

(defn- drop-usb!
  "The phone over USB let go: scrcpy ended, the phone free again."
  [store]
  (some-> (:close (:usb @session*)) (apply []))
  (swap! session* #(some-> % (dissoc :usb)))
  (update! store dissoc :usb))

(def usb-dark-ms
  "How long after connecting the panel waits for the phone's first
   frame before it says to wake the phone's screen."
  3000)

(defn- usb-error
  "What the panel says when the phone over USB did not come."
  [^js error]
  (let [words (get-in @session* [:ctx :labels])]
    (cond
      (= :busy (:reason (ex-data error))) (or (:usb-busy words) "An adb server on this laptop holds the phone: start the bridge to it (scripts/adb-bridge.mjs), then connect again.")
      (= :no-phone (:reason (ex-data error))) (or (:usb-no-phone words) "The adb server on this laptop knows no phone: plug it in with USB debugging on.")
      (= :no-usb (:reason (ex-data error))) (or (:usb-unsupported words) "This browser cannot reach a phone over USB.")
      (#{"SecurityError" "NotAllowedError"} (some-> error .-name)) (or (:usb-denied words) "Chrome may not use this USB device. On Linux it needs a udev rule for the phone.")
      :else (str (or (some-> error .-message) error)))))

(defn connect-usb!
  "The phone's screen over USB from the click: Chrome's picker, the
   phone asked to allow this browser once, then its picture live in the
   panel. A phone unplugged ends a recording, as Chrome's Stop sharing
   does, and is let go."
  [store]
  (update! store assoc :usb {:status :picking} :error nil)
  (-> (usb/connect! (fn [name]
                      (update! store assoc :usb {:status :connecting :name name})))
      (.then (fn [{:keys [name exited] :as connection}]
               (if-not connection
                 (update! store dissoc :usb)
                 (let [id (str (random-uuid))
                       current? #(= id (get-in @session* [:usb :id]))
                       frames (:frames connection)]
                   (swap! session* assoc :usb (assoc connection :id id))
                   (update! store assoc :usb {:status :live :name name :id id})
                   ;; no picture yet a while after connecting: the display is off
                   (letfn [(check! []
                             (when (current?)
                               (if (pos? (frames))
                                 (update! store update :usb dissoc :dark?)
                                 (do (update! store assoc-in [:usb :dark?] true)
                                     (js/setTimeout check! 1000)))))]
                     (js/setTimeout check! usb-dark-ms))
                   (.then exited (fn [_]
                                   (when (= id (get-in @session* [:usb :id]))
                                     (when (= :recording (:status (:ui/recorder @store)))
                                       (stop! store))
                                     (swap! session* update :usb dissoc :close)
                                     (update! store dissoc :usb))))))))
      (.catch (fn [error]
                (js/console.error "[recorder] usb" error)
                (update! store assoc :usb nil :error (usb-error error))))))

(defn- usb-track
  "The phone's picture over USB, nil while none is connected."
  []
  (get-in @session* [:usb :track]))

(defn- usb-missing?
  "Whether the phone's screen is ticked but no phone is connected over
   USB; the panel says so then."
  [store]
  (when (and (contains? (sources store) :phone-screen) (nil? (usb-track)))
    (update! store assoc :error (or (get-in @session* [:ctx :labels :usb-none]) "Connect the phone over USB first."))
    true))

(defn- clip
  "A promise of an object URL: `stream` recorded for `test-ms`."
  [^js stream bits]
  (js/Promise.
   (fn [resolve reject]
     (let [recorder (js/MediaRecorder. stream #js {:mimeType (mime-type)
                                                   :videoBitsPerSecond bits
                                                   :audioBitsPerSecond audio-bits})
           chunks #js []]
       (set! (.-ondataavailable recorder)
             (fn [^js event]
               (when (pos? (.. event -data -size))
                 (.push chunks (.-data event)))))
       (set! (.-onstop recorder)
             (fn [_]
               (resolve (js/URL.createObjectURL (js/Blob. chunks #js {:type (.-mimeType recorder)})))))
       (set! (.-onerror recorder) (fn [^js event] (reject (.-error event))))
       (.start recorder)
       (js/setTimeout (fn []
                        (when (= "recording" (.-state recorder))
                          (.stop recorder)))
                      test-ms)))))

(defn- ^:async phone-clip
  "The phone's test clip: the phone is asked for `test-ms`, and the
   machine is asked for the clip until the phone has named it (its
   `at` newer than the asking); the URL the panel plays it from."
  [asked-at]
  (let [url (str (phone-url) "/test")]
    (loop [tries 0]
      (let [{:keys [file at]} (try
                                (let [response (await (js/fetch url))]
                                  (t/read (t/reader :json) (await (.text response))))
                                (catch :default _
                                  nil))]
        (cond
          (and file at (> at asked-at))
          (str (str/replace (phone-url) #"/phone$" "/record") "/test/" file "?t=" at)

          (< tries 40)
          (do (await (sleep 1000))
              (recur (inc tries)))

          :else
          (throw (js/Error. "The phone's test clip did not come.")))))))

(defn- ^:async clips!
  "The test clips of every source ticked, into `:tests` as each comes:
   the screen (the one in the panel, or Chrome asks which, from the
   click) and the camera recorded here, the phone's on the phone."
  [store]
  (let [wanted (sources store)
        ^js preview (:preview @session*)
        ^js display (when (contains? wanted :screen)
                      (await (screen! store)))
        asked-at (js/Date.now)
        phones (when (contains? wanted :phone)
                 (:phones (await (post-phone! "test" {:seconds (/ test-ms 1000)}))))
        took (fn [source promise]
               (-> promise
                   (.then (fn [url] (update! store assoc-in [:tests source] url)))
                   (.catch (fn [error] (update! store assoc-in [:tests source] {:error (str (.-message error))})))))]
    (when (and (contains? wanted :phone) (not (pos? (or phones 0))))
      (throw (js/Error. (or (get-in @session* [:ctx :labels :phone-none]) "No phone is connected."))))
    (swap! session* assoc :since (js/performance.now))
    (update! store assoc :status :testing :seconds 0 :tests (into {} (map (fn [source] [source nil]) wanted)))
    (await (js/Promise.all
            (clj->js (cond-> []
                       display (conj (took :screen (clip (js/MediaStream. #js [(first (array-seq (.getVideoTracks display)))
                                                                                (.clone ^js (first (array-seq (.getAudioTracks preview))))])
                                                          screen-bits)))
                       (contains? wanted :webcam) (conj (took :webcam (clip preview webcam-bits)))
                       (contains? wanted :phone-screen) (conj (took :phone-screen (clip (js/MediaStream. #js [(usb-track)
                                                                                                             (.clone ^js (first (array-seq (.getAudioTracks preview))))])
                                                                                          screen-bits)))
                       (contains? wanted :phone) (conj (took :phone (phone-clip asked-at)))))))))

(defn test!
  "A few seconds of every source ticked, played back in the panel so
   that the whole setup can be checked; nothing of it is kept as an
   artifact."
  [store]
  (forget-tests! store)
  (when-not (usb-missing? store)
    (-> (clips! store)
      (.then (fn [_] (update! store assoc :status :ready)))
      (.catch (fn [^js error]
                (if (= "NotAllowedError" (.-name error))
                  (update! store assoc :status :ready :tests nil)
                  (failed! store error)))))))

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
              "phone" (or (:file-phone labels) "the phone's camera")
              "phone-screen" (or (:file-phone-screen labels) "the phone's screen")}
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
      (update! store assoc :error (or (get-in @session* [:ctx :labels :phone-none]) "No phone is connected."))
      (let [^js preview (:preview @session*)
            ^js screen-track (when display (first (array-seq (.getVideoTracks display))))
            ^js clone (when display (.clone ^js (first (array-seq (.getAudioTracks preview)))))
            phone-track (when (contains? wanted :phone-screen) (usb-track))
            ^js usb-clone (when phone-track (.clone ^js (first (array-seq (.getAudioTracks preview)))))
            base (str (get-in @session* [:ctx :url]) "/" the-stamp)
            mime (mime-type)
            recorders (cond-> {}
                        display (assoc "screen" (recorder-of store base mime (js/MediaStream. #js [screen-track clone]) "screen" screen-bits))
                        (contains? wanted :webcam) (assoc "webcam" (recorder-of store base mime preview "webcam" webcam-bits))
                        phone-track (assoc "phone-screen" (recorder-of store base mime (js/MediaStream. #js [phone-track usb-clone]) "phone-screen" screen-bits)))]
        (swap! session* assoc
               :clone clone
               :usb-clone usb-clone
               :recorders recorders
               :phone? phone?
               :stamp the-stamp
               :base base
               :mime mime
               :started {}
               :stopped {}
               :since (js/performance.now))
        (doseq [{:keys [^js recorder]} (vals recorders)]
          (.start recorder piece-ms))
        (update! store assoc :status :recording :seconds 0 :error nil :started-at (js/Date.now))))))

(defn start!
  "Starts the recording from the click (Chrome asks which screen only
   on a gesture, and only when none is in the panel yet): the screen
   with a copy of the microphone, the camera with the microphone, the
   phone's camera on the phone, each to its file. Without the screen
   nothing is asked: the cameras record. The phone's screen over USB
   must be connected first."
  [store]
  (cond
    (usb-missing? store)
    nil

    (not (contains? (sources store) :screen))
    (-> (begin! store nil)
        (.catch (fn [^js error] (failed! store error))))

    :else
    (-> (screen! store)
        (.then (fn [^js display]
                 (begin! store display)))
        (.catch (fn [^js error]
                  ;; the person closed Chrome's picker: nothing happened
                  (when-not (= "NotAllowedError" (.-name error))
                    (failed! store error)))))))

(declare pair!)

(defn toggle!
  "A source ticked or unticked, remembered. The phone's camera and its
   screen exclude each other (`exclusive`): ticking one unticks the
   other. The phone ticked without a phone connected shows the pairing
   at once, unticked hides it; the screen unticked is let go, the phone
   over USB too."
  [store source phone]
  (let [before (sources store)
        now (if (contains? before source)
              (disj before source)
              (disj (conj before source) (exclusive source)))]
    (remember! :sources (str/join "," (map name now)))
    (update! store assoc :sources now :error nil)
    (doseq [gone (remove now before)]
      (case gone
        :screen (drop-screen! store)
        :phone (update! store dissoc :pair)
        :phone-screen (drop-usb! store)
        nil))
    (when (and (= :phone source) (contains? now :phone) (nil? phone) (nil? (:pair (:ui/recorder @store))))
      (pair! store))))

(defn pair!
  "A pairing link from the machine, as a QR code in the panel; not
   shown when the phone was unticked while the link was coming."
  [store]
  (update! store assoc :pair {:busy? true})
  (let [asked? #(some? (:pair (:ui/recorder @store)))]
    (-> (post-phone! "code" {})
        (.then (fn [{:keys [url]}]
                 (if url
                   (-> (.toDataURL qrcode url #js {:width 220 :margin 1})
                       (.then (fn [data-url]
                                (when (asked?)
                                  (update! store assoc :pair {:url url :qr data-url})))))
                   (throw (js/Error. "No pairing link.")))))
        (.catch (fn [error]
                  (when (asked?)
                    (update! store assoc :pair nil :error (str (.-message error)))))))))

(defn- preview-mount
  [{:keys [replicant/node]}]
  (set! (.-muted ^js node) true)
  (swap! session* #(some-> % (assoc :preview-node node)))
  (when-let [stream (:preview @session*)]
    (set! (.-srcObject ^js node) stream)))

(defn- screen-mount
  [{:keys [replicant/node]}]
  (swap! session* #(some-> % (assoc :screen-node node)))
  (when-let [display (:display @session*)]
    (set! (.-srcObject ^js node) display)))

(defn- usb-preview
  "The phone's screen over USB when it is ticked: live once connected,
   the button to connect it otherwise; a word when this browser cannot
   reach a phone over USB."
  [store labels {:keys [status name id dark?]} ready?]
  [:figure.usb-screen {:replicant/key "usb"}
   (cond
     (not (usb/supported?))
     [:p.hint (:usb-unsupported labels)]

     (= :live status)
     (list
      [:div.usb-video {:replicant/key id
                       :replicant/on-mount (fn [{:keys [replicant/node]}]
                                             (when-let [element (get-in @session* [:usb :element])]
                                               (.appendChild ^js node element)))}]
      [:figcaption name]
      (when dark?
        [:p.hint (:usb-dark labels)]))

     :else
     [:button.choose {:type "button"
                      :disabled (or (not ready?) (#{:picking :connecting} status))
                      :on {:click (fn [_] (connect-usb! store))}}
      (case status
        :picking (:usb-picking labels)
        :connecting (:usb-connecting labels)
        (:usb-connect labels))])])

(defn- screen-preview
  "The screen in the panel when it is ticked: live once chosen, with a
   way to another; the button to choose it otherwise."
  [store labels screen? ready?]
  [:figure.screen {:replicant/key "screen"}
   (if screen?
     (list
      [:video.screen {:replicant/key "live"
                      :autoplay true
                      :muted true
                      :playsinline true
                      :replicant/on-mount screen-mount
                      :replicant/on-unmount (fn [_] (swap! session* #(some-> % (dissoc :screen-node))))}]
      [:figcaption
       (:source-screen labels)
       [:button.link {:type "button"
                      :disabled (not ready?)
                      :on {:click (fn [_] (choose-screen! store))}}
        (:screen-change labels)]])
     [:button.choose {:type "button"
                      :disabled (not ready?)
                      :on {:click (fn [_] (choose-screen! store))}}
      (:screen-choose labels)])])

(defn- ^:async watch-stills!
  "The phone's stills on `node` as they come: the machine holds each
   ask until a newer still is there (`rragent.phone`); until the node
   goes or the ask is aborted. A failed ask is tried again after a
   second."
  [^js node ^js controller]
  (loop [after 0
         ^js shown nil]
    (when-not (.. controller -signal -aborted)
      (let [[after shown]
            (try
              (let [response (await (js/fetch (str (phone-url) "/frame?after=" after)
                                              #js {:signal (.-signal controller)}))]
                (if (= 200 (.-status response))
                  (let [url (js/URL.createObjectURL (await (.blob response)))]
                    (set! (.-src node) url)
                    (.remove (.-classList node) "none")
                    (some-> shown js/URL.revokeObjectURL)
                    [(or (parse-long (str (.get (.-headers response) "X-Still"))) after) url])
                  [after shown]))
              (catch :default _
                (when-not (.. controller -signal -aborted)
                  (await (sleep 1000)))
                [after shown]))]
        (recur after shown)))))

(defn- phone-preview
  "The phone's picture in the panel, its stills as they come, while a
   phone is connected and ticked; nothing while it has not sent one."
  [phone]
  [:figure.phone-still {:replicant/key "phone"}
   [:img.phone-still {:alt ""
                      :replicant/on-mount (fn [{:keys [replicant/node]}]
                                            (let [controller (js/AbortController.)]
                                              (.add (.-classList ^js node) "none")
                                              (set! (.-__stills node) controller)
                                              (watch-stills! node controller)))
                      :replicant/on-unmount (fn [{:keys [replicant/node]}]
                                              (some-> ^js (.-__stills node) .abort))}]
   [:figcaption (:device phone)]])

(defn toggle-minimized!
  "The live views of a recording hidden or shown, remembered: hidden,
   the panel is the bar with the time and Stop."
  [store]
  (let [minimized? (not (:minimized? (:ui/recorder @store)))]
    (remember! :minimized (if minimized? "1" "0"))
    (update! store assoc :minimized? minimized?)))

(defn clock-time
  "When the recording started, as the clock here says it: 15:25:22."
  [ms]
  (.toLocaleTimeString (js/Date. ms) js/undefined #js {:hour "2-digit" :minute "2-digit" :second "2-digit"}))

(defn- previews
  "What is recorded, live: the camera, the screen chosen, the phone's
   stills, the phone's screen over USB; the same before and while it
   records. `ready?` lets the buttons to choose a screen and connect a
   phone work."
  [store labels {:keys [screen? usb] :as recorder} phone ready?]
  (let [ticked (or (:sources recorder) default-sources)]
    [:div.preview {:replicant/key "preview"}
     [:video.camera {:autoplay true
                     :playsinline true
                     :replicant/on-mount preview-mount}]
     (when (contains? ticked :screen)
       (screen-preview store labels screen? ready?))
     (when (and phone (contains? ticked :phone))
       (phone-preview phone))
     (when (contains? ticked :phone-screen)
       (usb-preview store labels usb ready?))]))

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
                           [:phone (:source-phone labels)]
                           [:phone-screen (:source-phone-screen labels)]]]
        [:label {:replicant/key source}
         [:input {:type "checkbox"
                  :checked (contains? ticked source)
                  :on {:change (fn [_] (toggle! store source phone))}}]
         word])]
   (if phone
     [:p.connected (str/replace (or (:phone-connected labels) "Phone connected: {device}")
                                "{device}"
                                (str (:device phone)
                                     (case (:facing phone)
                                       "environment" (str " · " (:phone-back labels))
                                       "user" (str " · " (:phone-front labels))
                                       nil)))]
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
  "The panel above the text while it is open: the devices, the meter
   beside the microphone, the camera, the screen and the phone live,
   the test, Start. While it records: a bar with the time, when it
   started and Stop, and the live views under it, which can be hidden
   (`toggle-minimized!`). `labels` are the words, the server's,
   translated; `phone` the phone connected, from the frame
   (`rragent.phone/status`)."
  [store state labels phone]
  (let [{:keys [status devices mic camera seconds tests error phone-waiting? minimized? started-at] :as recorder} (:ui/recorder state)
        ticked (or (:sources recorder) default-sources)]
    (when status
      [:div.recorder {:replicant/key "recorder"
                      :class (when (and minimized? (#{:recording :finishing} status)) "minimized")}
       (if (#{:recording :finishing} status)
         (list
          [:div.live {:replicant/key "live"}
           [:span.dot]
           [:span.time (clock seconds)]
           (when started-at
             [:span.started (str/replace (or (:started labels) "started at {time}") "{time}" (clock-time started-at))])
           [:span.hint (cond
                         phone-waiting? (:phone-waiting labels)
                         (= :finishing status) (:saving labels)
                         (contains? ticked :screen) (:recording labels)
                         :else (:recording-local labels))]
           [:button.link {:type "button"
                          :on {:click (fn [_] (toggle-minimized! store))}}
            (if minimized? (:show-live labels) (:hide-live labels))]
           (when (= :recording status)
             [:button.primary {:type "button"
                               :on {:click (fn [_] (stop! store))}}
              (:stop labels)])]
          (when-not minimized?
            (previews store labels recorder phone false)))
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
               [:span.mic
                (device-select store :mic mic (:mics devices))
                [:span.meter {:replicant/on-mount (fn [{:keys [replicant/node]}]
                                                    (swap! session* #(some-> % (assoc :meter node))))
                              :replicant/on-unmount (fn [_]
                                                      (swap! session* #(some-> % (dissoc :meter))))}
                 [:span] [:span] [:span] [:span] [:span]]]]
              [:label (:camera labels)
               (device-select store :camera camera (:cameras devices))]]
             (previews store labels recorder phone (= :ready status))
             (when (seq tests)
               [:div.tests
                (for [source [:screen :webcam :phone :phone-screen]
                      :let [url (get tests source ::none)]
                      :when (not= ::none url)]
                  [:figure {:replicant/key source}
                   (cond
                     (nil? url) [:div.pending [:span.dots [:span] [:span] [:span]]]
                     (map? url) [:p.error (:error url)]
                     :else [:video.test {:replicant/key url
                                         :src url
                                         :controls true}])
                   [:figcaption (get labels (keyword (str "source-" (name source))))]])])
             (phone-section store labels phone recorder)
             [:div.actions
              [:button {:type "button"
                        :disabled (or (not= :ready status) (empty? ticked))
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
