(ns rragent.client.usb-screen
  "An Android phone's screen in the page: the page talks ADB to the
   phone (Tango's `@yume-chan/adb`), pushes the scrcpy server to it and
   starts it (github.com/Genymobile/scrcpy), and decodes its H.264 with
   WebCodecs into a video track of the page. The panel of the recorder
   (`rragent.client.recorder`) shows the track and records it like the
   laptop's screen.

   ADB comes two ways. When the laptop runs an adb server, it holds the
   phone and Chrome cannot claim it: the page then goes through that
   server, by the laptop's bridge (`scripts/adb-bridge.mjs`, a WebSocket
   on 127.0.0.1 per connection to the server), so adb and its tunnels
   go on as before. Without the bridge, Chrome claims the phone itself
   over USB (WebUSB): USB debugging on, the phone allows this browser
   once, the key it allows lives in the browser (IndexedDB).

   The scrcpy server is served by the machine (`/scrcpy/`, its version
   is the one the packages speak). Only the phone's picture, no sound
   and no control of it; the display is woken when the phone connects,
   an asleep display sends no frames."
  (:require ["@yume-chan/adb" :refer [Adb AdbDaemonTransport AdbServerClient]]
            ["@yume-chan/adb-credential-web" :default AdbWebCredentialStore]
            ["@yume-chan/adb-daemon-webusb" :refer [AdbDaemonWebUsbDevice AdbDaemonWebUsbDeviceManager]]
            ["@yume-chan/adb-scrcpy" :refer [AdbScrcpyClient AdbScrcpyOptionsLatest]]
            ["@yume-chan/scrcpy" :refer [DefaultServerPath]]
            ["@yume-chan/scrcpy-decoder-webcodecs" :refer [InsertableStreamVideoFrameRenderer WebCodecsVideoDecoder]]
            ["@yume-chan/stream-extra" :refer [MaybeConsumable]]))

(def server-url
  "The scrcpy server, as the machine serves it from `public/scrcpy/`."
  "/scrcpy/scrcpy-server-v3.3.3")

(def bridge-url
  "The laptop's bridge to its adb server (`scripts/adb-bridge.mjs`)."
  "ws://127.0.0.1:15037/")

(def bridge-probe-ms
  "How long the page waits for the bridge before it goes over USB
   itself; short, so that Chrome's USB picker still counts the click."
  1500)

(def video-options
  "The phone's picture: H.264 at 30 frames a second, its longer side at
   most 1920 pixels, the phone kept awake while it is plugged in."
  {:video true
   :audio false
   :control false
   :videoCodec "h264"
   :maxSize 1920
   :maxFps 30
   :videoBitRate 8000000
   :stayAwake true})

(defn supported?
  "Whether this browser can show a phone's screen: WebCodecs and a
   track made of frames (Chrome on a desktop); WebUSB too, unless the
   laptop's bridge is there."
  []
  (boolean (and (.-isSupported WebCodecsVideoDecoder)
                (.-isSupported InsertableStreamVideoFrameRenderer))))

(defn- ^:async socket!
  "A WebSocket to the bridge, open; rejected when it does not open in
   `ms`."
  [ms]
  (js/Promise.
   (fn [resolve reject]
     (let [ws (js/WebSocket. bridge-url)
           timer (js/setTimeout (fn []
                                  (.close ws)
                                  (reject (js/Error. "no bridge")))
                                ms)]
       (set! (.-binaryType ws) "arraybuffer")
       (set! (.-onopen ws) (fn [_]
                             (js/clearTimeout timer)
                             (resolve ws)))
       (set! (.-onerror ws) (fn [_]
                              (js/clearTimeout timer)
                              (reject (js/Error. "no bridge"))))))))

(defn- connection
  "One connection to the adb server over the open WebSocket `ws`, as
   Tango's server client takes it."
  [^js ws]
  (let [closed (js/Promise. (fn [resolve]
                              (.addEventListener ws "close" (fn [_] (resolve js/undefined)))))]
    #js {:readable (js/ReadableStream.
                    #js {:start (fn [^js controller]
                                  (set! (.-onmessage ws) (fn [^js event]
                                                           (.enqueue controller (js/Uint8Array. (.-data event)))))
                                  (.addEventListener ws "close" (fn [_]
                                                                  (try (.close controller) (catch :default _ nil)))))
                         :cancel (fn [_] (.close ws))})
         :writable (new (.-WritableStream MaybeConsumable)
                        #js {:write (fn [chunk] (.send ws chunk))
                             :close (fn [] (.close ws))
                             :abort (fn [_] (.close ws))})
         :closed closed
         :close (fn [] (.close ws))}))

(def ^:private connector
  "Tango's server client over the bridge: a WebSocket per connection,
   no reverse tunnels (scrcpy's tunnel goes forward)."
  #js {:connect (fn [_] (.then (socket! 10000) connection))
       :addReverseTunnel (fn [_ _] (js/Promise.reject (js/Error. "no reverse tunnels through the bridge")))
       :removeReverseTunnel (fn [_] (js/Promise.resolve nil))
       :clearReverseTunnels (fn [] (js/Promise.resolve nil))})

(defn- ^:async bridge?
  "Whether the laptop's bridge answers."
  []
  (try
    (.close ^js (await (socket! bridge-probe-ms)))
    true
    (catch :default _
      false)))

(defn- ^:async adb-from-server!
  "The phone through the laptop's adb server: the one plugged in by USB
   when there is one, else the first it knows; nil when it knows none."
  [on-picked]
  (let [client (AdbServerClient. connector)
        devices (array-seq (await (.getDevices client #js ["device"])))
        ^js device (or (first (remove #(re-find #":" (.-serial ^js %)) devices))
                       (first devices))]
    (when-not device
      (throw (ex-info "no phone" {:reason :no-phone})))
    (let [name (or (some-> (.-model device) (.replaceAll "_" " ")) (.-serial device))]
      (on-picked name)
      {:adb (Adb. (await (.createTransport client #js {:transportId (.-transportId device)})))
       :name name
       :via :adb-server})))

(defn- busy?
  [error]
  (instance? (.-DeviceBusyError AdbDaemonWebUsbDevice) error))

(defn- ^:async device!
  "The phone over WebUSB: the one this site was allowed before when it
   is the only one plugged in, else the one Chrome's picker gives; nil
   when the person closed the picker."
  []
  (let [^js manager (.-BROWSER AdbDaemonWebUsbDeviceManager)]
    (when-not manager
      (throw (ex-info "no usb" {:reason :no-usb})))
    (let [known (array-seq (await (.getDevices manager)))]
      (if (= 1 (count known))
        (first known)
        (await (.requestDevice manager))))))

(defn- ^:async adb-over-usb!
  "The phone over WebUSB, connected and allowed; nil when the person
   closed the picker. `on-picked` hears of the phone once it is chosen,
   before it asks to allow this browser."
  [on-picked]
  (when-let [^js device (await (device!))]
    (on-picked (.-name device))
    (let [connection (try
                       (await (.connect device))
                       (catch :default error
                         (throw (if (busy? error)
                                  (ex-info "busy" {:reason :busy} error)
                                  error))))
          transport (await (.authenticate AdbDaemonTransport
                                          #js {:serial (.-serial device)
                                               :connection connection
                                               :credentialStore (AdbWebCredentialStore. "rragent")}))]
      {:adb (Adb. transport)
       :name (.-name device)
       :via :webusb})))

(defn- ^:async push-server!
  [^js adb]
  (let [response (await (js/fetch server-url))
        _ (when-not (.-ok response)
            (throw (js/Error. (str "The scrcpy server is not there (HTTP " (.-status response) ")."))))
        bytes (js/Uint8Array. (await (.arrayBuffer response)))]
    (await (.pushServer AdbScrcpyClient adb
                        (js/ReadableStream. #js {:start (fn [^js controller]
                                                          (.enqueue controller bytes)
                                                          (.close controller))})))))

(defn- ^:async wake!
  "The phone's display on: an asleep display sends no frames."
  [^js adb]
  (try
    (await (.spawnWaitText (.. adb -subprocess -noneProtocol) "input keyevent KEYCODE_WAKEUP"))
    (catch :default error
      (js/console.warn "[usb-screen] the display was not woken" error))))

(defn ^:async connect!
  "The phone's screen: through the laptop's adb server when its bridge
   answers, else Chrome's USB picker from the click; the display woken,
   scrcpy started. A map with the phone's `:name`, `:via` (`:adb-server`
   or `:webusb`), the `:track` of its picture, the `:element` that shows
   it, `:frames`, how many frames came so far, `:exited`, a promise that
   settles when the phone is gone, and `:close`; nil when the person
   closed the picker. A phone another program holds throws with
   `{:reason :busy}`, an adb server that knows no phone with
   `{:reason :no-phone}`. `on-picked` hears of the phone once it is
   chosen."
  [on-picked]
  (when-let [{:keys [^js adb name via]} (await (if (await (bridge?))
                                                 (adb-from-server! on-picked)
                                                 (adb-over-usb! on-picked)))]
    (try
      (await (wake! adb))
      (await (push-server! adb))
      (let [options (cond-> video-options
                      (= :adb-server via) (assoc :tunnelForward true))
            ^js client (await (.start AdbScrcpyClient adb DefaultServerPath
                                      (AdbScrcpyOptionsLatest. (clj->js options))))
            ;; scrcpy's lines must be read, or the connection stalls
            _ (-> (.pipeTo (.-output client) (js/WritableStream. #js {:write (fn [line] (js/console.debug "[scrcpy]" line))}))
                  (.catch (fn [_] nil)))
            ^js video (await (.-videoStream client))
            renderer (InsertableStreamVideoFrameRenderer.)
            decoder (WebCodecsVideoDecoder. #js {:codec (.. video -metadata -codec)
                                                 :renderer renderer})
            ^js element (.-element renderer)
            track (first (array-seq (.getVideoTracks ^js (.-srcObject element))))]
        (-> (.pipeTo (.-stream video) (.-writable decoder))
            (.catch (fn [_] nil)))
        {:name name
         :via via
         :track track
         :element element
         :frames (fn [] (.-framesRendered decoder))
         :exited (.-exited client)
         :close (fn []
                  (-> (js/Promise.allSettled #js [(.close client)])
                      (.then (fn [_]
                               (.dispose decoder)
                               (.stop ^js track)
                               (.close adb)))
                      (.catch (fn [_] nil))))})
      (catch :default error
        (.catch (.close adb) (fn [_] nil))
        (throw error)))))
