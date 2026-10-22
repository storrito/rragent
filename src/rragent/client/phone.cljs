(ns rragent.client.phone
  "The phone's page (`rragent.phone`, `/bot/<id>/phone`): the phone
   is a camera of a screencast. It shows its camera, says hello to the
   machine every few seconds, and listens for the laptop's start and
   stop: on start it records its camera and microphone at full
   quality into `phone.<webm|mp4>` of the recording, the pieces up as
   they come, the uploaders of `rragent.client.recorder`; on stop it
   sends the last pieces and tells the machine its file is up. Stills
   of the camera go up as fast as the network takes them, the phone's
   preview in the laptop's panel. The screen stays on while it records. Rendered by Replicant from the
   atom `state*`, nothing of the frames."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [replicant.dom :as d]
            [rragent.client.recorder :as recorder]))

(defonce ^:private state*
  (atom {:status :connecting}))

(defonce ^:private session*
  (atom nil))

(def hello-ms
  10000)

(def frame-ms
  "How often a still of the camera may go to the laptop's panel: six a
   second at most, fewer when one takes longer to go up."
  160)

(def frame-width
  "The width of a still: enough to frame the picture by."
  320)

(defn- words
  [k]
  (get-in @state* [:words k] (name k)))

(defn device-name
  "What the laptop shows: the phone's model when the browser says it
   (`model`, Chrome's client hint; the user agent's, unless it is the
   `K` Chrome puts there instead), else the system and the browser."
  [ua model]
  (let [ua (str ua)
        from-ua (some-> (re-find #"Android [^;]+; ([^;)]+)\)" ua) second str/trim)
        known (some #(when-not (or (str/blank? %) (= "K" %)) %) [(some-> model str/trim) from-ua])
        system (cond
                 (re-find #"iPhone" ua) "iPhone"
                 (re-find #"iPad" ua) "iPad"
                 (re-find #"Android" ua) "Android")
        browser (cond
                  (re-find #"SamsungBrowser" ua) "Samsung Internet"
                  (re-find #"Firefox|FxiOS" ua) "Firefox"
                  (re-find #"EdgA|EdgiOS" ua) "Edge"
                  (re-find #"CriOS|Chrome" ua) "Chrome"
                  (re-find #"Safari" ua) "Safari")]
    (or known
        (some->> [system browser] (remove nil?) seq (str/join " · "))
        "Phone")))

(defn- ^:async model
  "The phone's model from Chrome's client hints, nil elsewhere."
  []
  (try
    (some-> (await (some-> js/navigator .-userAgentData (.getHighEntropyValues #js ["model"])))
            .-model)
    (catch :default _
      nil)))

(defn- post!
  [action data]
  (js/fetch (str (:phone-url @state*) "/" action)
            #js {:method "POST"
                 :headers #js {"Content-Type" "application/transit+json"}
                 :body (t/write (t/writer :json) data)}))

(declare stop!)

(defn- hello!
  "The phone's presence for the laptop's panel, with the recording it
   is on: when the laptop stopped that one and the page missed the
   event (Android pauses a page in the background), the answer says
   so and the recording stops."
  []
  (let [{:keys [status stamp]} @state*
        recording (when (= :recording status) stamp)]
    (-> (post! "hello" (cond-> {:device (:device @state*)
                                :facing (:facing @state*)}
                         recording (assoc :recording recording)))
        (.then (fn [^js response] (.text response)))
        (.then (fn [text]
                 (when (and recording
                            (:stop (t/read (t/reader :json) text))
                            (= recording (:stamp @state*)))
                   (stop!))))
        (.catch (fn [_] nil)))))

(defn- mime-type
  []
  (some #(when (js/MediaRecorder.isTypeSupported %) %)
        ["video/webm;codecs=vp9,opus" "video/webm;codecs=vp8,opus" "video/webm" "video/mp4"]))

(defn- extension-of
  [mime]
  (if (str/includes? (str mime) "mp4") "mp4" "webm"))

(defn- stop-tracks!
  [^js stream]
  (when stream
    (doseq [^js track (array-seq (.getTracks stream))]
      (.stop track))))

(defn- ^:async camera!
  "The camera facing `facing`, with the microphone, live on the page."
  [facing]
  (stop-tracks! (:stream @session*))
  (let [stream (await (js/navigator.mediaDevices.getUserMedia
                       (clj->js {:audio true
                                 :video {:facingMode facing
                                         :width {:ideal 1920}
                                         :height {:ideal 1080}
                                         :frameRate {:ideal 30}}})))]
    (swap! session* assoc :stream stream)
    (when-let [^js node (:video @session*)]
      (set! (.-srcObject node) stream)
      (some-> (.play node) (.catch (fn [_] nil))))
    (swap! state* assoc :facing facing)
    stream))

(defn- still!
  "A still of the camera to the machine, for the laptop's panel to show
   the phone's picture; none while the last is still going up, or the
   page is hidden."
  []
  (let [^js node (:video @session*)]
    (when (and node
               (not (:sending-still? @session*))
               (not js/document.hidden)
               (pos? (.-videoWidth node)))
      (let [canvas (js/document.createElement "canvas")
            height (js/Math.round (* frame-width (/ (.-videoHeight node) (.-videoWidth node))))]
        (set! (.-width canvas) frame-width)
        (set! (.-height canvas) height)
        (.drawImage (.getContext canvas "2d") node 0 0 frame-width height)
        (swap! session* assoc :sending-still? true)
        (.toBlob canvas
                 (fn [^js blob]
                   (-> (if blob
                         (js/fetch (str (:phone-url @state*) "/frame")
                                   #js {:method "POST"
                                        :headers #js {"Content-Type" "image/jpeg"}
                                        :body blob})
                         (js/Promise.resolve nil))
                       (.catch (fn [_] nil))
                       (.finally (fn [] (swap! session* assoc :sending-still? false)))))
                 "image/jpeg"
                 0.6)))))

(defn- ^:async wake!
  []
  (try
    (when-let [lock (some-> js/navigator .-wakeLock)]
      (swap! session* assoc :wake (await (.request lock "screen"))))
    (catch :default _
      nil)))

(defn- tick!
  []
  (when-let [since (:since @session*)]
    (let [seconds (js/Math.floor (/ (- (js/performance.now) since) 1000))]
      (when (not= seconds (:seconds @state*))
        (swap! state* assoc :seconds seconds)))))

(defn- ^:async finish!
  "The last pieces up, the machine told. A test is the phone's own: it
   asks for the end itself and names its clip, so the laptop's panel
   can play it."
  [stamp]
  (try
    (await (:chain @(:uploader @session*)))
    (if (= "test" stamp)
      (let [ext (:ext @session*)
            response (await (js/fetch (str (:record-url @state*) "/test/finish")
                                      #js {:method "POST"
                                           :headers #js {"Content-Type" "application/transit+json"}
                                           :body (t/write (t/writer :json) {:files {"phone" ext} :test true})}))]
        (when-not (= 200 (.-status response))
          (throw (js/Error. (str "The test clip was not kept (" (.-status response) ")."))))
        (await (post! "done" {:stamp stamp :file (str "phone." ext)}))
        (swap! state* assoc :status :ready :tested? true))
      (do (await (post! "done" {:stamp stamp}))
          (swap! state* assoc :status :done :stamp stamp)))
    (catch :default error
      (js/console.error "[phone]" error)
      (swap! state* assoc :status :failed :error (str (.-message error))))
    (finally
      (some-> (:wake @session*) (.release))
      (some-> (:timer @session*) js/clearInterval)
      (swap! session* dissoc :recorder :uploader :since :wake :timer))))

(defn- start!
  "The laptop started the recording `stamp`: this camera records into
   its file of it; a test (`seconds`) stops by itself."
  [stamp & [seconds]]
  (when-not (#{:recording :testing} (:status @state*))
    (let [^js stream (:stream @session*)
          mime (mime-type)
          ext (extension-of mime)
          recorder (js/MediaRecorder. stream #js {:mimeType mime
                                                  :videoBitsPerSecond recorder/webcam-bits
                                                  :audioBitsPerSecond recorder/audio-bits})
          up (recorder/uploader (str (:record-url @state*) "/" stamp "/phone?ext=" ext))]
      (set! (.-ondataavailable recorder)
            (fn [^js event]
              (when (pos? (.. event -data -size))
                (recorder/push! up (.-data event)))))
      (set! (.-onstop recorder)
            (fn [_]
              (swap! state* assoc :status :uploading)
              (finish! stamp)))
      (swap! session* assoc
             :recorder recorder
             :uploader up
             :ext ext
             :since (js/performance.now)
             :timer (js/setInterval tick! 250))
      (wake!)
      (.start recorder recorder/piece-ms)
      (when seconds
        (js/setTimeout stop! (* 1000 seconds)))
      (swap! state* assoc :status (if seconds :testing :recording) :stamp stamp :seconds 0 :error nil :tested? false))))

(defn- stop!
  []
  (when-let [^js recorder (:recorder @session*)]
    (when (= "recording" (.-state recorder))
      (.stop recorder))))

(defn- listen!
  "The laptop's start and stop, server-sent events."
  []
  (let [source (js/EventSource. (str (:phone-url @state*) "/events"))]
    (.addEventListener source "start"
                       (fn [^js event]
                         (let [data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
                           (start! (:stamp data)))))
    (.addEventListener source "stop" (fn [_] (stop!)))
    (.addEventListener source "test"
                       (fn [^js event]
                         (let [data (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
                           (start! "test" (or (:seconds data) 5)))))
    (set! (.-onerror source) (fn [_] (swap! state* assoc :listening? false)))
    ;; back in touch: what was missed meanwhile is asked for at once
    (set! (.-onopen source) (fn [_]
                              (swap! state* assoc :listening? true)
                              (hello!)))
    (swap! session* assoc :source source)))

(defn- flip!
  []
  (let [facing (if (= "user" (:facing @state*)) "environment" "user")]
    (-> (camera! facing)
        (.then (fn [_] (hello!)))
        (.catch (fn [error]
                  (swap! state* assoc :error (str (.-message error))))))))

(defn- clock
  [seconds]
  (str (quot (or seconds 0) 60) ":" (.padStart (str (rem (or seconds 0) 60)) 2 "0")))

(defn- view
  [{:keys [status seconds error listening? stamp tested?] bot-name :name}]
  [:div.phone
   [:header
    [:strong (words :title)]
    [:span.bot bot-name]]
   [:div.camera
    [:video {:autoplay true
             :playsinline true
             :replicant/on-mount (fn [{:keys [replicant/node]}]
                                   (set! (.-muted ^js node) true)
                                   (swap! session* assoc :video node)
                                   (when-let [stream (:stream @session*)]
                                     (set! (.-srcObject ^js node) stream)
                                     (some-> (.play ^js node) (.catch (fn [_] nil)))))}]
    (when (#{:recording :testing} status)
      [:div.badge [:span.dot] (clock seconds)])]
   [:div.state {:class (name status)}
    (case status
      :connecting (words :connecting)
      :ready (cond
               (not listening?) (words :reconnecting)
               tested? (words :tested)
               :else (words :waiting))
      :testing (words :testing)
      :recording (words :recording)
      :uploading (words :uploading)
      :done (str/replace (words :done) "{stamp}" (str stamp))
      :failed (str/replace (words :failed) "{error}" (str error))
      "")]
   (when error
     (when-not (= :failed status)
       [:p.error error]))
   [:div.actions
    [:button {:type "button"
              :disabled (contains? #{:recording :testing} status)
              :on {:click (fn [_] (flip!))}}
     (words :flip)]]
   [:p.hint (words :keep-open)]])

(defn- render!
  []
  (when-let [^js node (:node @session*)]
    (d/render node (view @state*))))

(defn mount!
  "The page on `node` (`#phone`): the camera, the hello, the events."
  [^js node]
  (let [bot (.getAttribute node "data-bot")
        base (str "/bot/" bot)
        words (js->clj (js/JSON.parse (or (.getAttribute node "data-words") "{}")) :keywordize-keys true)]
    (reset! session* {:node node})
    (reset! state* {:status :connecting
                    :name (.getAttribute node "data-name")
                    :words words
                    :facing "environment"
                    :phone-url (str base "/phone")
                    :record-url (str base "/record")})
    (add-watch state* ::render (fn [_ _ _ _] (render!)))
    (render!)
    (-> (model)
        (.then (fn [model]
                 (swap! state* assoc :device (device-name js/navigator.userAgent model))
                 (camera! "environment")))
        (.then (fn [_]
                 (swap! state* assoc :status :ready)
                 (hello!)
                 (js/setInterval hello! hello-ms)
                 ;; a page back in front says hello at once
                 (.addEventListener js/document "visibilitychange"
                                    (fn [_]
                                      (when (= "visible" js/document.visibilityState)
                                        (hello!))))
                 (js/setInterval still! frame-ms)
                 (listen!)))
        (.catch (fn [error]
                  (js/console.error "[phone]" error)
                  (swap! state* assoc :status :failed :error (str (.-message error))))))))
