(ns rragent.projects
  "An artifact with a process: a directory under `work/artifacts/` with
   a package.json. Its template (`rragent.templates`, named by the
   package) says what runs it: the template's `entry`, an executable
   run as `<entry> <project-dir>` with PORT in its environment and the
   programs the template needs (`rragent.programs`); or a page of the
   project, served as it is, no process. A project without a template
   runs with Node on the `main` of its package.json, `server.mjs`
   without. rragent starts the process when the project is on screen
   or changed lately, stops it when no one wanted it for a while,
   shows its page in the iframe through a proxy under
   `/artifacts/<name>/` (`rragent.proxy`), passes a command posted
   from that page on to it, restarts it when it dies, and writes its
   output to `.log` in the directory. An eval goes to the process the
   same way (`eval!`), for a template that takes one. The port of
   pchat.projects: one folder, no chats, and no nREPL reload, since a
   project's process reads its files again when they change.

   The key this namespace owns in the bot's map of the db
   (`rragent.db`, under the handle's `:path`):

       :projects {name {:port :ready-at :exit}}         a process
       :projects {name {:static? true :entry :ready-at}} a page served as files"
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.artifacts :as artifacts]
            [rragent.db :as db]
            [rragent.programs :as programs]
            [rragent.proxy :as proxy]
            [rragent.rframes.http :as http]
            [rragent.templates :as templates]))

(defonce procs
  (atom {}))

(defn handle
  "`{:db the db, :path where the bot's map is in it (the db's value
   itself without), :dir the artifacts folder, :base the path the pane
   shows the projects under (`/artifacts`, or `/bot/<id>/artifacts`),
   :shown? a function of no arguments, whether someone looks at the
   page right now (yes, by default)}`. `:procs`, the processes by
   name, is shared by every handle of the process unless given, so
   that a tool reaches what the pane started."
  [{:keys [dir shown? procs base path] :as opts}]
  (assoc opts
         :dir (path/resolve dir)
         :path (or path [])
         :base (or base artifacts/url-prefix)
         :shown? (or shown? (constantly true))
         :procs (or procs rragent.projects/procs)))

(defn- state
  "The bot's map the handle writes in."
  [{:keys [db path]}]
  (db/at @db path))

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
  "The file Node runs for a project without a template: `main` of its
   package.json, `server.mjs` without."
  [project-dir]
  (or (not-empty (aget (package project-dir) "main"))
      "server.mjs"))

(defn static-template
  "The project's template when the pane serves the project as files,
   else nil."
  [project-dir]
  (let [template (templates/of-project project-dir)]
    (when (and template (not (:missing? template)) (templates/static? template))
      template)))

(defn command
  "What runs the project, `{:cmd :args :needs}`: its template's entry
   on the project's folder, or Node on the main file of a project
   without a template. Throws for a template that is not installed."
  [project-dir]
  (let [template (templates/of-project project-dir)]
    (cond
      (:missing? template)
      (throw (js/Error. (str "The template " (:name template) " is not installed: " (str/join ", " (templates/names)) ".")))

      template
      {:cmd (templates/entry-file template)
       :args [project-dir]
       :needs (:needs template)}

      :else
      {:cmd (.-execPath js/process)
       :args [(main-file project-dir)]
       :needs []})))

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
  [{:keys [shown?] :as handle} name now]
  (or (and (shown?)
           (= name (:artifacts/current (state handle))))
      (< (- now (newest-change (project-dir handle name))) recent-ms)))

(defn- environment
  [base name port needs]
  (js/Object.assign #js {}
                    (.-env js/process)
                    (clj->js (programs/environment needs))
                    #js {:PORT (str port)
                         ;; the path the page is shown under, for a link that needs it
                         :RRAGENT_BASE (str base "/" name)
                         ;; the project exits by itself when rragent is gone
                         :RRAGENT_PID (str (.-pid js/process))}))

(defn- note-exit!
  "The exit of the process into the table and the db, when it is still
   the project's process (a restart replaced it otherwise)."
  [{:keys [procs db path]} name process code signal]
  (when (identical? process (:process (get @procs name)))
    (let [exit (or code (if signal 1 0))]
      (swap! procs assoc-in [name :exit] exit)
      (swap! db db/update-at path assoc-in [:projects name :exit] exit))))

(defn- note-failure!
  "A project that cannot start: the reason into its `.log`, the exit
   into the table and the db."
  [{:keys [procs db path] :as handle} name message]
  (fs/appendFileSync (path/join (project-dir handle name) ".log") (str message "\n"))
  (swap! procs assoc name {:exit 1 :started (js/Date.now) :last-wanted (js/Date.now)})
  (swap! db db/update-at path assoc-in [:projects name] {:exit 1}))

(defn ^:async start!
  "The project's process started, its output to `.log`."
  [{:keys [procs db base path] :as handle} name]
  (let [project-dir (project-dir handle name)
        port (await (free-port))
        {:keys [cmd args needs] :as run} (try (command project-dir) (catch :default e {:error (.-message e)}))]
    (if-let [error (:error run)]
      (note-failure! handle name error)
      (let [log (fs/openSync (path/join project-dir ".log") "a")
            process (child/spawn cmd
                                 (clj->js args)
                                 #js {:cwd project-dir
                                      :env (environment base name port needs)
                                      :stdio #js ["ignore" log log]
                                      ;; a process group of its own, stopped as one
                                      :detached true})
            now (js/Date.now)]
        (fs/closeSync log)
        (.on process "exit" (fn [code signal] (note-exit! handle name process code signal)))
        (.on process "error" (fn [error]
                               (js/console.error "[projects]" name (str error))
                               (fs/appendFileSync (path/join project-dir ".log") (str error "\n"))
                               (note-exit! handle name process 1 nil)))
        (swap! procs assoc name {:process process
                                 :port port
                                 :started now
                                 :last-wanted now})
        (swap! db db/update-at path assoc-in [:projects name] {:port port :ready-at nil})))
    name))

(defn- kill!
  "The process group ended; the process alone when it has no group."
  [^js child]
  (when-let [pid (.-pid child)]
    (try
      (.kill js/process (- pid) "SIGTERM")
      (catch :default _
        (try
          (.kill child "SIGTERM")
          (catch :default _
            nil))))))

(defn stop!
  [{:keys [procs db path]} name]
  (when-let [{:keys [process]} (get @procs name)]
    (some-> process kill!))
  (swap! procs dissoc name)
  (swap! db db/update-at path update :projects dissoc name))

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
  [{:keys [db procs path] :as handle} name]
  (let [{:keys [port exit]} (get @procs name)]
    (when (and port (nil? exit) (nil? (get-in (state handle) [:projects name :ready-at])))
      (when (await (answers? port))
        (swap! db db/update-at path assoc-in [:projects name :ready-at] (js/Date.now))))))

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

(defn- sync-static!
  "A project the pane serves as files: ready at its newest change, so
   that the iframe loads the page again after one."
  [{:keys [db path] :as handle} name template]
  (let [entry {:static? true
               :entry (:entry template)
               :ready-at (newest-change (project-dir handle name))}]
    (when (not= entry (get-in (state handle) [:projects name]))
      (swap! db db/update-at path assoc-in [:projects name] entry))))

(defn- ^:async sync-process!
  [{:keys [procs] :as handle} name now]
  (let [wanted? (wanted? handle name now)]
    (if (contains? @procs name)
      (do (await (check-ready! handle name))
          (await (restart-exited! handle name now))
          (stop-idle! handle name now wanted?))
      (when wanted?
        (await (start! handle name))))))

(defn ^:async sync!
  "Over the projects of the folder: starts the ones that are wanted, the
   one on screen and the ones with recent changes, stops the ones that
   went or idled, notes which are ready, restarts the dead; a project
   of a static template is noted as ready with its newest change."
  [{:keys [dir procs db path] :as handle}]
  (let [current (set (names dir))
        now (js/Date.now)]
    (doseq [name (keys @procs)
            :when (not (contains? current name))]
      (stop! handle name))
    (doseq [name (keys (:projects (state handle)))
            :when (not (contains? current name))]
      (swap! db db/update-at path update :projects dissoc name))
    (doseq [name current]
      (if-let [template (static-template (project-dir handle name))]
        (sync-static! handle name template)
        (await (sync-process! handle name now))))))

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

(defn- redirect-to-folder
  "`/artifacts/<name>` without the slash is sent to `/artifacts/<name>/`
   so that the page's relative links work."
  [w uri]
  (assoc w :ring/response {:status 302
                           :headers {"Location" (str uri "/")}
                           :body ""}))

(defn- static-page
  "The entry page of a project served as files, or nothing for a
   project without it."
  [{:keys [dir]} name entry w]
  (if-let [file (http/resolve-file (path/join dir name) (str entry))]
    (assoc w :ring/response (http/file-response (:ring/request w) file))
    w))

(defn ^:async handler
  "The handler of `/artifacts/*`, the rest of the path under `:*`: a
   running project's page goes to its process, a static project's
   entry is served at `/artifacts/<name>/`, `/artifacts/<name>` without
   the slash is sent to `/artifacts/<name>/` so that the page's
   relative links work; its media files and anything else are left to
   the caller, without a response."
  [{:keys [procs dir] :as handle} w]
  (let [[name rest] (split-path (get-in w [:ring/route-params :*]))
        {:keys [port]} (get @procs name)
        {:keys [ready-at static? entry]} (get-in (state handle) [:projects name])
        uri (get-in w [:ring/request :uri])
        folder? (str/blank? rest)]
    (cond
      (and folder? (not (str/ends-with? (str uri) "/")) (or static? (and port ready-at)))
      (redirect-to-folder w uri)

      static?
      (if folder? (static-page handle name entry w) w)

      (not (and port ready-at))
      w

      (media-file? dir name rest)
      w

      :else
      (assoc w :ring/response (await (proxy/request port rest (:ring/request w)))))))

(defn- quoted
  "`s` as a regular expression that matches it."
  [s]
  (str/replace (str s) #"[.*+?^${}()|\[\]\\/]" (fn [m] (str "\\" m))))

(defn referer-project
  "The running project whose page a request came from: the Referer
   names `<base>/<name>/`, and the project is ready."
  [{:keys [procs base] :as handle} request]
  (when-let [[_ name] (re-find (re-pattern (str "^https?://[^/]+" (quoted base) "/([^/?#]+)/")) (str (get-in request [:headers "referer"])))]
    (when (and (get @procs name)
               (get-in (state handle) [:projects name :ready-at]))
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

(def wake-ms
  "How long a project that is started for an eval may take to answer."
  15000)

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async wake!
  "The project's process running and ready, started when it does not
   run: its port. Throws when there is no such project, when the pane
   serves it as files, or when it does not come up."
  [{:keys [procs db dir path] :as handle} name]
  (when-not (contains? (set (names dir)) name)
    (throw (js/Error. (str "artifacts/" name " is no project."))))
  (when (static-template (project-dir handle name))
    (throw (js/Error. (str "artifacts/" name " has no process: its template serves a page as it is."))))
  (when-not (contains? @procs name)
    (await (start! handle name)))
  (loop [waited 0]
    (let [{:keys [port exit]} (get @procs name)]
      (cond
        exit
        (throw (js/Error. (str "artifacts/" name " exited with " exit ", its .log says why.")))

        (await (answers? port))
        (do (when (nil? (get-in (state handle) [:projects name :ready-at]))
              (swap! db db/update-at path assoc-in [:projects name :ready-at] (js/Date.now)))
            port)

        (< wake-ms waited)
        (throw (js/Error. (str "artifacts/" name " did not come up in " (/ wake-ms 1000) " s, its .log says why.")))

        :else
        (do (await (sleep 100))
            (recur (+ waited 300)))))))

(defn ^:async eval!
  "`code` evaluated by the project's process, which takes it at `POST
   /eval` when its template takes an eval (the README says what the
   process must do): `{:status :text}`, the printed value with 200, the
   error otherwise, 404 or 405 from a process that takes none."
  [handle name code]
  (let [port (await (wake! handle name))
        response (await (js/fetch (str "http://127.0.0.1:" port "/eval")
                                  #js {:method "POST"
                                       :headers #js {"Content-Type" "text/plain; charset=utf-8"}
                                       :body (str code)}))]
    {:status (.-status response)
     :text (await (.text response))}))
