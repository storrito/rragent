(ns rragent.render
  "A video from an artifact project: the `render` tool
   (`rragent.tools.render`) or a click in the pane starts it, its
   progress is in the db while it runs, the file it writes shows up as
   an artifact by itself (`rragent.artifacts`), Stop ends the process
   and removes the half-written file. A HyperFrames project
   (`composition/index.html`) renders through the HyperFrames CLI,
   `hyperframes` on the PATH or RRAGENT_HYPERFRAMES, with the Chrome of
   the machine (CHROME, or the usual names on the PATH), the values of
   its `variables.json` passed along; the progress lines the CLI prints
   (`@hf-progress {...}`) are the progress. The port of pchat.render
   and bin/render: the render is a child process of the agent's own,
   no babashka. The motion design comes with the next milestone.

   The key this namespace owns in the db (`rragent.db`):

       :renders {name {:started ms :progress \"30 %\" :exit n :error \"...\"}}"
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:readline" :as readline]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]))

(def chrome-names
  ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "chrome"])

(defn on-path
  "The executable `name` on the PATH, or nil."
  [name]
  (some (fn [dir]
          (let [file (path/join dir name)]
            (when (try (.isFile (fs/statSync file)) (catch :default _ false))
              file)))
        (str/split (str (aget (.-env js/process) "PATH")) #":")))

(defn chrome
  "`CHROME` in the environment, else the first of the usual names on the
   PATH, else nil."
  []
  (or (not-empty (aget (.-env js/process) "CHROME"))
      (some on-path chrome-names)))

(defn hyperframes
  "The HyperFrames CLI: RRAGENT_HYPERFRAMES, else `hyperframes` on the
   PATH, else nil."
  []
  (or (not-empty (aget (.-env js/process) "RRAGENT_HYPERFRAMES"))
      (on-path "hyperframes")))

(defn missing-tools
  "The names of the video tools that are not there."
  []
  (cond-> []
    (nil? (on-path "ffmpeg")) (conj "ffmpeg")
    (nil? (chrome)) (conj "google-chrome")
    (nil? (hyperframes)) (conj "hyperframes")))

(defn not-ready-message
  "Why a render cannot start, or nil when the video tools are there:
   the words for the person, no stack trace."
  [missing]
  (when (seq missing)
    (str "The video tools are not installed on this machine (" (str/join ", " missing)
         "), so no video: the operator installs them.")))

(defn report
  "One line on what is there and what is missing, for the start."
  []
  (let [found (fn [name file] (str name (if file (str " at " file) " missing")))]
    (str "videos: " (found "Chrome" (chrome)) ", " (found "ffmpeg" (on-path "ffmpeg")) ", " (found "hyperframes" (hyperframes)))))

(defn node-modules
  "The node_modules of the HyperFrames install the CLI belongs to, where
   the player and the runtime of a composition's page lie; nil without
   the CLI."
  []
  (when-let [cli (hyperframes)]
    (let [real (try (fs/realpathSync cli) (catch :default _ cli))]
      (loop [dir (path/dirname real)]
        (cond
          (= "node_modules" (path/basename dir)) dir
          (= dir (path/dirname dir)) nil
          :else (recur (path/dirname dir)))))))

(defn environment
  "What the HyperFrames CLI and a project's process get: the Chrome of
   the machine, no telemetry, no browser download."
  []
  (let [chrome (chrome)]
    (cond-> {"HYPERFRAMES_NO_TELEMETRY" "1"
             "HYPERFRAMES_SKIP_SKILLS" "1"
             "PUPPETEER_SKIP_DOWNLOAD" "1"}
      chrome (assoc "CHROME" chrome
                    "HYPERFRAMES_BROWSER_PATH" chrome
                    "PRODUCER_HEADLESS_SHELL_PATH" chrome)
      (node-modules) (assoc "HYPERFRAMES_NODE_MODULES" (node-modules)))))

(defn handle
  "`{:db the db, :dir the artifacts folder}`; `:procs`, the table of the
   running renders by name, is shared by every handle of the process
   unless given, so that the tool starts what the pane stops."
  [{:keys [dir] :as opts}]
  (assoc opts :dir (path/resolve dir)))

(defonce procs
  (atom {}))

(defn- table
  [{:keys [procs]}]
  (or procs rragent.render/procs))

(defn running?
  [handle name]
  (contains? @(table handle) name))

(defn any-running?
  "Whether a render is on its way."
  [handle]
  (boolean (seq @(table handle))))

(defn output-file
  "Where the video of the project goes: beside the project, with its name."
  [{:keys [dir]} name]
  (path/join dir (str name ".mp4")))

(defn kind-of
  "What the project is: `:hyperframes` with a composition, nil otherwise."
  [project-dir]
  (when (fs/existsSync (path/join project-dir "composition" "index.html"))
    :hyperframes))

(defn extra-args
  "RRAGENT_RENDER_ARGS, more arguments for the CLI, `--browser-gpu` on a
   machine with a graphics card for example."
  []
  (some-> (aget (.-env js/process) "RRAGENT_RENDER_ARGS") str/trim not-empty (str/split #"\s+")))

(defn command
  "The command that renders the project: `{:cmd :args :cwd}`, nil for a
   project that is no HyperFrames composition."
  [{:keys [dir] :as handle} name]
  (let [project-dir (path/join dir name)
        variables (path/join project-dir "variables.json")]
    (when (= :hyperframes (kind-of project-dir))
      {:cmd (hyperframes)
       :args (-> ["render" "." "--output" (output-file handle name)]
                 (into (extra-args))
                 (cond-> (fs/existsSync variables) (into ["--variables-file" variables])))
       :cwd (path/join project-dir "composition")})))

(defn progress-of
  "The progress a line of the CLI reports, `@hf-progress {\"pct\":30 ...}`,
   as `\"30 %\"`; nil for any other line."
  [line]
  (when-let [[_ json] (re-find #"^@hf-progress\s+(\{.*\})\s*$" (str line))]
    (try
      (let [pct (aget (js/JSON.parse json) "pct")]
        (when (number? pct)
          (str (js/Math.round pct) " %")))
      (catch :default _
        nil))))

(defn- kill!
  [^js process]
  (when-let [pid (.-pid process)]
    (try
      (js/process.kill (- pid) "SIGTERM")
      (catch :default _
        (try (.kill process "SIGTERM") (catch :default _ nil))))))

(defn- finish!
  "The exit of the process into the db: an error with the last line of
   the output, nothing of it after a stop; the finished video is news
   for the pane."
  [{:keys [db] :as handle} name exit lines]
  (let [stopped? (get-in @(table handle) [name :stopped?])]
    (swap! (table handle) dissoc name)
    (swap! db update :renders
           (fn [renders]
             (if stopped?
               (dissoc renders name)
               (update renders name
                       (fn [render]
                         (cond-> (-> render (dissoc :progress) (assoc :exit exit))
                           (not (zero? exit))
                           (assoc :error (or (last (remove progress-of lines))
                                             (str "exit " exit)))))))))
    (artifacts/refollow! db)
    (get-in @db [:renders name])))

(defn- refuse!
  "No process: the render is over before it started, with the message
   as its error, where the person reads it."
  [{:keys [db]} name message]
  (let [render {:started (js/Date.now) :exit 1 :error message}]
    (swap! db assoc-in [:renders name] render)
    render))

(defn- lines!
  "Every line of the stream to `on-line`."
  [stream on-line]
  (.on (readline/createInterface #js {:input stream}) "line"
       (fn [line]
         (when-not (str/blank? line)
           (on-line line)))))

(defn start!
  "The render of the project `name` started, unless it runs already or
   the video tools are not there (then the reason is the render's
   error, see `not-ready-message`). `on-line` gets every line the
   render prints. Answers a promise of the render's state once it ended."
  ([handle name]
   (start! handle name nil))
  ([{:keys [db dir] :as handle} name on-line]
   (cond
     (running? handle name)
     (js/Promise. (fn [resolve]
                    (swap! (table handle) update-in [name :waiting] (fnil conj []) resolve)))

     (not (kind-of (path/join dir name)))
     (js/Promise.resolve (refuse! handle name (str name " is no project that can be rendered: a HyperFrames composition has composition/index.html.")))

     :else
     (if-let [message (not-ready-message (missing-tools))]
       (js/Promise.resolve (refuse! handle name message))
       (let [{:keys [cmd args cwd]} (command handle name)
             process (child/spawn cmd (clj->js args)
                                  #js {:cwd cwd
                                       :env (js/Object.assign #js {} (.-env js/process) (clj->js (environment)))
                                       :stdio #js ["ignore" "pipe" "pipe"]
                                       :detached true})
             lines (atom [])
             note! (fn [line]
                     (swap! lines #(vec (take-last 20 (conj % line))))
                     (when-let [progress (progress-of line)]
                       (swap! db assoc-in [:renders name :progress] progress))
                     (when on-line
                       (on-line line)))]
         (swap! (table handle) assoc name {:process process :waiting []})
         (swap! db assoc-in [:renders name] {:started (js/Date.now)})
         (lines! (.-stdout process) note!)
         (lines! (.-stderr process) note!)
         (js/Promise. (fn [resolve]
                        (swap! (table handle) update-in [name :waiting] conj resolve)
                        (let [settle! (fn [exit]
                                        ;; once: `close` follows `error` for a process that could not start
                                        (when (running? handle name)
                                          (let [waiting (get-in @(table handle) [name :waiting])
                                                render (finish! handle name exit @lines)]
                                            (doseq [f waiting]
                                              (f render)))))]
                          (.on process "error" (fn [error]
                                                 (note! (str error))
                                                 (settle! 1)))
                          (.on process "close" (fn [code signal]
                                                 (settle! (or code (if signal 1 0)))))))))))))

(defn stop!
  "Ends a running render; the file it was writing goes with it."
  [{:keys [db] :as handle} name]
  (when-let [{:keys [process]} (get @(table handle) name)]
    (swap! (table handle) assoc-in [name :stopped?] true)
    (kill! process)
    (let [started (get-in @db [:renders name :started] 0)
          file (output-file handle name)]
      ;; the encoder wrote into it since the start: a torso
      (when (and (fs/existsSync file) (<= started (.-mtimeMs (fs/statSync file))))
        (fs/rmSync file #js {:force true})))
    (swap! db update :renders dissoc name)
    name))

(defn stop-all!
  [handle]
  (doseq [name (keys @(table handle))]
    (stop! handle name)))
