(ns rragent.motion.app
  "The process of a motion project, `rragent motion <dir>`: the design
   of `design.cljs` (`rragent.motion.design`) played on a page of
   rframes (`rragent.motion.player`) that rragent proxies into the pane,
   the data of `design.edn` kept across restarts, both files watched so
   that a saved change shows at once, the media files of the folder
   served to the frames. `rragent motion-render <dir> <out>` is the
   video of the same design (`rragent.motion.render`), `frame n/m` on
   its output as the progress. The port of pchat's motion template,
   whose babashka app held all of this; here the project folder holds
   the design alone and rragent runs it."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.assets :as assets]
            [rragent.motion.design :as design]
            [rragent.motion.player :as player]
            [rragent.motion.render :as render]
            [rragent.rframes.command :as command]
            [rragent.rframes.http :as http]
            [rragent.rframes.replicant :as replicant]
            [rragent.rframes.sse :as sse]))

(def design-file
  "design.cljs")

(def data-file
  "design.edn")

(def poll-ms
  "How often the design file is looked at for a change."
  500)

(defn state
  "The state of a project in `dir`: the player's atom and the loaded
   design."
  [dir]
  {:dir (path/resolve dir)
   :db (atom {:design {} :motion/t 0 :motion/playing? false})
   :loaded (atom {:error "not loaded yet"})})

(defn load!
  "The design file evaluated into the state; the page follows."
  [{:keys [dir loaded]}]
  (reset! loaded (design/load (path/join dir design-file)))
  (sse/refresh-all!)
  @loaded)

(defn- safe
  "The hiccup of `f`, or its error as a paragraph."
  [f & args]
  (try
    (player/data-only (apply f args))
    (catch :default e
      [:p.error (str "The controls failed: " (or (.-message e) (str e)))])))

(defn page-view
  "The player over the design at the current time, the controls under
   it, the error of the design file when it has one."
  [{:keys [db loaded]} _w]
  (let [{:keys [error size duration design-view controls]} @loaded
        {:keys [design] :as value} @db]
    [:div.artifact
     (when error
       [:p.error (str "design.cljs: " error)])
     (when design-view
       (player/player value {:frame design-view
                             :duration duration
                             :size size}))
     (when controls
       (safe controls design))]))

(def styles
  "The rules of the page; the design's `styles` follow them."
  (str "body { margin: 0; padding: 16px; font: 15px/1.5 system-ui, sans-serif; color: #111; background: #fff; }"
       ".artifact { display: flex; flex-direction: column; gap: 16px; }"
       ".controls { display: flex; flex-wrap: wrap; gap: 12px 20px; }"
       ".controls label { display: flex; align-items: center; gap: 8px; }"
       player/css))

(defn head
  "The head of the page: the styles, the player's script, and the page's
   scripts of rragent, whose origin this page lives under."
  [{:keys [loaded]}]
  (let [{:keys [scripts preload]} (assets/client)]
    (str "<title>artifact</title>"
         "<style>" styles (:styles @loaded) "</style>"
         "<script>" player/script "</script>"
         (apply str (for [href preload]
                      (str "<link rel=\"modulepreload\" href=\"" href "\">")))
         (apply str (for [src scripts]
                      (str "<script type=\"module\" src=\"" src "\"></script>"))))))

(def media
  "What a frame may refer to by its name: pictures, sounds, videos, fonts."
  #{"png" "jpg" "jpeg" "gif" "webp" "svg" "avif" "mp3" "wav" "m4a" "ogg" "aac"
    "mp4" "webm" "mov" "woff" "woff2" "ttf" "otf" "css"})

(defn- file-handler
  "A media file of the folder a frame refers to by its name; nothing
   else, nothing hidden."
  [{:keys [dir]} w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        extension (str/lower-case (or (second (re-find #"\.([^./]+)$" subpath)) ""))
        file (when (and (contains? media extension)
                        (not (re-find #"(^|/)\." subpath)))
               (http/resolve-file dir subpath))]
    (assoc w :ring/response (if file
                              (http/file-response (:ring/request w) file)
                              http/not-found))))

(defn register
  [{:keys [db loaded] :as state}]
  (replicant/expand
   (concat [{:replicant/shim "/"
             :head (head state)}
            {:replicant/render "/"
             :render/fn (fn [w] (page-view state w))}]
           (player/commands db {:duration (fn [] (or (:duration @loaded) 0))})
           command/register
           ;; last: the files of the folder
           [{:ring/route [:get "/*"]
             :ring/handler (fn [w] (file-handler state w))}])))

(defn- watch-file!
  "`f` called when the file's time changes, looked at every `poll-ms`."
  [file f]
  (let [mtime (fn [] (try (.-mtimeMs (fs/statSync file)) (catch :default _ nil)))
        seen (atom (mtime))
        timer (js/setInterval (fn []
                                (let [now (mtime)]
                                  (when (not= @seen now)
                                    (reset! seen now)
                                    (f))))
                              poll-ms)]
    (.unref timer)
    (fn [] (js/clearInterval timer))))

(defn- exit-with-parent!
  "rragent started this process; when rragent is gone, so is this."
  []
  (when-let [pid (some-> (aget (.-env js/process) "RRAGENT_PID") js/parseInt (as-> n (when-not (js/isNaN n) n)))]
    (.unref (js/setInterval (fn []
                              (try
                                (js/process.kill pid 0)
                                (catch :default _
                                  (js/process.exit 0))))
                            2000))))

(defn ^:async serve!
  "The project of `dir` served on `port` (0 takes a free one) on the
   loopback: `{:server :state :stop}`."
  [dir port]
  (let [{:keys [db dir] :as state} (state dir)]
    (load! state)
    (sse/watch! db)
    (let [stop-data (player/watch-design! db (path/join dir data-file))
          stop-design (watch-file! (path/join dir design-file) (fn [] (load! state)))
          server (await (http/listen (http/ring-handler (fn [] (register state))) port "127.0.0.1"))]
      {:server server
       :state state
       :stop (fn []
               (stop-data)
               (stop-design)
               (.closeAllConnections server)
               (.close server))})))

(defn ^:async serve-main
  "`rragent motion <dir>`: PORT from the environment, a line on the port."
  [dir]
  (when (str/blank? dir)
    (throw (js/Error. "rragent motion <dir>: the folder of the design")))
  (exit-with-parent!)
  (let [{:keys [server]} (await (serve! dir (js/parseInt (or (not-empty (aget (.-env js/process) "PORT")) "0") 10)))]
    (println (str "artifact on http://127.0.0.1:" (.-port (.address server))))))

(defn ^:async render-file!
  "The design of `dir` as the video `out`: `{:out :frames :seconds}`."
  [dir out on-progress]
  (let [dir (path/resolve dir)
        {:keys [error size duration design-view styles]} (design/load (path/join dir design-file))
        design (or (player/read-design (path/join dir data-file)) {})]
    (when error
      (throw (js/Error. (str "design.cljs: " error))))
    (await (render/render! {:frame design-view
                            :design design
                            :duration duration
                            :fps 30
                            :size size
                            :head (str "<style>" rragent.motion.app/styles styles "</style>")
                            :dir dir
                            :audio (:audio design)
                            :out out
                            :on-progress on-progress}))))

(defn ^:async render-main
  "`rragent motion-render <dir> <out>`: `frame n/m` every second of
   video and at the end, then the file and the time it took."
  [dir out]
  (when (or (str/blank? dir) (str/blank? out))
    (throw (js/Error. "rragent motion-render <dir> <out.mp4>")))
  (let [{:keys [frames seconds]} (await (render-file! dir out (fn [done all]
                                                                 (when (or (= done all) (zero? (mod done 30)))
                                                                   (println (str "frame " done "/" all))))))]
    (println (str out ": " frames " frames in " (js/Math.round seconds) " s"))))
