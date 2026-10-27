(ns rragent.mcp
  "The MCP servers of the machine: services whose tools the agent
   reaches over the Model Context Protocol, each tool a function of
   the REPL (`mcp.<server>/<tool>`, see `rragent.codemode`). The
   servers are named in `mcp.json` of the machine's folder, the file
   pi, pchat and most MCP clients read:

       {\"mcpServers\":
        {\"storrito\" {\"url\" \"https://acme.storrito.com/mcp\"
                     \"headers\" {\"Authorization\" \"Bearer ${STORRITO_TOKEN}\"}}
         \"files\"    {\"command\" \"npx\" \"args\" [\"-y\" \"@modelcontextprotocol/server-filesystem\" \".\"]}}}

   A server is a URL (Streamable HTTP, with `headers`) or a program
   (stdio, with `args`, `env` and `cwd`, the machine's folder without);
   `${NAME}` in any of those is the environment variable, so that a
   token stays out of the file. `enabled: false` keeps an entry
   without connecting it, `timeoutMs` is how long one request may
   take, renewed by every progress the server reports (a minute
   without).

   The clients live in the main thread, one per server for every bot,
   and pi-mcp (`@earendil-works/pi-mcp`) speaks the protocol: the
   programs of the REPL run in worker threads that block while a call
   is answered (`rragent.codemode-worker`), so a connection outlives
   the worker a Stop ends, and an aborted call is cancelled at the
   server. A server is connected when a program first needs the list
   of tools (`catalog`), its tools listed then and again when it says
   that they changed; one that failed is tried again after
   `retry-ms`, one whose connection closed (a program that ended, an
   HTTP session that expired) at the next call. The file is read
   again when it changed: a server whose entry changed is connected
   anew, a removed one closed.

   A call answers what the REPL gets (`call!`): the structured content
   when the tool gives one, else the text of its content, as data when
   it is JSON (as `rragent.gateway/call` reads a body); its pictures
   go to the model after the program's answer. A tool's failure
   (`isError` in its result) and a failure of the protocol are both
   `{:error}`, which the REPL throws."
  (:require ["@earendil-works/pi-mcp" :refer [McpAbortError McpClient McpSessionExpiredError McpTimeoutError StdioTransport StreamableHttpTransport toLlmContent]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.gateway :as gateway]
            [rragent.version :as version]))

(def file-name
  "mcp.json")

(def default-timeout-ms
  60000)

(def retry-ms
  "How long a server that failed to connect is left alone before the
   next program tries it again: a server that is down costs a program
   its timeout once, not every program."
  30000)

(def connect-wait-ms
  "How long a program waits for the servers to connect before it runs
   without the slow ones."
  15000)

(def max-images
  "The pictures of one call that reach the model; the others are
   named only."
  4)

(def name-pattern
  #"[A-Za-z0-9_-]+")

(defn file
  "The file of the servers in the machine's folder `home`."
  [home]
  (path/join home file-name))

;;; Names in the REPL

(defn symbol-name
  "A name of the server or the tool as a symbol's name: anything but
   letters, digits, `_` and `-` as `-`, a leading digit after `t`."
  [s]
  (let [cleaned (str/replace (str s) #"[^A-Za-z0-9_-]" "-")]
    (if (re-find #"^[0-9]" cleaned) (str "t" cleaned) cleaned)))

(defn ns-name-of
  "The namespace of the server's tools in the REPL: mcp.<server>."
  [server]
  (str "mcp." (symbol-name server)))

;;; The file

(defn- substitute
  "`${NAME}` in `s` as the environment variable; a variable that is not
   set is an error that names it."
  [s]
  (str/replace (str s) #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
               (fn [[_ var-name]]
                 (let [value (aget (.-env js/process) var-name)]
                   (if (some? value)
                     value
                     (throw (js/Error. (str "the environment variable " var-name " is not set"))))))))

(defn- substitute-map
  [m]
  (into {} (map (fn [[k v]] [(name k) (substitute v)])) m))

(defn transport-spec
  "How to reach the server of `entry`, the file's map with string keys:
   `{:kind :http :url :headers}` or `{:kind :stdio :command :args :env
   :cwd}`, `${NAME}` filled in; `{:error}` when the entry says
   neither or a variable is missing."
  [entry home]
  (try
    (let [{:strs [url headers command args env cwd type]} entry]
      (cond
        (= "sse" type) {:error "the legacy HTTP+SSE transport is not supported, only Streamable HTTP"}
        (and (string? url) (re-find #"^https?://" url)) {:kind :http
                                                         :url (substitute url)
                                                         :headers (substitute-map headers)}
        (and (string? command) (not (str/blank? command))) {:kind :stdio
                                                            :command (substitute command)
                                                            :args (mapv substitute args)
                                                            :env (substitute-map env)
                                                            :cwd (if cwd (path/resolve home (substitute cwd)) home)}
        url {:error "the url must start with https:// or http://"}
        :else {:error "the entry has neither a url nor a command"}))
    (catch :default e
      {:error (ex-message e)})))

(defn timeout-of
  [entry]
  (let [given (get entry "timeoutMs")]
    (if (and (number? given) (pos? given)) given default-timeout-ms)))

(defn read-servers
  "`{name entry}` of the file, string keys, `{}` without it; a file
   that is no JSON is one entry `{:error}` under the name of the
   file, so that the REPL says why nothing is there."
  [file]
  (if-not (fs/existsSync file)
    {}
    (try
      (let [config (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))
            servers (get config "mcpServers")]
        (if (map? servers)
          servers
          {file-name {:error "the file has no \"mcpServers\" object"}}))
      (catch :default e
        {file-name {:error (str "the file is not JSON: " (ex-message e))}}))))

(defn- mtime
  [file]
  (try
    (.-mtimeMs (fs/statSync file))
    (catch :default _ nil)))

;;; The registry

(defn registry
  "The MCP servers of `file` (`rragent.mcp/file` of the machine's
   folder), nothing connected yet: `{:file :home :transport :state}`.
   `transport`, a test's, makes the transport of a server from its
   name and `transport-spec` instead of pi-mcp's stdio and HTTP
   transports. `state` is an atom of `{:mtime :servers :version}`,
   `servers` by name `{:entry :spec :timeout :client :connecting
   :tools :instructions :error :failed-at}`; `version` counts the
   changes of what the REPL sees."
  [{:keys [file home transport]}]
  {:file file
   :home (or home (some-> file path/dirname))
   :transport transport
   :state (atom {:mtime ::unread :servers {} :version 0})})

(defn- bump
  [state]
  (update state :version inc))

(defn- make-transport
  [{:keys [transport]} server-name {:keys [kind url headers command args env cwd] :as spec}]
  (cond
    transport (transport server-name spec)
    (= :http kind) (StreamableHttpTransport. #js {:url url :headers (clj->js headers)})
    :else (StdioTransport. #js {:command command
                                :args (clj->js args)
                                :env (clj->js env)
                                :cwd cwd
                                :stderr "pipe"})))

(defn- close-client!
  [client]
  (when client
    (-> (.close client) (.catch (fn [_] nil)))))

(defn- current?
  "Whether `client` is still the one of the server: a client that was
   replaced or closed says nothing anymore."
  [{:keys [state]} server-name client]
  (identical? client (get-in @state [:servers server-name :client])))

(defn- tool-data
  "What the REPL knows of a tool: `{:name :title :description
   :input-schema}`, the schema as data with string keys."
  [^js tool]
  (cond-> {:name (.-name tool)
           :description (or (.-description tool) "")
           :input-schema (js->clj (.-inputSchema tool))}
    (.-title tool) (assoc :title (.-title tool))))

(defn- stderr-tail
  "The last lines a program printed on stderr, for a failure."
  [transport]
  (let [text (str/trim (str (try (.-stderr transport) (catch :default _ ""))))]
    (when (seq text)
      (str " (stderr: " (subs text (max 0 (- (count text) 500))) ")"))))

(defn- ^:async refresh-tools!
  "The tools of the server listed again, after it said they changed."
  [{:keys [state] :as registry} server-name client]
  (try
    (let [tools (mapv tool-data (await (.listTools client)))]
      (when (current? registry server-name client)
        (swap! state (fn [s] (bump (assoc-in s [:servers server-name :tools] tools))))))
    (catch :default e
      (js/console.error "[mcp]" server-name "tools/list after a change failed:" (ex-message e)))))

(defn- same-entry?
  "Whether the server still has `entry`: an attempt made for an entry
   the file changed meanwhile, or removed, must not touch the state."
  [{:keys [state]} server-name entry]
  (let [s (get-in @state [:servers server-name])]
    (and s (= entry (:entry s)))))

(defn- ^:async open!
  "One connection to the server: initialize, its tools listed. Answers
   nothing; the state says how it went."
  [{:keys [state] :as registry} server-name]
  (let [{:keys [spec timeout entry]} (get-in @state [:servers server-name])
        transport (make-transport registry server-name spec)
        client (McpClient. #js {:name "rragent" :version version/version :requestTimeoutMs timeout})]
    (try
      (await (.connect client transport))
      (let [tools (mapv tool-data (await (.listTools client)))]
        (.onClose client (fn []
                           (when (current? registry server-name client)
                             (swap! state update-in [:servers server-name] assoc :client nil))))
        (.onNotification client "notifications/tools/list_changed"
                         (fn [_] (refresh-tools! registry server-name client)))
        (.onError client (fn [e] (js/console.error "[mcp]" server-name (ex-message e))))
        (if (same-entry? registry server-name entry)
          (swap! state (fn [s]
                         (bump (update-in s [:servers server-name] assoc
                                          :client client
                                          :tools tools
                                          :instructions (.-instructions client)
                                          :error nil
                                          :failed-at nil))))
          ;; the entry was changed or removed meanwhile
          (close-client! client)))
      (catch :default e
        (close-client! client)
        (when (same-entry? registry server-name entry)
          (swap! state (fn [s]
                         (bump (update-in s [:servers server-name] assoc
                                          :client nil
                                          :error (str (ex-message e) (stderr-tail transport))
                                          :failed-at (js/Date.now))))))))))

(defn- connect!
  "The server connected, once for every caller that asks meanwhile: a
   promise that resolves when the attempt is over."
  [{:keys [state] :as registry} server-name]
  (or (get-in @state [:servers server-name :connecting])
      (let [done (atom nil)
            attempt (-> (open! registry server-name)
                        (.finally (fn []
                                    (swap! state (fn [s]
                                                   (if (identical? @done (get-in s [:servers server-name :connecting]))
                                                     (update-in s [:servers server-name] dissoc :connecting)
                                                     s))))))]
        (reset! done attempt)
        (swap! state assoc-in [:servers server-name :connecting] attempt)
        attempt)))

(defn- wants-connection?
  "Whether the server should be connected now: on, reachable by its
   entry, not connected, and not failed within `retry-ms`."
  [{:keys [spec client failed-at off]} now]
  (and (not off)
       (not (:error spec))
       (nil? client)
       (or (nil? failed-at) (< retry-ms (- now failed-at)))))

(defn- server-state
  "The state of the server `server-name` of `entry`, nothing connected."
  [registry server-name entry]
  (cond
    (and (map? entry) (contains? entry :error)) {:entry entry :spec entry}
    (not (map? entry)) {:entry entry :spec {:error "the entry is no object"}}
    (not (re-matches name-pattern server-name)) {:entry entry :spec {:error "the name may have letters, digits, - and _ only"}}
    :else {:entry entry
           :off (false? (get entry "enabled"))
           :spec (transport-spec entry (:home registry))
           :timeout (timeout-of entry)}))

(defn reload!
  "The file read again when it changed since the last reading: a new
   or changed entry gets a new state (its old client closed), a
   removed one is closed. True when something changed."
  [{:keys [file state] :as registry}]
  (let [now-mtime (mtime file)]
    (when (not= now-mtime (:mtime @state))
      (let [entries (read-servers file)
            old (:servers @state)
            closing (for [[server-name {:keys [entry client]}] old
                          :when (not= entry (get entries server-name ::gone))]
                      client)
            servers (into {}
                          (map (fn [[server-name entry]]
                                 (let [before (get old server-name)]
                                   [server-name (if (and before (= entry (:entry before)))
                                                  before
                                                  (server-state registry server-name entry))])))
                          entries)]
        (swap! state (fn [s] (bump (assoc s :mtime now-mtime :servers servers))))
        (doseq [client closing]
          (close-client! client))
        true))))

(defn- server-catalog
  [server-name {:keys [spec tools error instructions off client connecting]}]
  (cond-> {:name server-name
           :ns (ns-name-of server-name)
           :tools (vec tools)}
    off (assoc :off true)
    (:error spec) (assoc :error (:error spec))
    (and error (not (:error spec))) (assoc :error error)
    instructions (assoc :instructions instructions)
    client (assoc :connected true)
    connecting (assoc :connecting true)))

(defn snapshot
  "What the REPL knows of the servers now, data for another thread:
   `{:version :servers [{:name :ns :tools :error :instructions :off
   :connected :connecting}]}`, by name."
  [{:keys [state]}]
  (let [{:keys [version servers]} @state]
    {:version version
     :servers (mapv (fn [[server-name s]] (server-catalog server-name s)) (sort-by key servers))}))

(defn ^:async catalog
  "The servers and their tools for a program: the file read again when
   it changed, every server that should be connected connected first
   (`wants-connection?`), in parallel, waited for at most `wait-ms`
   (`connect-wait-ms` without): a slow one goes on connecting and its
   tools come with a later program. See `snapshot`."
  [{:keys [state] :as registry} & [wait-ms]]
  (reload! registry)
  (let [now (js/Date.now)
        attempts (vec (for [[server-name s] (:servers @state)
                            :when (or (:connecting s) (wants-connection? s now))]
                        (connect! registry server-name)))]
    (when (seq attempts)
      (let [timer (atom nil)]
        (await (js/Promise.race #js [(js/Promise.all (clj->js attempts))
                                     (js/Promise. (fn [resolve]
                                                    (reset! timer (js/setTimeout resolve (or wait-ms connect-wait-ms)))))]))
        (js/clearTimeout @timer)))
    (snapshot registry)))

;;; A call

(defn- text-value
  "A text of the result as the REPL gets it: data when it is JSON, as
   `rragent.gateway/parse` reads a body, else the text."
  [text]
  (let [trimmed (str/trim (str text))]
    (if (re-find #"^[\[{]" trimmed)
      (gateway/parse trimmed)
      text)))

(defn result-value
  "What a call of the REPL answers for the tool's `result` (a
   CallToolResult): `{:value :images}`, the value the structured
   content when there is one, else the content as `toLlmContent`
   reads it, a text as data when it is JSON, one block itself and
   several a vector; a picture is in the value as a line that says it
   is shown after the answer, and among the `images`, at most
   `max-images`. `isError` makes it `{:error}` with the text."
  [^js result]
  (let [blocks (js->clj (toLlmContent result) :keywordize-keys true)
        images (filterv #(= "image" (:type %)) blocks)
        values (mapv (fn [{:keys [type text mimeType]}]
                       (if (= "image" type)
                         (str "[picture, " mimeType ", shown to you after the program's answer]")
                         (text-value text)))
                     blocks)
        structured (.-structuredContent result)]
    (if (true? (.-isError result))
      {:error (let [text (str/join "\n" (keep :text blocks))]
                (if (str/blank? text) "the tool failed and said nothing" text))}
      {:value (cond
                (some? structured) (js->clj structured :keywordize-keys true)
                (= 1 (count values)) (first values)
                (empty? values) nil
                :else values)
       :images (mapv (fn [{:keys [data mimeType]}] {:data data :mime-type mimeType})
                     (take max-images images))})))

(defn- progress-text
  [server-name tool ^js progress]
  (str "mcp." server-name "/" tool ": "
       (.-progress progress)
       (when (some? (.-total progress)) (str "/" (.-total progress)))
       (when (.-message progress) (str " " (.-message progress)))))

(defn- ^:async client-for!
  "The connected client of the server, connected now when it is not
   and has not failed within `retry-ms`: `{:client}` or `{:error}`."
  [{:keys [state] :as registry} server-name]
  (reload! registry)
  (let [s (get-in @state [:servers server-name])]
    (cond
      (nil? s) {:error (str "There is no MCP server " server-name " in " (:file registry) "; (mcp/servers) lists them.")}
      (:off s) {:error (str "The MCP server " server-name " is off (\"enabled\": false).")}
      (:error (:spec s)) {:error (str "The MCP server " server-name " cannot be reached: " (:error (:spec s)))}
      :else (do
              (when (or (:connecting s) (wants-connection? s (js/Date.now)))
                (await (connect! registry server-name)))
              (let [{:keys [client error]} (get-in @state [:servers server-name])]
                (if client
                  {:client client}
                  {:error (str "The MCP server " server-name " is not connected: " (or error "it closed"))}))))))

(defn- ^:async call-once
  [client tool args {:keys [signal on-progress timeout]}]
  (await (.callTool client tool (clj->js (or args {}))
                    #js {:signal signal
                         :timeoutMs timeout
                         :onProgress (fn [progress]
                                       (when on-progress (on-progress progress)))})))

(defn ^:async call!
  "The tool `tool` of the server `server-name` called with `args`, a
   map: `{:value :images}` (`result-value`) or `{:error}`; it never
   rejects. `signal` aborts the call, which the server is told;
   `on-progress` gets a line for every progress it reports, which also
   renews the timeout. An HTTP session the server forgot is opened
   again and the call made once more, the server having taken nothing
   of it."
  [{:keys [state] :as registry} server-name tool args & [{:keys [signal on-progress]}]]
  (let [label (str "mcp." server-name "/" tool)
        opts {:signal signal
              :timeout (get-in @state [:servers server-name :timeout] default-timeout-ms)
              :on-progress (when on-progress #(on-progress (progress-text server-name tool %)))}]
    (try
      (let [{:keys [client error]} (await (client-for! registry server-name))]
        (if error
          {:error error}
          (let [result (try
                         (await (call-once client tool args opts))
                         (catch :default e
                           (if (instance? McpSessionExpiredError e)
                             (do (swap! state update-in [:servers server-name] assoc :client nil)
                                 (close-client! client)
                                 (let [{again :client error :error} (await (client-for! registry server-name))]
                                   (if again
                                     (await (call-once again tool args opts))
                                     (throw (js/Error. error)))))
                             (throw e))))
                {:keys [error] :as answer} (result-value result)]
            (if error
              {:error (str label " failed: " error)}
              answer))))
      (catch :default e
        {:error (str label ": "
                     (cond
                       (instance? McpAbortError e) "the call was stopped"
                       (instance? McpTimeoutError e) (str "no answer within " (:timeout opts) " ms (\"timeoutMs\" in " file-name " sets it)")
                       :else (ex-message e)))}))))

(defn ^:async close!
  "Every connection closed, a program ended; the file is read again at
   the next catalog."
  [{:keys [state]}]
  (let [clients (keep :client (vals (:servers @state)))]
    (swap! state assoc :mtime ::unread :servers {})
    (await (js/Promise.all (clj->js (mapv close-client! clients))))))

(defn report
  "One line for the console: the servers of the file, when there are
   any."
  [{:keys [file] :as registry}]
  (reload! registry)
  (let [{:keys [servers]} (snapshot registry)]
    (when (seq servers)
      (str "MCP servers in " file ": "
           (str/join ", " (map (fn [{:keys [name off error]}]
                                 (str name (cond off " (off)" error (str " (" error ")"))))
                               servers))))))
