(ns rragent.projects
  "An artifact with a process: a directory under `work/artifacts/` with
   a package.json. rragent starts it (`node <main>`, `server.mjs`
   unless the package names another file; or, with `\"rragent\":
   \"motion\"` in the package, this program's `motion` command on the
   folder; PORT in its environment) when it is on screen or changed
   lately, stops it when no one wanted it for a while, shows its page
   in the iframe through a proxy under `/artifacts/<name>/`
   (`rragent.proxy`), passes a command posted from that page on to it,
   restarts it when it dies, and writes its output to `.log` in the
   directory. The port of
   pchat.projects: one folder, no chats, and no nREPL reload, since a
   Node project of the templates reads its files again per request and
   its page loads them again when they change.

   The key this namespace owns in the db (`rragent.db`):

       :projects {name {:port :ready-at :exit}}"
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.program :as program]
            [rragent.proxy :as proxy]
            [rragent.rframes.http :as http]))

(defn handle
  "`{:db the db, :dir the artifacts folder, :shown? a function of no
   arguments, whether someone looks at the page right now (yes, by
   default), :env extra environment for the processes}`. The processes
   are kept by name."
  [{:keys [dir shown?] :as opts}]
  (assoc opts
         :dir (path/resolve dir)
         :shown? (or shown? (constantly true))
         :procs (atom {})))

(defn names
  "The artifacts that are projects: subdirectories with a package.json."
  [dir]
  (->> (try
         (array-seq (fs/readdirSync dir #js {:withFileTypes true}))
         (catch :default _
           []))
       (filter (fn [^js entry]
                 (and (.isDirectory entry)
                      (not (str/starts-with? (.-name entry) "."))
                      (artifacts/project? (path/join dir (.-name entry))))))
       (map (fn [^js entry] (.-name entry)))
       (sort)
       (vec)))

(defn- package
  [project-dir]
  (try
    (js/JSON.parse (fs/readFileSync (path/join project-dir "package.json") "utf8"))
    (catch :default _
      #js {})))

(defn main-file
  "The file Node runs for the project: `main` of its package.json,
   `server.mjs` without."
  [project-dir]
  (or (not-empty (aget (package project-dir) "main"))
      "server.mjs"))

(defn command
  "What runs the project, `{:cmd :args}`: this program's `motion`
   command for a package that says `\"rragent\": \"motion\"`, else
   Node on the main file."
  [project-dir]
  (if (= "motion" (aget (package project-dir) "rragent"))
    (program/command "motion" project-dir)
    {:cmd (.-execPath js/process)
     :args [(main-file project-dir)]}))

(defn ^:async free-port
  "A port nothing listens on right now."
  []
  (js/Promise. (fn [resolve reject]
                 (let [server (net/createServer)]
                   (.once server "error" reject)
                   (.listen server 0 "127.0.0.1"
                            (fn []
                              (let [port (.-port (.address server))]
                                (.close server (fn [] (resolve port))))))))))

(def idle-ms
  "A project no one looks at for this long is stopped; it starts again
   when it is wanted."
  (* 15 60 1000))

(def recent-ms
  "A project with a change this recent is wanted: the agent works on it."
  (* 10 60 1000))

(def restart-after-ms
  "A project that died comes back, not more often than this."
  10000)

(defn- project-dir
  [{:keys [dir]} name]
  (path/join dir name))

(defn- newest-change
  [project-dir]
  (reduce max 0 (map :mtime (artifacts/files project-dir))))

(defn- wanted?
  "The project on screen while someone looks at the page, or one that
   changed lately."
  [{:keys [db shown?] :as handle} name now]
  (or (and (shown?)
           (= name (:artifacts/current @db)))
      (< (- now (newest-change (project-dir handle name))) recent-ms)))

(defn- environment
  [{:keys [env]} name port]
  (js/Object.assign #js {}
                    (.-env js/process)
                    (clj->js (or env {}))
                    #js {:PORT (str port)
                         ;; the path the page is shown under, for a link that needs it
                         :RRAGENT_BASE (str artifacts/url-prefix "/" name)
                         ;; the project exits by itself when rragent is gone
                         :RRAGENT_PID (str (.-pid js/process))}))

(defn- note-exit!
  "The exit of the process into the table and the db, when it is still
   the project's process (a restart replaced it otherwise)."
  [{:keys [procs db]} name process code signal]
  (when (identical? process (:process (get @procs name)))
    (let [exit (or code (if signal 1 0))]
      (swap! procs assoc-in [name :exit] exit)
      (swap! db assoc-in [:projects name :exit] exit))))

(defn ^:async start!
  "The project's process started, its output to `.log`."
  [{:keys [procs db] :as handle} name]
  (let [project-dir (project-dir handle name)
        port (await (free-port))
        log (fs/openSync (path/join project-dir ".log") "a")
        {:keys [cmd args]} (command project-dir)
        process (child/spawn cmd
                             (clj->js args)
                             #js {:cwd project-dir
                                  :env (environment handle name port)
                                  :stdio #js ["ignore" log log]
                                  ;; a process group of its own, stopped as one
                                  :detached true})
        now (js/Date.now)]
    (fs/closeSync log)
    (.on process "exit" (fn [code signal] (note-exit! handle name process code signal)))
    (.on process "error" (fn [error]
                           (js/console.error "[projects]" name (str error))
                           (note-exit! handle name process 1 nil)))
    (swap! procs assoc name {:process process
                             :port port
                             :started now
                             :last-wanted now})
    (swap! db assoc-in [:projects name] {:port port :ready-at nil})
    name))

(defn- kill!
  "The process group ended; the process alone when it has no group."
  [^js process]
  (when-let [pid (.-pid process)]
    (try
      (js/process.kill (- pid) "SIGTERM")
      (catch :default _
        (try
          (.kill process "SIGTERM")
          (catch :default _
            nil))))))

(defn stop!
  [{:keys [procs db]} name]
  (when-let [{:keys [process]} (get @procs name)]
    (kill! process))
  (swap! procs dissoc name)
  (swap! db update :projects dissoc name))

(defn stop-all!
  [{:keys [procs] :as handle}]
  (doseq [name (keys @procs)]
    (stop! handle name)))

(defn ^:async answers?
  "Whether something accepts a connection on the port."
  [port]
  (js/Promise. (fn [resolve]
                 (let [socket (net/connect #js {:host "127.0.0.1" :port port})]
                   (.setTimeout socket 200)
                   (.once socket "connect" (fn [] (.destroy socket) (resolve true)))
                   (.once socket "timeout" (fn [] (.destroy socket) (resolve false)))
                   (.once socket "error" (fn [] (resolve false)))))))

(defn- ^:async check-ready!
  [{:keys [db procs]} name]
  (let [{:keys [port exit]} (get @procs name)]
    (when (and port (nil? exit) (nil? (get-in @db [:projects name :ready-at])))
      (when (await (answers? port))
        (swap! db assoc-in [:projects name :ready-at] (js/Date.now))))))

(defn- ^:async restart-exited!
  [{:keys [procs] :as handle} name now]
  (let [{:keys [exit started]} (get @procs name)]
    (when (and exit (< restart-after-ms (- now started)))
      (stop! handle name)
      (await (start! handle name)))))

(defn- stop-idle!
  "A running project no one wanted for `idle-ms` is stopped: a process
   and its memory for nothing."
  [{:keys [procs] :as handle} name now wanted?]
  (when-let [{:keys [last-wanted]} (get @procs name)]
    (if wanted?
      (swap! procs assoc-in [name :last-wanted] now)
      (when (< idle-ms (- now last-wanted))
        (stop! handle name)))))

(defn ^:async sync!
  "Over the projects of the folder: starts the ones that are wanted, the
   one on screen and the ones with recent changes, stops the ones that
   went or idled, notes which are ready, restarts the dead."
  [{:keys [dir procs] :as handle}]
  (let [current (set (names dir))
        now (js/Date.now)]
    (doseq [name (keys @procs)
            :when (not (contains? current name))]
      (stop! handle name))
    (doseq [name current
            :let [wanted? (wanted? handle name now)]]
      (if (contains? @procs name)
        (do (await (check-ready! handle name))
            (await (restart-exited! handle name now))
            (stop-idle! handle name now wanted?))
        (when wanted?
          (await (start! handle name)))))))

(defn watch!
  "`sync!` once a second, and every project stopped when the process
   ends. Answers a function that ends the watch and the projects."
  [handle]
  (let [busy? (atom false)
        timer (js/setInterval (fn []
                                (when (compare-and-set! busy? false true)
                                  (-> (sync! handle)
                                      (.catch (fn [error] (js/console.error "[projects]" (str error))))
                                      (.finally (fn [] (reset! busy? false))))))
                              1000)
        on-exit (fn [] (stop-all! handle))]
    (.unref timer)
    (.on js/process "exit" on-exit)
    (fn []
      (js/clearInterval timer)
      (.off js/process "exit" on-exit)
      (stop-all! handle))))

(defn split-path
  "\"name/rest/of/it\" of the wildcard into [name \"rest/of/it\"]."
  [subpath]
  (let [[name & rest] (str/split (str subpath) #"/" 2)]
    [name (or (first rest) "")]))

(defn- media-file?
  "A picture, a video or a sound the agent put into the project's
   folder (a clip it cut, a still): served as the file it is, since the
   project's process serves only its page."
  [dir name rest]
  (and (not (str/blank? rest))
       (#{:image :video :audio} (artifacts/kind rest))
       (not (artifacts/hidden? rest))
       (http/regular-file? (path/join dir name rest))))

(defn ^:async handler
  "The handler of `/artifacts/*`, the rest of the path under `:*`: a
   running project's page goes to its process, `/artifacts/<name>`
   without the slash is sent to `/artifacts/<name>/` so that the page's
   relative links work; its media files and anything else are left to
   the caller, without a response."
  [{:keys [procs db dir]} w]
  (let [[name rest] (split-path (get-in w [:ring/route-params :*]))
        {:keys [port]} (get @procs name)
        uri (get-in w [:ring/request :uri])]
    (cond
      (not (and port (get-in @db [:projects name :ready-at])))
      w

      (and (str/blank? rest) (not (str/ends-with? (str uri) "/")))
      (assoc w :ring/response {:status 302
                               :headers {"Location" (str uri "/")}
                               :body ""})

      (media-file? dir name rest)
      w

      :else
      (assoc w :ring/response (await (proxy/request port rest (:ring/request w)))))))

(defn referer-project
  "The running project whose page a request came from: the Referer
   names `/artifacts/<name>/`, and the project is ready."
  [{:keys [procs db]} request]
  (when-let [[_ name] (re-find #"^https?://[^/]+/artifacts/([^/?#]+)/" (str (get-in request [:headers "referer"])))]
    (when (and (get @procs name)
               (get-in @db [:projects name :ready-at]))
      name)))

(defn ^:async command-handler
  "`POST /command` from a project's page goes to the project's process:
   the runtime of the page posts its commands to the origin's /command,
   and the page belongs to the project (its Referer says so). Any other
   request is left to the caller, without a response."
  [{:keys [procs] :as handle} w]
  (let [request (:ring/request w)]
    (if-let [name (referer-project handle request)]
      (assoc w :ring/response (await (proxy/request (:port (get @procs name)) "command" request)))
      w)))
