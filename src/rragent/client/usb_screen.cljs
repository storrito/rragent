(ns rragent.client.usb-screen
  "An Android phone's screen in the page, over a USB cable: Chrome talks
   ADB to the phone itself (WebUSB, Tango's `@yume-chan/adb`), pushes the
   scrcpy server to it and starts it (github.com/Genymobile/scrcpy), and
   decodes its H.264 with WebCodecs into a video track of the page. The
   panel of the recorder (`rragent.client.recorder`) shows the track and
   records it like the laptop's screen.

   The phone needs USB debugging on (Developer options) and allows this
   browser once; the key it allows lives in the browser (IndexedDB). An
   `adb` server running on the laptop holds the phone, which Chrome then
   cannot claim. The scrcpy server is served by the machine
   (`/scrcpy/`, its version is the one the packages speak); no sound and
   no control of the phone, only its picture."
  (:require ["@yume-chan/adb" :refer [Adb AdbDaemonTransport]]
            ["@yume-chan/adb-credential-web" :default AdbWebCredentialStore]
            ["@yume-chan/adb-daemon-webusb" :refer [AdbDaemonWebUsbDevice AdbDaemonWebUsbDeviceManager]]
            ["@yume-chan/adb-scrcpy" :refer [AdbScrcpyClient AdbScrcpyOptionsLatest]]
            ["@yume-chan/scrcpy" :refer [DefaultServerPath]]
            ["@yume-chan/scrcpy-decoder-webcodecs" :refer [InsertableStreamVideoFrameRenderer WebCodecsVideoDecoder]]))

(def server-url
  "The scrcpy server, as the machine serves it from `public/scrcpy/`."
  "/scrcpy/scrcpy-server-v3.3.3")

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
  "Whether this browser can show a phone's screen: WebUSB, WebCodecs and
   a track made of frames (Chrome on a desktop)."
  []
  (boolean (and (some? (.-BROWSER AdbDaemonWebUsbDeviceManager))
                (.-isSupported WebCodecsVideoDecoder)
                (.-isSupported InsertableStreamVideoFrameRenderer))))

(defn- busy?
  [error]
  (instance? (.-DeviceBusyError AdbDaemonWebUsbDevice) error))

(defn- ^:async adb!
  "The phone Chrome's picker gives, connected and allowed; nil when the
   person closed the picker."
  []
  (when-let [^js device (await (.requestDevice (.-BROWSER AdbDaemonWebUsbDeviceManager)))]
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
       :name (.-name device)})))

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

(defn ^:async connect!
  "The phone's screen: Chrome's USB picker from the click, the phone
   allowed, scrcpy started. A map with the phone's `:name`, the `:track`
   of its picture, the `:element` that shows it, `:exited`, a promise
   that settles when the phone is gone, and `:close`; nil when the
   person closed the picker. A phone another program holds throws with
   `{:reason :busy}`."
  []
  (when-let [{:keys [^js adb name]} (await (adb!))]
    (try
      (await (push-server! adb))
      (let [^js client (await (.start AdbScrcpyClient adb DefaultServerPath
                                       (AdbScrcpyOptionsLatest. (clj->js video-options))))
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
         :track track
         :element element
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
