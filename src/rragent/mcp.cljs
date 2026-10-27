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
            ["@earendil-works/pi-mcp/oauth" :refer [McpOAuthAuthorizationRequiredError adaptOAuthProvider authorizeMcp]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.gateway :as gateway]
            [rragent.mcp-oauth :as oauth]
            [rragent.secrets :as secrets]
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

(def var-pattern
  #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")

(defn- entry-strings
  "The strings of `entry` that `${NAME}` is filled into."
  [{:strs [url headers command args env cwd oauth]}]
  (filter string? (concat [url command cwd] (vals headers) args (vals env)
                          (when (map? oauth) (vals oauth)))))

(defn oauth-of
  "What `\"oauth\"` of an entry says, `${NAME}` filled in by `fill`:
   nil for none, `{}` for `true` (the service registers rragent itself),
   else `{:client-id :client-secret :scope}`."
  [oauth fill]
  (cond
    (true? oauth) {}
    (map? oauth) (let [{:strs [clientId clientSecret scope]} oauth]
                   (cond-> {}
                     (string? clientId) (assoc :client-id (fill clientId))
                     (string? clientSecret) (assoc :client-secret (fill clientSecret))
                     (string? scope) (assoc :scope scope)))
    :else nil))

(defn vars-of
  "The names of the `${NAME}`s of `entry`, in order, each once."
  [entry]
  (vec (distinct (for [s (entry-strings entry)
                       [_ var-name] (re-seq var-pattern s)]
                   var-name))))

(defn- substitute
  "`${NAME}` in `s` as `lookup` answers it."
  [lookup s]
  (str/replace (str s) var-pattern (fn [[_ var-name]] (lookup var-name))))

(defn- substitute-map
  [fill m]
  (into {} (map (fn [[k v]] [(name k) (fill v)])) m))

(defn env-lookup
  "A `${NAME}` as the environment has it; nil when it is not set."
  [var-name]
  (aget (.-env js/process) var-name))

(defn transport-spec
  "How to reach the server of `entry`, the file's map with string keys:
   `{:kind :http :url :headers}` or `{:kind :stdio :command :args :env
   :cwd}`, `${NAME}` filled in by `lookup` (the secrets, then the
   environment; the environment without); `{:error}` when the entry
   says neither, `{:error :needs}` when a `${NAME}` has no value, the
   names under `:needs`."
  ([entry home]
   (transport-spec entry home env-lookup))
  ([entry home lookup]
   (let [{:strs [url headers command args env cwd type oauth]} entry
         missing (vec (remove #(some? (lookup %)) (vars-of entry)))
         fill (partial substitute lookup)]
     (cond
       (= "sse" type) {:error "the legacy HTTP+SSE transport is not supported, only Streamable HTTP"}
       (seq missing) {:error (str "needs the secret" (when (next missing) "s") " " (str/join ", " missing)
                                  ", not saved yet (a field in the chat or the MCP settings takes it)")
                      :needs missing}
       (and (some? oauth) (not (or (true? oauth) (false? oauth) (map? oauth))))
       {:error "\"oauth\" is true, or an object with clientId, clientSecret and scope"}

       (and (string? url) (re-find #"^https?://" url)) (cond-> {:kind :http
                                                                :url (fill url)
                                                                :headers (substitute-map fill headers)}
                                                         (oauth-of oauth fill) (assoc :oauth (oauth-of oauth fill)))

       (and oauth (not (false? oauth))) {:error "OAuth is for a server with a url, not for a program"}
       (and (string? command) (not (str/blank? command))) {:kind :stdio
                                                           :command (fill command)
                                                           :args (mapv fill args)
                                                           :env (substitute-map fill env)
                                                           :cwd (if cwd (path/resolve home (fill cwd)) home)}
       url {:error "the url must start with https:// or http://"}
       :else {:error "the entry has neither a url nor a command"}))))

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
  "What says that `file` changed: its time, inode and size. A write of
   ours renames a new file over it, a new inode, so two quick writes in
   the same tick of the clock differ too."
  [file]
  (try
    (let [stat (fs/statSync file)]
      [(.-mtimeMs stat) (.-ino stat) (.-size stat)])
    (catch :default _ nil)))

;;; The registry

(defn registry
  "The MCP servers of `file` (`rragent.mcp/file` of the machine's
   folder), nothing connected yet: `{:file :home :secrets-file
   :transport :state}`. `secrets-file` holds the values of the
   `${NAME}`s (`rragent.secrets`, `secrets.json` beside `file`
   without); a name it lacks is looked up in the environment.
   `transport`, a test's, makes the transport of a server from its
   name and `transport-spec` instead of pi-mcp's stdio and HTTP
   transports. `state` is an atom of `{:mtime :servers :version}`,
   `servers` by name `{:entry :spec :timeout :client :connecting
   :tools :instructions :error :failed-at}`; `version` counts the
   changes of what the REPL sees."
  [{:keys [file home transport secrets-file oauth-file]}]
  {:file file
   :home (or home (some-> file path/dirname))
   :secrets-file (or secrets-file (some-> file path/dirname secrets/file))
   :oauth-file (or oauth-file (some-> file path/dirname oauth/file))
   ;; the page's address, which the services of an OAuth sign-in send
   ;; the browser back to: RRAGENT_URL, else what a sign-in was started at
   :base (atom (not-empty (aget (.-env js/process) "RRAGENT_URL")))
   :transport transport
   :state (atom {:mtime ::unread :servers {} :version 0})})

(defn- bump
  [state]
  (update state :version inc))

(defn redirect-of
  "Where the services send the browser back after a sign-in: the
   callback under the page's address (`:base`), else the address the
   server's client was registered with, else localhost's."
  [{:keys [base oauth-file]} server-name]
  (or (some-> @base oauth/redirect-url)
      (first (get-in (oauth/state-of oauth-file server-name) ["clientInformation" "redirect_uris"]))
      (oauth/redirect-url "http://localhost:8080")))

(defn oauth-provider
  "pi-mcp's OAuth provider of the server of `spec`; `on-redirect` gets
   the service's sign-in address."
  [{:keys [oauth-file] :as registry} server-name {:keys [url oauth]} & [on-redirect]]
  (oauth/provider (assoc oauth
                         :file oauth-file
                         :server-name server-name
                         :server-url url
                         :redirect (redirect-of registry server-name)
                         :on-redirect on-redirect)))

(defn- make-transport
  [{:keys [transport] :as registry} server-name {:keys [kind url headers command args env cwd oauth] :as spec}]
  (cond
    transport (transport server-name (cond-> spec
                                       oauth (assoc :auth-provider (adaptOAuthProvider (oauth-provider registry server-name spec)))))
    (= :http kind) (StreamableHttpTransport. (clj->js (cond-> {:url url :headers headers}
                                                        oauth (assoc :authProvider (adaptOAuthProvider (oauth-provider registry server-name spec))))))
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
  "Whether the server still has `entry` and `spec`: an attempt made for
   an entry the file changed meanwhile, or removed, or for a secret
   replaced since, must not touch the state."
  [{:keys [state]} server-name entry spec]
  (let [s (get-in @state [:servers server-name])]
    (and s (= entry (:entry s)) (= spec (:spec s)))))

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
        (if (same-entry? registry server-name entry spec)
          (swap! state (fn [s]
                         (bump (update-in s [:servers server-name] assoc
                                          :client client
                                          :tools tools
                                          :instructions (.-instructions client)
                                          :error nil
                                          :sign-in false
                                          :failed-at nil))))
          ;; the entry was changed or removed meanwhile
          (close-client! client)))
      (catch :default e
        (close-client! client)
        (when (same-entry? registry server-name entry spec)
          (let [sign-in? (instance? McpOAuthAuthorizationRequiredError e)]
            (swap! state (fn [s]
                           (bump (update-in s [:servers server-name] assoc
                                            :client nil
                                            :sign-in sign-in?
                                            :error (if sign-in?
                                                     "needs a sign-in at the service (OAuth): the person signs in with the link the MCP settings or a [:sign-in …] in your answer give"
                                                     (str (ex-message e) (stderr-tail transport)))
                                            :failed-at (js/Date.now)))))))))))

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
  [{:keys [spec client failed-at off sign-in]} now]
  (and (not off)
       (not sign-in)
       (not (:error spec))
       (nil? client)
       (or (nil? failed-at) (< retry-ms (- now failed-at)))))

(defn- lookup-of
  "The `${NAME}` lookup of the registry now: its secrets, then the
   environment."
  [{:keys [secrets-file]}]
  (let [saved (if secrets-file (secrets/read-all secrets-file) {})]
    (fn [var-name]
      (or (get saved var-name) (env-lookup var-name)))))

(defn- server-state
  "The state of the server `server-name` of `entry`, nothing connected;
   `lookup` fills in its `${NAME}`s."
  [registry lookup server-name entry]
  (cond
    (and (map? entry) (contains? entry :error)) {:entry entry :spec entry}
    (not (map? entry)) {:entry entry :spec {:error "the entry is no object"}}
    (not (re-matches name-pattern server-name)) {:entry entry :spec {:error "the name may have letters, digits, - and _ only"}}
    :else {:entry entry
           :off (false? (get entry "enabled"))
           :spec (transport-spec entry (:home registry) lookup)
           :timeout (timeout-of entry)}))

(defn reload!
  "The file and the secrets read again when one changed since the last
   reading: a new entry, or one whose entry or filled-in spec changed
   (a secret saved, replaced or removed), gets a new state, its old
   client closed; a removed one is closed. True when something
   changed."
  [{:keys [file secrets-file state] :as registry}]
  (let [now-mtime [(mtime file) (some-> secrets-file mtime)]]
    (when (not= now-mtime (:mtime @state))
      (let [entries (read-servers file)
            lookup (lookup-of registry)
            old (:servers @state)
            fresh (into {}
                        (map (fn [[server-name entry]]
                               [server-name (server-state registry lookup server-name entry)]))
                        entries)
            kept? (fn [server-name]
                    (let [before (get old server-name)
                          now (get fresh server-name)]
                      (and before now
                           (= (:entry before) (:entry now))
                           (= (:spec before) (:spec now)))))
            closing (for [[server-name {:keys [client]}] old
                          :when (not (kept? server-name))]
                      client)
            servers (into {}
                          (map (fn [[server-name s]]
                                 [server-name (if (kept? server-name) (get old server-name) s)]))
                          fresh)]
        (swap! state (fn [s] (bump (assoc s :mtime now-mtime :servers servers))))
        (doseq [client closing]
          (close-client! client))
        true))))

(defn- server-catalog
  [server-name {:keys [spec tools error instructions off client connecting sign-in]}]
  (cond-> {:name server-name
           :ns (ns-name-of server-name)
           :tools (vec tools)}
    off (assoc :off true)
    (:oauth spec) (assoc :oauth true)
    sign-in (assoc :sign-in true)
    (:error spec) (assoc :error (:error spec))
    (:needs spec) (assoc :needs (:needs spec))
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


;;; Changing the file: what mcp/add!, mcp/test, mcp/enable! and
;;; mcp/remove! of the REPL and the MCP settings of the page do

(def entry-keys
  "What `mcp/add!` takes, and the key of each in the file."
  {:url "url" :headers "headers" :token nil :oauth "oauth" :command "command" :args "args"
   :env "env" :cwd "cwd" :timeout-ms "timeoutMs" :enabled "enabled"})

(def secret-header-pattern
  "Headers whose values are credentials."
  #"(?i)auth|key|token|secret|password|cookie")

(defn- literal-secret?
  "A value given for a credential that is not a `${NAME}`: the token
   itself, which has no place in the file, nor in the conversation it
   came from."
  [v]
  (and (string? v) (not (str/blank? v)) (not (re-find var-pattern v))))

(defn- string-map?
  [m]
  (and (map? m) (every? (fn [[k v]] (and (or (keyword? k) (string? k)) (string? v))) m)))

(defn entry-of
  "The file's entry for what the REPL gives `mcp/add!`: `{:entry}`, the
   map with the file's string keys, or `{:error}`. `:token \"NAME\"`
   is the header `Authorization: Bearer ${NAME}`. A credential given as
   a value, not as a `${NAME}`, is refused: the person saves the value
   in a field of the page, the file names it."
  [opts]
  (let [{:keys [url headers token oauth command args env cwd timeout-ms enabled]} opts
        unknown (remove (set (keys entry-keys)) (keys opts))
        headers (into {} (map (fn [[k v]] [(name k) v])) headers)
        env (into {} (map (fn [[k v]] [(name k) v])) env)]
    (cond
      (not (map? opts)) {:error "mcp/add! takes a map: {:url \"https://…/mcp\" :token \"NAME\"} or {:command \"npx\" :args [...]}"}
      (seq unknown) {:error (str "mcp/add! does not know " (str/join ", " unknown) "; it takes " (str/join ", " (keys entry-keys)))}
      (= (some? url) (some? command)) {:error "mcp/add! takes either :url (a Streamable HTTP server) or :command (a program over stdio)"}
      (and url (not (and (string? url) (re-find #"^https?://" url)))) {:error "the :url must start with https:// or http://"}
      (and command (not (and (string? command) (not (str/blank? command))))) {:error "the :command is a program's name or path"}
      (and token (not (secrets/valid-name? token))) {:error (str ":token is the name of a secret, such as \"STORRITO_TOKEN\", not the token itself")}
      (and token (contains? headers "Authorization")) {:error "either :token or an Authorization header, not both"}
      (not (or (nil? (:headers opts)) (string-map? (:headers opts)))) {:error ":headers is a map of strings"}
      (not (or (nil? (:env opts)) (string-map? (:env opts)))) {:error ":env is a map of strings"}
      (not (or (nil? args) (and (sequential? args) (every? string? args)))) {:error ":args is a vector of strings"}
      (not (or (nil? cwd) (string? cwd))) {:error ":cwd is a folder, relative to the machine's"}
      (not (or (nil? timeout-ms) (and (number? timeout-ms) (pos? timeout-ms)))) {:error ":timeout-ms is a number of milliseconds"}
      (not (or (nil? enabled) (boolean? enabled))) {:error ":enabled is true or false"}
      (and oauth command) {:error ":oauth is for a server with a :url"}
      (and oauth token) {:error "either :oauth (the person signs in) or :token, not both"}
      (not (or (nil? oauth) (true? oauth)
               (and (map? oauth) (every? #{:client-id :client-secret :scope} (keys oauth)) (every? string? (vals oauth)))))
      {:error ":oauth is true, or {:client-id \"…\" :client-secret \"${NAME}\" :scope \"…\"} for a service that does not register clients itself"}
      (and (map? oauth) (literal-secret? (:client-secret oauth)))
      {:error "the :client-secret of :oauth goes in as ${NAME}, never as the value itself"}
      (some (fn [[k v]] (and (re-find secret-header-pattern k) (literal-secret? v))) headers)
      {:error "a credential in a header goes in as ${NAME}, as :token \"NAME\" does, never as the value itself: the person types the value into the field that (mcp/add! …) asks for"}
      (some (fn [[k v]] (and (re-find secrets/secret-env-pattern k) (literal-secret? v))) env)
      {:error "a credential in :env goes in as ${NAME}, never as the value itself: the person types the value into the field that (mcp/add! …) asks for"}
      (some (fn [s] (and (string? s) (re-find #"(?i)[?&][a-z_]*(key|token|secret)=[^$&]" s))) [url])
      {:error "a credential in the url goes in as ${NAME}, never as the value itself"}
      :else
      {:entry (cond-> {}
                url (assoc "url" url)
                (or token (seq headers)) (assoc "headers" (cond-> headers
                                                            token (assoc "Authorization" (str "Bearer ${" token "}"))))
                command (assoc "command" command)
                (seq args) (assoc "args" (vec args))
                (seq env) (assoc "env" env)
                cwd (assoc "cwd" cwd)
                (true? oauth) (assoc "oauth" true)
                (map? oauth) (assoc "oauth" (cond-> {}
                                              (:client-id oauth) (assoc "clientId" (:client-id oauth))
                                              (:client-secret oauth) (assoc "clientSecret" (:client-secret oauth))
                                              (:scope oauth) (assoc "scope" (:scope oauth))))
                timeout-ms (assoc "timeoutMs" timeout-ms)
                (false? enabled) (assoc "enabled" false))})))

(defn- read-config
  "The whole file as data, string keys, `{}` without it; throws when
   it is no JSON object, which is the person's to fix, not to be
   written over."
  [file]
  (if-not (fs/existsSync file)
    {}
    (let [config (try
                   (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))
                   (catch :default e
                     (throw (js/Error. (str file " is not JSON (" (ex-message e) "); fix it or remove it first")))))]
      (if (map? config)
        config
        (throw (js/Error. (str file " is no JSON object; fix it or remove it first")))))))

(defn- write-config!
  "The file written whole, beside it and renamed over it, readable by
   its owner only."
  [file config]
  (let [tmp (str file ".tmp-" (.-pid js/process) "-" (js/Date.now))]
    (fs/mkdirSync (path/dirname file) #js {:recursive true})
    (fs/writeFileSync tmp (str (js/JSON.stringify (clj->js config) nil 2) "\n") #js {:mode 384})
    (fs/renameSync tmp file)))

(defn- change-servers!
  "The `mcpServers` of the file through `f`, the other keys kept, and
   the file read again at once."
  [{:keys [file state] :as registry} f]
  (let [config (read-config file)
        servers (get config "mcpServers")]
    (write-config! file (assoc config "mcpServers" (f (if (map? servers) servers {}))))
    (swap! state assoc :mtime ::unread)
    (reload! registry)))

(defn status
  "What the REPL learns of one server: `{:name :ns :connected
   :connecting :off :tools :needs :error :instructions}`, `:tools` the
   names of its tools; nil for a server that is not there."
  [{:keys [state]} server-name]
  (when-let [s (get-in @state [:servers server-name])]
    (-> (server-catalog server-name s)
        (update :tools #(mapv :name %)))))

(defn- known!
  [{:keys [state file]} server-name]
  (when-not (get-in @state [:servers server-name])
    (throw (js/Error. (str "There is no MCP server " server-name " in " file "; (mcp/servers) lists them.")))))

(defn ^:async test!
  "The server connected anew, the old connection closed: its `status`
   once the attempt is over, the tools it has or why it failed. A
   server that is off or lacks a secret says so without an attempt."
  [{:keys [state] :as registry} server-name]
  (reload! registry)
  (known! registry server-name)
  (let [{:keys [off spec connecting client]} (get-in @state [:servers server-name])]
    (when-not (or off (:error spec))
      (when-not connecting
        (swap! state (fn [s] (bump (update-in s [:servers server-name] assoc :client nil :failed-at nil :error nil :sign-in false))))
        (close-client! client))
      (await (connect! registry server-name)))
    (status registry server-name)))

(defn ^:async add!
  "The server `server-name` added to the file, or its entry replaced,
   from `opts` (`entry-of`), and connected right away (`test!`): its
   `status`, with `:needs` the secrets the person still has to save."
  [registry server-name opts]
  (when-not (and (string? server-name) (re-matches name-pattern server-name))
    (throw (js/Error. (str "A server's name has letters, digits, - and _: " (pr-str server-name)))))
  (let [{:keys [entry error]} (entry-of opts)]
    (when error
      (throw (js/Error. error)))
    (change-servers! registry #(assoc % server-name entry))
    (await (test! registry server-name))))

(defn ^:async enable!
  "The server switched on (connected right away) or off (closed),
   `enabled: false` in its entry or not: its `status`."
  [registry server-name on?]
  (reload! registry)
  (known! registry server-name)
  (change-servers! registry (fn [servers]
                              (if (map? (get servers server-name))
                                (update servers server-name #(if on? (dissoc % "enabled") (assoc % "enabled" false)))
                                servers)))
  (if on?
    (await (test! registry server-name))
    (status registry server-name)))

(defn remove!
  "The server taken out of the file and closed, its OAuth sign-in
   forgotten. True."
  [{:keys [oauth-file] :as registry} server-name]
  (reload! registry)
  (known! registry server-name)
  (change-servers! registry #(dissoc % server-name))
  (when oauth-file
    (oauth/forget! oauth-file server-name))
  true)

;;; OAuth: the person signs in at the service

(defn- oauth-spec!
  "The spec of an OAuth server, or a throw that says why it is none."
  [{:keys [state] :as registry} server-name]
  (reload! registry)
  (known! registry server-name)
  (let [{:keys [spec]} (get-in @state [:servers server-name])]
    (cond
      (:error spec) (throw (js/Error. (str "The MCP server " server-name " cannot be reached: " (:error spec))))
      (not (:oauth spec)) (throw (js/Error. (str "The MCP server " server-name " does not sign in with OAuth (\"oauth\": true in its entry).")))
      :else spec)))

(defn ^:async sign-in!
  "The start of a sign-in at the service of `server-name`, `base` the
   page's address the service sends the browser back to: `{:url}`, the
   service's sign-in page for the person's browser, with a state of its
   own. pi-mcp discovers the service's authorization server and
   registers rragent there the first time."
  [{:keys [base oauth-file] :as registry} server-name page-base]
  (let [spec (oauth-spec! registry server-name)]
    (when (seq (str page-base))
      (when-not @base
        (reset! base page-base)))
    (oauth/fresh-attempt! oauth-file server-name (redirect-of registry server-name))
    (let [url (atom nil)
          provider (oauth-provider registry server-name spec #(reset! url %))
          result (await (authorizeMcp provider #js {:serverUrl (:url spec) :skipRefresh true}))]
      (if (and (= "REDIRECT" result) @url)
        {:url @url}
        (throw (js/Error. (str "The service of " server-name " started no sign-in.")))))))

(defn ^:async signed-in!
  "The end of a sign-in: the `code` the service sent the browser back
   with, for the sign-in whose `oauth-state` it carries, exchanged for
   tokens, and the server connected (`test!`). Answers the server's
   name and its `status`, `{:server :status}`; throws for a state no
   sign-in waits for."
  [{:keys [oauth-file] :as registry} oauth-state code & [iss]]
  (let [server-name (or (oauth/server-by-state oauth-file oauth-state)
                        (throw (js/Error. "No sign-in waits for this answer; start it again.")))
        spec (oauth-spec! registry server-name)
        provider (oauth-provider registry server-name spec)]
    (await (authorizeMcp provider (clj->js (cond-> {:serverUrl (:url spec) :authorizationCode code}
                                             (seq iss) (assoc :iss iss)))))
    (oauth/update-state! oauth-file server-name #(dissoc % "oauthState" "codeVerifier"))
    {:server server-name
     :status (await (test! registry server-name))}))

(defn sign-out!
  "The tokens and the registration of `server-name` forgotten, its
   connection closed: the next connection needs a sign-in again."
  [{:keys [oauth-file state] :as registry} server-name]
  (oauth-spec! registry server-name)
  (oauth/forget! oauth-file server-name)
  (let [client (get-in @state [:servers server-name :client])]
    (swap! state (fn [s] (bump (update-in s [:servers server-name] assoc :client nil :tools [] :sign-in true
                                          :error "signed out: the person signs in again with the link of the MCP settings"))))
    (close-client! client))
  (status registry server-name))

(defn- address
  "Where the server is, for the page: the URL without its query and
   credentials, or the program's name; never a filled-in `${NAME}`."
  [entry]
  (let [{:strs [url command]} (when (map? entry) entry)]
    (cond
      (string? url) (try
                      (let [u (js/URL. url)]
                        (str (.-protocol u) "//" (.-host u) (.-pathname u)))
                      (catch :default _ url))
      (string? command) (path/basename command)
      :else nil)))

(defn using
  "The names of the servers whose entries name the secret
   `secret-name` as `${NAME}`."
  [{:keys [state]} secret-name]
  (vec (for [[server-name {:keys [entry]}] (sort-by key (:servers @state))
             :when (and (map? entry) (some #{secret-name} (vars-of entry)))]
         server-name)))

(defn overview
  "The servers for the MCP settings of the page, by name:
   `{:name :address :connected :connecting :off :tools :needs :error}`,
   `:tools` how many."
  [{:keys [state]}]
  (vec (for [[server-name {:keys [entry] :as s}] (sort-by key (:servers @state))]
         (-> (server-catalog server-name s)
             (update :tools count)
             (dissoc :ns :instructions)
             (assoc :address (address entry))))))

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
