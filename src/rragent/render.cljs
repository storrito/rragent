(ns rragent.render
  "A render of an artifact project: the `render` tool
   (`rragent.tools.render`) or a click in the pane starts it, its
   progress is in the db while it runs, the file it writes shows up as
   an artifact by itself (`rragent.artifacts`), Stop ends the process
   and removes the half-written file. How the file is made is the
   project's template's business (`rragent.templates`): its `render`
   executable is run as `render <project-dir> <output>` in the
   project's folder, with the programs the template needs in its
   environment (`rragent.programs`), and writes the file,
   `artifacts/<name>.<ext>` with the first of the template's `renders`.
   A line `progress <done>/<total>` or `progress <n>%` on its output is
   the progress the pane shows, every other line is the render's
   output; its exit status says whether it worked, its stderr why not.
   A render that runs longer than the timeout is ended. The port of
   pchat.render and bin/render: the render is a child process of the
   agent's own.

   The key this namespace owns in the db (`rragent.db`):

       :renders {name {:started ms :progress \"30 %\" :exit n :error \"...\"}}"
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:readline" :as readline]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.programs :as programs]
            [rragent.templates :as templates]))

(def default-timeout-ms
  "A render that runs longer is ended: RRAGENT_RENDER_TIMEOUT_MIN
   minutes, thirty without."
  (* 60 1000 (or (some-> (aget (.-env js/process) "RRAGENT_RENDER_TIMEOUT_MIN") js/parseFloat (as-> n (when-not (js/isNaN n) n)))
                 30)))

(defn handle
  "`{:db the db, :dir the artifacts folder, :timeout-ms}`; `:procs`, the
   table of the running renders by name, is shared by every handle of
   the process unless given, so that the tool starts what the pane
   stops."
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

(defn template-of
  "The project's template, see `rragent.templates/of-project`."
  [{:keys [dir]} name]
  (templates/of-project (path/join dir name)))

(defn extension
  "What the template's render makes, the first of its `renders`; mp4
   without."
  [template]
  (or (first (:renders template)) "mp4"))

(defn output-file
  "Where the render of the project goes: beside the project, with its
   name and the template's extension."
  [{:keys [dir] :as handle} name]
  (path/join dir (str name "." (extension (template-of handle name)))))

(defn progress-of
  "The progress a line of a render reports, as `\"30 %\"`: `progress
   54/180` or `progress 30%`; nil for any other line."
  [line]
  (when-let [[_ a b] (re-find #"^progress\s+(\d+)\s*(?:/\s*(\d+)|%)\s*$" (str line))]
    (if b
      (str (js/Math.round (* 100 (/ (js/parseInt a 10) (max 1 (js/parseInt b 10))))) " %")
      (str a " %"))))

(defn refusal
  "Why the project cannot be rendered, or nil: no template, one that is
   not installed, one that renders nothing, a need missing."
  [{:keys [dir] :as handle} name]
  (let [template (template-of handle name)]
    (cond
      (not (artifacts/project? (path/join dir name)))
      (str name " is no project: a project is a folder under artifacts/ with a package.json.")

      (nil? template)
      (str name " was not made from a template, so nothing knows how to render it.")

      (:missing? template)
      (str "The template " (:name template) " of " name " is not installed.")

      (empty? (:renders template))
      (str "The template " (:name template) " renders nothing.")

      (not (fs/existsSync (templates/render-file template)))
      (str "The template " (:name template) " has no render script.")

      :else
      (programs/not-ready-message (:name template) (programs/missing (:needs template))))))

(defn- kill!
  "Ends the render and everything it started: SIGTERM to the process
   group the detached spawn made. The child is not named `process`
   here on purpose: that name would shadow Node's `process`, and the
   group kill would become the child's own `kill` with the negative
   pid as the signal, so that only the shell died and ffmpeg or Chrome
   kept the pipes open until they finished on their own."
  [^js child]
  (when-let [pid (.-pid child)]
    (try
      (.kill js/process (- pid) "SIGTERM")
      (catch :default _
        (try (.kill child "SIGTERM") (catch :default _ nil))))))

(defn- error-of
  "The words of a failed render: the end of its stderr, else the last
   line of its output, else the exit."
  [exit lines errors]
  (or (not-empty (str/join "\n" errors))
      (last (remove progress-of lines))
      (str "exit " exit)))

(defn- finish!
  "The exit of the process into the db: the error with the reason,
   nothing of it after a stop; the finished file is news for the pane."
  [{:keys [db] :as handle} name exit lines errors]
  (let [{:keys [stopped? timed-out?]} (get @(table handle) name)]
    (swap! (table handle) dissoc name)
    (swap! db update :renders
           (fn [renders]
             (if stopped?
               (dissoc renders name)
               (update renders name
                       (fn [render]
                         (cond-> (-> render (dissoc :progress) (assoc :exit exit))
                           timed-out?
                           (assoc :error (str "The render took longer than " (js/Math.round (/ (:timeout-ms timed-out?) 60000)) " minutes and was ended."))

                           (and (not timed-out?) (not (zero? exit)))
                           (assoc :error (error-of exit lines errors))))))))
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

(defn- spawn!
  "The template's render script on the project, its output followed:
   a promise of the render's state once it ended."
  [{:keys [db dir timeout-ms] :as handle} name template on-line]
  (let [project-dir (path/join dir name)
        out (output-file handle name)
        process (child/spawn (templates/render-file template) #js [project-dir out]
                             #js {:cwd project-dir
                                  :env (js/Object.assign #js {} (.-env js/process) (clj->js (programs/environment (:needs template))))
                                  :stdio #js ["ignore" "pipe" "pipe"]
                                  :detached true})
        lines (atom [])
        errors (atom [])
        timeout-ms (or timeout-ms default-timeout-ms)
        note! (fn [line]
                (swap! lines #(vec (take-last 20 (conj % line))))
                (when-let [progress (progress-of line)]
                  (swap! db assoc-in [:renders name :progress] progress))
                (when on-line
                  (on-line line)))
        timer (js/setTimeout (fn []
                               (when (running? handle name)
                                 (swap! (table handle) assoc-in [name :timed-out?] {:timeout-ms timeout-ms})
                                 (kill! process)))
                             timeout-ms)]
    (swap! (table handle) assoc name {:process process :waiting []})
    (swap! db assoc-in [:renders name] {:started (js/Date.now)})
    (lines! (.-stdout process) note!)
    (lines! (.-stderr process) (fn [line]
                                 (swap! errors #(vec (take-last 5 (conj % line))))
                                 (note! line)))
    (js/Promise. (fn [resolve]
                   (swap! (table handle) update-in [name :waiting] conj resolve)
                   (let [settle! (fn [exit]
                                   ;; once: `close` follows `error` for a process that could not start
                                   (when (running? handle name)
                                     (js/clearTimeout timer)
                                     (let [waiting (get-in @(table handle) [name :waiting])
                                           render (finish! handle name exit @lines @errors)]
                                       (doseq [f waiting]
                                         (f render)))))]
                     (.on process "error" (fn [error]
                                            (swap! errors conj (str error))
                                            (note! (str error))
                                            (settle! 1)))
                     (.on process "close" (fn [code signal]
                                            (settle! (or code (if signal 1 0))))))))))

(defn start!
  "The render of the project `name` started, unless it runs already or
   it cannot (then the reason is the render's error, see `refusal`).
   `on-line` gets every line the render prints. Answers a promise of
   the render's state once it ended."
  ([handle name]
   (start! handle name nil))
  ([handle name on-line]
   (cond
     (running? handle name)
     (js/Promise. (fn [resolve]
                    (swap! (table handle) update-in [name :waiting] (fnil conj []) resolve)))

     :else
     (if-let [message (refusal handle name)]
       (js/Promise.resolve (refuse! handle name message))
       (spawn! handle name (template-of handle name) on-line)))))

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
