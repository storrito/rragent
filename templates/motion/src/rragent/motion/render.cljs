(ns rragent.motion.render
  "A motion design as a video file: every frame of the design as HTML, a
   headless Chrome shows them one after the other at the size of the
   video and screenshots each, ffmpeg encodes the screenshots and mixes
   the audio tracks of the design in. fnmotion.render ported to Node:
   the same page, the same DevTools calls, the same ffmpeg command; the
   socket is Node's own WebSocket, the frames are rendered to HTML by
   Replicant's string renderer, and a few Chromes work side by side
   (`:workers`), each on every nth frame, the screenshots written to
   ffmpeg in order as they come. Nothing but the video reaches the disk.

       (render! {:frame design-view :design design :duration 6 :fps 30
                 :size [1080 1920] :head styles :dir \".\"
                 :audio [{:src \"music.mp3\" :volume 0.6 :fade-out 1}]
                 :out \"../hello.mp4\"})

   The page is a file in `:dir`, so a relative `src` in a frame finds
   the file it finds on the page of the design; `:head` is what the
   head of that page has, the styles and the fonts. An audio track is a
   map: `:src` the file, relative to `:dir`; `:start` when it begins in
   the video, seconds, 0; `:duration` how long it plays, to the end of
   the video; `:media-start` where in the file it begins, 0; `:volume`
   0..1, 1; `:fade-in` and `:fade-out` in seconds, 0; `:loop?` whether
   the file starts over when it ends, false. The tracks are mixed; the
   video sets the length."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [replicant.string :as rs]
            [rragent.programs :as programs]))

;;; The frames

(defn frame-times
  "The times of the frames of a video of `duration` seconds at `fps`."
  [duration fps]
  (let [n (js/Math.round (* duration fps))]
    (mapv #(/ % fps) (range n))))

(defn- replicant-key?
  [k]
  (or (= :on k)
      (and (keyword? k) (= "replicant" (namespace k)))))

(defn plain
  "The hiccup without what only Replicant understands: `:on` and
   `:replicant/*`, which the string renderer would print as attributes."
  [hiccup]
  (walk/postwalk (fn [x]
                   (if (and (vector? x) (keyword? (first x)) (map? (second x)))
                     (update x 1 #(into {} (remove (comp replicant-key? key)) %))
                     x))
                 hiccup))

(defn html
  "The hiccup as an HTML string."
  [hiccup]
  (str (rs/render (plain hiccup))))

(def ^:private show-script
  "The function on the page that puts a frame into the stage and settles
   when its fonts and images are there, laid out. The screenshot itself
   draws the frame; waiting for an animation frame here would hang in a
   headless Chrome that thinks its page is hidden."
  (str "window.__fnmotion = {show: function (html) {"
       " var stage = document.getElementById('stage');"
       " stage.innerHTML = html;"
       " var images = Array.prototype.map.call(stage.querySelectorAll('img'), function (img) { return img.decode().catch(function () {}); });"
       " return Promise.all([document.fonts.ready].concat(images)).then(function () {"
       "  void stage.offsetHeight;"
       "  return true;"
       " });"
       "}};"))

(defn page-html
  "The page Chrome shows: the head of the design's page, a stage of the
   size of the video, the function that puts a frame into it."
  [{:keys [size head background]}]
  (let [[w h] size]
    (str "<!doctype html><html><head><meta charset=\"utf-8\">"
         head
         "<style>html, body { margin: 0; padding: 0; overflow: hidden; background: " (or background "#fff") "; }"
         " #stage { position: relative; width: " w "px; height: " h "px; overflow: hidden; }"
         " #stage * { animation: none !important; transition: none !important; }</style>"
         "</head><body><div id=\"stage\" class=\"motion-stage\"></div>"
         "<script>" show-script "</script></body></html>")))

;;; Chrome

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defonce ^:private chromes
  (atom #{}))

(defn- kill-group!
  "The signal to the whole process group of the child, Chrome and what
   it spawned; the child alone when it has no group. Not named
   `process`: that would shadow Node's `process` here."
  [^js child signal]
  (when-let [pid (.-pid child)]
    (try
      (.kill js/process (- pid) signal)
      (catch :default _
        (try (.kill child signal) (catch :default _ nil))))))

(defn- stop-every-chrome!
  "A render that is ended from outside (the person stops it, the process
   gets a signal) leaves no Chrome and no profile behind."
  []
  (doseq [{:keys [process profile]} @chromes]
    (kill-group! process "SIGKILL")
    (try (fs/rmSync profile #js {:recursive true :force true}) (catch :default _ nil)))
  (reset! chromes #{}))

#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private exit-hook
  (do (.on js/process "exit" stop-every-chrome!)
      ;; the signals end the process through `exit`, which the hook sees
      (doseq [signal ["SIGTERM" "SIGINT" "SIGHUP"]]
        (.on js/process signal (fn [] (js/process.exit 143))))
      true))

(defn- ^:async devtools-port
  "The port Chrome wrote into its profile, within twenty seconds."
  [profile exited]
  (let [file (path/join profile "DevToolsActivePort")]
    (loop [waited 0]
      (let [lines (when (fs/existsSync file)
                    (str/split-lines (fs/readFileSync file "utf8")))]
        (cond
          (= 2 (count lines))
          (js/parseInt (first lines) 10)

          @exited
          (throw (js/Error. (str "Chrome exited: " (str/trim (try (fs/readFileSync (path/join profile "chrome.log") "utf8") (catch :default _ ""))))))

          (< 20000 waited)
          (throw (js/Error. "Chrome did not open its DevTools port in 20 s"))

          :else
          (do (await (sleep 100))
              (recur (+ waited 100))))))))

(defn ^:async start-chrome!
  "A headless Chrome of its own, with a window no smaller than a desktop
   one: Chrome takes a tiny window for hidden and paints no frame in it,
   and a screenshot waits for a frame. The page itself gets the size of
   the video from `prepare-page!`. `{:process :profile :port}`."
  [chrome [w h]]
  (let [[w h] [(max w 1024) (max h 768)]
        profile (fs/mkdtempSync (path/join (os/tmpdir) "fnmotion-chrome"))
        log (fs/openSync (path/join profile "chrome.log") "a")
        exited (atom false)
        process (child/spawn chrome
                             #js ["--headless=new" "--no-sandbox" "--disable-gpu" "--disable-dev-shm-usage"
                                  "--hide-scrollbars" "--no-first-run" "--disable-extensions" "--mute-audio"
                                  "--disable-background-networking" "--force-device-scale-factor=1"
                                  "--force-color-profile=srgb"
                                  ;; every page stays awake, as with Puppeteer
                                  "--disable-renderer-backgrounding"
                                  "--disable-backgrounding-occluded-windows"
                                  "--disable-background-timer-throttling"
                                  "--disable-hang-monitor"
                                  (str "--window-size=" w "," h)
                                  "--remote-debugging-port=0"
                                  (str "--user-data-dir=" profile)
                                  "about:blank"]
                             #js {:stdio #js ["ignore" log log] :detached true})]
    (fs/closeSync log)
    (.on process "exit" (fn [] (reset! exited true)))
    (.on process "error" (fn [] (reset! exited true)))
    (let [entry {:process process :profile profile}]
      (swap! chromes conj entry)
      (try
        (assoc entry :port (await (devtools-port profile exited)))
        (catch :default e
          (swap! chromes disj entry)
          (kill-group! process "SIGKILL")
          (throw e))))))

(defn ^:async stop-chrome!
  "Ends Chrome and removes its profile once it is gone."
  [{:keys [process profile] :as entry}]
  (swap! chromes disj entry)
  (let [gone (js/Promise. (fn [resolve]
                            (if (some? (.-exitCode process))
                              (resolve true)
                              (.once process "exit" (fn [] (resolve true))))))]
    (kill-group! process "SIGTERM")
    (when-not (await (js/Promise.race #js [gone (.then (sleep 10000) (fn [] false))]))
      (kill-group! process "SIGKILL")
      (await (js/Promise.race #js [gone (sleep 5000)])))
    (try
      (fs/rmSync profile #js {:recursive true :force true})
      (catch :default _
        ;; Chrome wrote into it after it said goodbye; the next look wins
        (await (sleep 500))
        (fs/rmSync profile #js {:recursive true :force true})))))

(defn ^:async page-url
  "The DevTools socket of the page Chrome opened."
  [port]
  (let [targets (await (.json (await (js/fetch (str "http://127.0.0.1:" port "/json/list")))))]
    (or (some #(when (= "page" (.-type %)) (.-webSocketDebuggerUrl %)) (array-seq targets))
        (throw (js/Error. "Chrome opened no page")))))

;;; The DevTools protocol

(defn ^:async connect!
  "A WebSocket to a page's DevTools socket: `{:socket :pending :ids}`."
  [ws-url]
  (let [pending (atom {})
        socket (js/WebSocket. ws-url)]
    (.addEventListener socket "message"
                       (fn [^js event]
                         (let [message (js/JSON.parse (.-data event))]
                           (when-let [answer (get @pending (.-id message))]
                             (answer message)))))
    (.addEventListener socket "error"
                       (fn [^js event]
                         (doseq [answer (vals @pending)]
                           (answer #js {:error #js {:message (str "socket error " (.-message event))}}))))
    (await (js/Promise. (fn [resolve reject]
                          (.addEventListener socket "open" resolve)
                          (.addEventListener socket "error" reject))))
    {:socket socket
     :pending pending
     :ids (atom 0)}))

(defn ^:async call!
  "One method of the protocol: its result, or a throw with the error it
   answered. The timer of a call is cleared with its answer; left to
   run, it would keep the process alive for a minute after the render."
  [{:keys [socket pending ids]} method params]
  (let [id (swap! ids inc)
        message (await (js/Promise. (fn [resolve]
                                      (let [timer (js/setTimeout (fn [] (resolve nil)) 60000)]
                                        (swap! pending assoc id (fn [answer]
                                                                  (js/clearTimeout timer)
                                                                  (resolve answer)))
                                        (.send socket (js/JSON.stringify #js {:id id :method method :params (clj->js (or params {}))}))))))]
    (swap! pending dissoc id)
    (cond
      (nil? message) (throw (js/Error. (str method " did not answer in 60 s")))
      (.-error message) (throw (js/Error. (str method ": " (.. message -error -message))))
      :else (.-result message))))

(defn ^:async evaluate!
  [cdp expression]
  (let [^js answer (await (call! cdp "Runtime.evaluate" {:expression expression
                                                          :awaitPromise true
                                                          :returnByValue true}))]
    (when-let [details (.-exceptionDetails answer)]
      (throw (js/Error. (str "The page failed: " (or (some-> details .-exception .-description)
                                                      (.-text details))))))
    (some-> answer .-result .-value)))

(defn- ^:async wait-ready!
  [cdp]
  (loop [waited 0]
    (when-not (true? (await (evaluate! cdp "document.readyState === 'complete' && !!window.__fnmotion")))
      (when (< 30000 waited)
        (throw (js/Error. "The render page did not load in 30 s")))
      (await (sleep 50))
      (recur (+ waited 50)))))

(defn ^:async screenshot!
  "The viewport as PNG bytes."
  [cdp]
  (js/Buffer.from (.-data (await (call! cdp "Page.captureScreenshot" {:format "png"}))) "base64"))

(defn- ^:async prepare-page!
  "A page of the size of the video, with the render page loaded."
  [cdp [w h] page]
  (await (call! cdp "Emulation.setDeviceMetricsOverride" {:width w :height h :deviceScaleFactor 1 :mobile false}))
  (await (call! cdp "Page.navigate" {:url (url/pathToFileURL page)}))
  (await (call! cdp "Page.bringToFront" {}))
  (await (wait-ready! cdp))
  cdp)

;;; ffmpeg

(defn- num
  "A number in a filter: 1.5 or 2, never 1,5."
  [x]
  (let [v (/ (js/Math.round (* 1000 x)) 1000)]
    (if (== v (js/Math.floor v))
      (str (js/Math.trunc v))
      (str v))))

(defn track-filter
  "The filter of one audio track, the input `i`: the part of the file that
   plays, at its volume, faded, delayed to its start; out as [a<i>]."
  [i {:keys [start duration media-start volume fade-in fade-out]} video-duration]
  (let [start (or start 0)
        plays (max 0 (min (or duration (- video-duration start))
                          (- video-duration start)))
        volume (or volume 1)
        fade-in (or fade-in 0)
        fade-out (or fade-out 0)
        steps (cond-> [(str "atrim=start=" (num (or media-start 0)) ":duration=" (num plays))
                       "asetpts=PTS-STARTPTS"]
                (not= 1 volume) (conj (str "volume=" (num volume)))
                (pos? fade-in) (conj (str "afade=t=in:st=0:d=" (num fade-in)))
                (pos? fade-out) (conj (str "afade=t=out:st=" (num (max 0 (- plays fade-out))) ":d=" (num fade-out)))
                (pos? start) (conj (str "adelay=" (js/Math.round (* 1000 start)) ":all=1")))]
    (str "[" i ":a]" (str/join "," steps) "[a" i "]")))

(defn audio-filter
  "The filter graph that mixes the tracks, the inputs 1..n, into [aout]."
  [tracks video-duration]
  (let [n (count tracks)]
    (str (str/join ";" (map-indexed (fn [k track] (track-filter (inc k) track video-duration)) tracks))
         ";"
         (apply str (map #(str "[a" (inc %) "]") (range n)))
         (if (= 1 n)
           "anull"
           (str "amix=inputs=" n ":duration=longest:normalize=0"))
         "[aout]")))

(defn ffmpeg-args
  "The command: PNG frames on stdin at `fps`, the audio tracks as inputs,
   H.264 and AAC in an MP4 of `duration` seconds."
  [{:keys [ffmpeg fps duration audio out]}]
  (-> [(or ffmpeg "ffmpeg") "-y" "-loglevel" "error" "-f" "image2pipe" "-framerate" (str fps) "-i" "-"]
      (into (mapcat (fn [{:keys [src loop?]}]
                      (cond-> []
                        loop? (into ["-stream_loop" "-1"])
                        true (into ["-i" (str src)])))
                    audio))
      (into (when (seq audio)
              ["-filter_complex" (audio-filter audio duration)
               "-map" "0:v" "-map" "[aout]" "-c:a" "aac" "-b:a" "192k" "-ar" "48000"]))
      (into ["-c:v" "libx264" "-pix_fmt" "yuv420p" "-crf" "18" "-preset" "medium"
             "-t" (num duration) "-movflags" "+faststart" (str out)])))

(defn- start-encoder!
  "ffmpeg with the frames on its stdin: `{:process :stdin :done}`, `done`
   a promise of `{:exit :err}`."
  [args dir]
  (let [[cmd & rest] args
        process (child/spawn cmd (clj->js (vec rest)) #js {:cwd dir :stdio #js ["pipe" "ignore" "pipe"]})
        err (atom "")]
    (.on (.-stderr process) "data" (fn [chunk] (swap! err str chunk)))
    {:process process
     :stdin (.-stdin process)
     :done (js/Promise. (fn [resolve]
                          (.on process "error" (fn [error] (resolve {:exit 1 :err (str error)})))
                          (.on process "close" (fn [code] (resolve {:exit (or code 1) :err @err})))))}))

(defn- write!
  "A buffer to a writable, waiting when it is full."
  [^js stream buffer]
  (js/Promise. (fn [resolve]
                 (if (.write stream buffer)
                   (resolve true)
                   (.once stream "drain" (fn [] (resolve true)))))))

;;; The render

(defn default-workers
  "Half the processors, one to four: a Chrome per worker."
  []
  (max 1 (min 4 (quot (count (os/cpus)) 2))))

(defn- slot
  "A promise and its settlers; a rejection no one awaits is not a crash."
  []
  (let [settlers (atom nil)
        promise (js/Promise. (fn [resolve reject] (reset! settlers [resolve reject])))]
    (.catch promise (fn [_] nil))
    {:promise promise
     :resolve (first @settlers)
     :reject (second @settlers)}))

(defn- ^:async work!
  "One worker: its frames, every `workers`th from `first-index`, shown and
   screenshot into their slots. It stays close behind the writer so that
   no more than a few screenshots wait in memory. An error goes into the
   slots it still owes, so that the writer sees it."
  [cdp {:keys [frame design times slots written workers]} first-index]
  (let [mine (range first-index (count times) workers)]
    (try
      (doseq [i mine]
        (while (> (- i @written) (* 2 workers))
          (await (sleep 5)))
        (await (evaluate! cdp (str "window.__fnmotion.show(" (js/JSON.stringify (html (frame design (nth times i)))) ")")))
        ((:resolve (nth slots i)) (await (screenshot! cdp))))
      (catch :default e
        (doseq [i mine]
          ((:reject (nth slots i)) e))))))

(defn- ^:async write-frames!
  "The screenshots into ffmpeg, in order, as they come."
  [stdin {:keys [slots written on-progress]}]
  (doseq [[i {:keys [promise]}] (map-indexed vector slots)]
    (let [png (await promise)]
      (await (write! stdin png))
      (reset! written (inc i))
      (when on-progress
        (on-progress (inc i) (count slots))))))

(defn ^:async render!
  "Renders the design to `:out`, a path relative to the working directory,
   and answers `{:out :frames :workers :seconds}`. The options are in the
   namespace; `:fps` is 30, `:dir` the working directory, `:chrome` is
   found when absent (`rragent.programs/locate`, CHROME in the
   environment or the usual names on the PATH), `:ffmpeg` the same way,
   `:workers` the Chromes that screenshot side by side
   (`default-workers`), `:on-progress` a function of the frames done and
   the frames in all."
  [{:keys [frame design duration fps size head background dir audio out chrome ffmpeg workers on-progress]
    :or {fps 30 dir "."}}]
  (let [chrome (or chrome
                   (programs/locate "chrome")
                   (throw (js/Error. "No Chrome: CHROME in the environment, or google-chrome or chromium on the PATH.")))
        ffmpeg (or ffmpeg (programs/locate "ffmpeg") "ffmpeg")
        dir (path/resolve dir)
        ;; H.264 wants even sizes
        size (mapv #(* 2 (quot (js/Math.trunc %) 2)) size)
        times (frame-times duration fps)
        workers (min (max 1 (or workers (default-workers))) (max 1 (count times)))
        page (path/join dir ".fnmotion-render.html")
        out (path/resolve out)
        started (js/Date.now)]
    (fs/writeFileSync page (page-html {:size size :head head :background background}))
    (let [chromes (await (js/Promise.all (clj->js (mapv (fn [_] (start-chrome! chrome size)) (range workers)))))]
      (try
        (let [cdps (await (js/Promise.all (clj->js (mapv (fn [{:keys [port]}]
                                                           (-> (page-url port)
                                                               (.then connect!)
                                                               (.then (fn [cdp] (prepare-page! cdp size page)))))
                                                         chromes))))
              encoder (start-encoder! (ffmpeg-args {:ffmpeg ffmpeg :fps fps :duration duration :audio audio :out out}) dir)]
          (try
            (let [job {:frame frame
                       :design design
                       :times times
                       :slots (vec (repeatedly (count times) slot))
                       :written (atom 0)
                       :workers workers
                       :on-progress on-progress}
                  running (mapv (fn [k cdp] (work! cdp job k)) (range) cdps)]
              (await (write-frames! (:stdin encoder) job))
              (.end (:stdin encoder))
              (await (js/Promise.all (clj->js running))))
            (let [{:keys [exit err]} (await (:done encoder))]
              (when-not (zero? exit)
                (throw (js/Error. (str "ffmpeg failed: " (str/trim (str err)))))))
            {:out out
             :frames (count times)
             :workers workers
             :seconds (/ (- (js/Date.now) started) 1000)}
            (catch :default e
              (try (fs/rmSync out #js {:force true}) (catch :default _ nil))
              (throw e))
            (finally
              (doseq [{:keys [socket]} cdps]
                (try (.close socket) (catch :default _ nil)))
              (when (nil? (.-exitCode (:process encoder)))
                (try (.kill (:process encoder) "SIGKILL") (catch :default _ nil))))))
        (finally
          (await (js/Promise.all (clj->js (mapv stop-chrome! chromes))))
          (try (fs/rmSync page #js {:force true}) (catch :default _ nil)))))))
