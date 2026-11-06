(ns rragent.tools.host
  "The tools that run a machine's calls (`rragent.machine`): what a
   program names under `:next`, run by the shell as a nested call of
   the machine tool under the step's key (`rragent.tools.machine`),
   so that a call a restart interrupted is found by its key and not
   made again. Only tools call them, never the model (`callers
   [\"tools\"]`); their value is for the state. `gateway` and
   `gateway-read` are one HTTP request through the gateway, the
   second one the host may make again after a restart (a GET);
   `storrito` a procedure of the organization's API; `composio` and
   `composio-read` the agent's services at Composio, the second the
   reads; `mcp` a tool of an MCP server, its pictures in the value for
   the model; `mcp-admin` a change of the servers; `connectors` the
   gateway's list read again, with the Storrito catalog; `spit` and
   `slurp` a file of the bot's work folder written and read; `events`
   the bot's events as data; `remember` is the bot's own tool
   (`rragent.tools.events`). An answer longer than `max-inline-chars`
   fails the call and names `:to-file`, a path under the work folder
   the body is written to instead."
  (:require ["@earendil-works/pi-ai" :refer [Type]]
            ["@earendil-works/pi-durable" :refer [defineTool]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [rragent.codemode :as codemode]
            [rragent.composio :as composio]
            [rragent.events :as events]
            [rragent.gateway :as gateway]
            [rragent.mcp :as mcp]
            [rragent.storrito :as storrito]
            [rragent.tools.events :as events-tool]
            [rragent.tools.work :as work]))

(def names
  ["gateway" "gateway-read" "storrito" "composio" "composio-read" "mcp" "mcp-admin" "connectors" "spit" "slurp" "events"])

(def max-inline-chars
  "How long an answer may be as data in the state: it is kept with
   every step of the machine."
  1000000)

(defn- answer
  "A result whose value is `data`, for the state."
  [data]
  #js {:output #js [#js {:type "text" :text (pr-str data)}]
       :structuredOutput (clj->js data)})

(defn- failure
  "An error result whose value says why, `more` the status and body of
   a failed request."
  [message & [more]]
  #js {:output #js [#js {:type "text" :text (str message)}]
       :structuredOutput (clj->js (merge more {:error (str message)}))
       :isError true})

(defn- tool
  "A tool of `name` over `parameters` whose `run` (an async function
   of the arguments as data, the call's api and context) answers the
   value; a throw is the failure, with the status and body of a failed
   request."
  [name description replay parameters run]
  (defineTool
   #js {:name name
        :description description
        :callers #js ["tools"]
        :replay replay
        :parameters parameters
        :structuredOutputSchema (.Any Type)
        :execute (^:async fn [args api call-context]
                   (try
                     (let [value (await (run (js->clj args :keywordize-keys true) api call-context))]
                       (when (< max-inline-chars (count (pr-str value)))
                         (throw (js/Error. (str "The answer is " (count (pr-str value)) " characters, more than " max-inline-chars
                                                " fit into the state: ask with :to-file, a path under your work folder, and the body goes there."))))
                       (answer value))
                     (catch :default e
                       (let [{:keys [status body]} (ex-data e)]
                         (failure (ex-message e) (when status {:status status :body body}))))))}))

(defn- file-of
  "The file `given` of the call's work folder, absolute; a throw for
   a path outside it."
  [api given]
  (let [dir (work/dir api)
        file (path/resolve dir (str given))]
    (when-not (str/starts-with? file (str dir path/sep))
      (throw (js/Error. (str "a path inside your work folder, not " (pr-str given)))))
    file))

(defn- sleep
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async request
  "One request through the gateway of `codemode` to the connection
   `which` at `path` with `opts` (`rragent.gateway/fetch!`), tried
   again as `:retry` of the opts says; `{:status :headers :body}`, or
   with `:to-file` the body written to that file of the work folder
   and `{:status :headers :file :size :type}` instead."
  [codemode api which path opts]
  (let [{:keys [statuses times delay-ms]} (:retry opts)
        to-file (:to-file opts)
        file (when to-file (file-of api to-file))]
    (loop [tries 1]
      (let [reply (await (gateway/fetch! (codemode/discovery codemode) which path (cond-> (dissoc opts :retry)
                                                                                   to-file (assoc :to-file true))))]
        (if (and (contains? (set statuses) (:status reply)) (< tries (or times 1)))
          (do (await (sleep (or delay-ms 1000)))
              (recur (inc tries)))
          (if to-file
            (do (fs/mkdirSync (path/dirname file) #js {:recursive true})
                (fs/writeFileSync file (:bytes reply))
                {:status (:status reply) :headers (:headers reply)
                 :file (str to-file) :size (.-length (:bytes reply)) :type (get-in reply [:headers :content-type])})
            reply))))))

(defn gateway
  [codemode]
  (tool "gateway" "One HTTP request through the Sprites gateway, for a machine." "unsafe"
        (.Object Type #js {:which (.String Type) :path (.String Type) :opts (.Optional Type (.Any Type))})
        (^:async fn [{:keys [which path opts]} api _]
          (await (request codemode api which path (or opts {}))))))

(defn gateway-read
  [codemode]
  (tool "gateway-read" "One GET through the Sprites gateway, for a machine; made again after a restart." "safe"
        (.Object Type #js {:which (.String Type) :path (.String Type) :opts (.Optional Type (.Any Type))})
        (^:async fn [{:keys [which path opts]} api _]
          (await (request codemode api which path (or opts {}))))))

(defn- storrito-connection!
  [codemode]
  (let [{:keys [connection catalog link error]} (codemode/storrito codemode)]
    (cond
      (and connection catalog) connection
      error (throw (js/Error. (str "The Storrito catalog at " link " could not be read: " error)))
      :else (throw (js/Error. "No Storrito among the connections: no custom_api connection of the gateway has a catalog at its base.")))))

(defn storrito-tool
  [codemode]
  (tool "storrito" "One procedure of the organization's Storrito API, for a machine." "unsafe"
        (.Object Type #js {:procedure (.String Type) :args (.Optional Type (.Any Type))})
        (^:async fn [{:keys [procedure args]} _ _]
          (await (storrito/call! (codemode/discovery codemode) (storrito-connection! codemode) procedure (or args {}))))))

(defn- ^:async meta-call!
  "The meta tool `slug` run with `args` in the session of the
   environment, through the Composio connection, awaited; the result
   as `rragent.composio/result` reads it."
  [codemode slug args]
  (let [discovery (codemode/discovery codemode)
        connection (composio/connection! discovery)
        session (composio/session-id!)]
    (composio/result slug (await (gateway/fetch! discovery (:id connection) (composio/meta-path session)
                                                 {:method "POST" :body {:slug slug :arguments args}})))))

(defn- ^:async rest-call!
  [codemode method path opts]
  (let [discovery (codemode/discovery codemode)
        connection (composio/connection! discovery)
        {:keys [status body]} (await (gateway/fetch! discovery (:id connection) path (assoc opts :method method)))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Composio answered HTTP " status " to " method " " path ": "
                           (or (get-in body [:error :message]) (if (string? body) body (js/JSON.stringify (clj->js body)))))
                      {:status status :body body})))
    body))

(defn- ^:async composio-action!
  "One of the composio/ calls run: `action` names it, the rest are
   its values, `session` the search session the shell carries."
  [codemode api call-context {:keys [action session] :as args}]
  (case action
    "search" (await (meta-call! codemode "COMPOSIO_SEARCH_TOOLS" (composio/search-arguments (:use-case args) (:toolkits args))))
    "schemas" (await (meta-call! codemode "COMPOSIO_GET_TOOL_SCHEMAS" (composio/schemas-arguments (:slugs args) session)))
    "execute" (await (meta-call! codemode "COMPOSIO_MULTI_EXECUTE_TOOL" (composio/execute-arguments (:slug args) (:args args) session)))
    "connections" (await (meta-call! codemode "COMPOSIO_MANAGE_CONNECTIONS" (composio/connections-arguments (:toolkits args) session)))
    "accounts" (composio/account-list (await (rest-call! codemode "GET" composio/accounts-path {:query (composio/accounts-query (composio/user-id!))})))
    "trigger-types" (composio/trigger-type-list (await (rest-call! codemode "GET" composio/trigger-types-path {:query (composio/trigger-types-query (:toolkit args))})))
    "triggers" (composio/trigger-list (await (rest-call! codemode "GET" composio/triggers-path {})))
    "add-trigger" (let [body (await (rest-call! codemode "POST" (composio/upsert-trigger-path (:slug args))
                                                {:body (composio/upsert-trigger-body (:account args) (composio/user-id!) (:config args))}))
                        id (or (:trigger_id body) (:id body))]
                    (events/append! (await (events-tool/file api call-context)) composio/trigger-fact {:id id :slug (str (:slug args)) :account (str (:account args))} (js/Date.now))
                    {:id id :slug (:slug args) :account (:account args)})
    "delete-trigger" (do (await (rest-call! codemode "DELETE" (composio/delete-trigger-path (:id args)) {}))
                         (events/append! (await (events-tool/file api call-context)) composio/trigger-deleted-fact {:id (str (:id args))} (js/Date.now))
                         {:id (:id args) :deleted true})
    (throw (js/Error. (str "No such Composio call: " action)))))

(def composio-parameters
  (.Object Type #js {:action (.String Type)
                     :use-case (.Optional Type (.String Type))
                     :toolkits (.Optional Type (.Any Type))
                     :slugs (.Optional Type (.Any Type))
                     :slug (.Optional Type (.String Type))
                     :args (.Optional Type (.Any Type))
                     :account (.Optional Type (.String Type))
                     :config (.Optional Type (.Any Type))
                     :toolkit (.Optional Type (.String Type))
                     :id (.Optional Type (.String Type))
                     :session (.Optional Type (.String Type))}))

(defn composio-tool
  [codemode]
  (tool "composio" "One change at Composio, for a machine: a tool run, a connection started, a trigger added or deleted." "unsafe"
        composio-parameters
        (fn [args api call-context] (composio-action! codemode api call-context args))))

(defn composio-read
  [codemode]
  (tool "composio-read" "One read at Composio, for a machine: a search, schemas, accounts, trigger types, triggers." "safe"
        composio-parameters
        (fn [args api call-context] (composio-action! codemode api call-context args))))

(defn mcp-tool
  [codemode]
  (tool "mcp" "One tool of an MCP server of the machine, for a machine." "unsafe"
        (.Object Type #js {:server (.String Type) :tool (.String Type) :args (.Optional Type (.Any Type))})
        (^:async fn [{:keys [server tool args]} _ call-context]
          (let [registry (:mcp codemode)]
            (when-not registry
              (throw (js/Error. "There are no MCP servers here.")))
            (let [{:keys [value images error]} (await (mcp/call! registry server tool (or args {}) {:signal (.-abortSignal call-context)}))]
              (when error
                (throw (js/Error. error)))
              {:value value :images (vec images)})))))

(defn mcp-admin
  [codemode]
  (tool "mcp-admin" "A change of the machine's MCP servers, for a machine." "unsafe"
        (.Object Type #js {:admin (.String Type) :name (.String Type) :opts (.Optional Type (.Any Type)) :on? (.Optional Type (.Boolean Type))})
        (^:async fn [{:keys [admin name opts on?]} _ _]
          (let [registry (:mcp codemode)]
            (when-not registry
              (throw (js/Error. "There are no MCP servers here.")))
            (case admin
              "add" (await (mcp/add! registry name (or opts {})))
              "test" (await (mcp/test! registry name))
              "enable" (await (mcp/enable! registry name (true? on?)))
              "remove" (await (mcp/remove! registry name))
              (throw (js/Error. (str "No such change of the MCP servers: " admin))))))))

(defn connectors
  [codemode]
  (tool "connectors" "The connectors of the Sprites gateway, read again, for a machine." "safe"
        (.Object Type #js {})
        (^:async fn [_ _ _]
          (let [discovery (await (gateway/discover! (:gateway codemode)))
                found (await (storrito/find-catalog! discovery))]
            (reset! (:discovery codemode) discovery)
            (reset! (:storrito codemode) found)
            (reset! (:asked codemode) (js/Date.now))
            (dissoc discovery :tries)))))

(defn spit
  []
  (tool "spit" "A file of the bot's work folder written, for a machine." "safe"
        (.Object Type #js {:path (.String Type) :content (.String Type)})
        (^:async fn [{:keys [path content]} api _]
          (let [file (file-of api path)]
            (fs/mkdirSync (path/dirname file) #js {:recursive true})
            (fs/writeFileSync file (str content) "utf8")
            (str path)))))

(defn slurp
  []
  (tool "slurp" "A file of the bot's work folder read, for a machine." "safe"
        (.Object Type #js {:path (.String Type)})
        (^:async fn [{:keys [path]} api _]
          (let [file (file-of api path)]
            (try
              (fs/readFileSync file "utf8")
              (catch :default e
                (throw (js/Error. (if (= "ENOENT" (.-code e))
                                    (str "No file " path " in your work folder.")
                                    (ex-message e))))))))))

(defn events-data
  []
  (tool "events" "The bot's events as data, for a machine: oldest first, :event and :since narrow them." "safe"
        (.Object Type #js {:event (.Optional Type (.String Type)) :since (.Optional Type (.Any Type))})
        (^:async fn [{:keys [event since]} api call-context]
          (events/select (events/read (await (events-tool/file api call-context))) (cond-> {} event (assoc :event event) since (assoc :since since))))))

(defn tools
  "All of them, over the core `codemode`."
  [codemode]
  [(gateway codemode) (gateway-read codemode) (storrito-tool codemode) (composio-tool codemode) (composio-read codemode)
   (mcp-tool codemode) (mcp-admin codemode) (connectors codemode) (spit) (slurp) (events-data)])
